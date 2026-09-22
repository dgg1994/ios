package com.admin.service;

import java.util.Locale;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.admin.config.V26AdminProperties;
import com.admin.util.BIP32Util;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.web3j.utils.Numeric;

/**
 * 对齐 Python：TronGrid 请求带 {@code TRON-PRO-API-KEY}；
 * {@code /wallet/*} 在限流时改走 {@code TRON_FULLNODES} 里的下一个节点。
 */
@Component
public class TronGridClient {

    /** 与 Python address_balance.TRON_API_BASE / TRON_FULLNODES 一致。 */
    static final String API_BASE = "https://api.trongrid.io";
    private static final String[] FULLNODES = {
            "https://api.trongrid.io",
            "https://tron-rpc.publicnode.com",
    };

    private final RestTemplate http;
    private final String apiKey;

    public TronGridClient(V26AdminProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(15000);
        f.setReadTimeout(20000);
        this.http = new RestTemplate(f);
        String key = props == null || props.getTrongridApiKey() == null ? "" : props.getTrongridApiKey().trim();
        this.apiKey = key;
    }

    /** Python {@code /v1/accounts} 只打 TronGrid，失败返回 null，由调用方改走 fullnode。 */
    public JSONObject getAccounts(String address) {
        try {
            ResponseEntity<String> resp = http.exchange(
                    API_BASE + "/v1/accounts/" + address, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
            return JSON.parseObject(resp.getBody());
        } catch (Exception e) {
            return null;
        }
    }

    /** wallet/getaccount、trigger*、broadcast 等。429/5xx 换下一个 fullnode。 */
    public JSONObject postWallet(String path, JSONObject body) {
        String p = path.startsWith("/") ? path : "/" + path;
        RestClientResponseException lastHttp = null;
        Exception last = null;
        for (String base : FULLNODES) {
            try {
                ResponseEntity<String> resp = http.exchange(
                        base + p, HttpMethod.POST, new HttpEntity<>(body.toJSONString(), headers()), String.class);
                return JSON.parseObject(resp.getBody());
            } catch (RestClientResponseException e) {
                lastHttp = e;
                int code = e.getRawStatusCode();
                if (code == 429 || code >= 500) {
                    continue;
                }
                JSONObject parsed = parseQuiet(e.getResponseBodyAsString());
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (lastHttp != null) {
            throw lastHttp;
        }
        if (last != null) {
            throw new IllegalStateException(last.getMessage() == null ? "TRON 节点不可用" : last.getMessage(), last);
        }
        return null;
    }

    public static String abiAddressParam(String base58OrHex) {
        String hex;
        String raw = base58OrHex == null ? "" : base58OrHex.trim();
        if (raw.startsWith("T")) {
            hex = base58ToHex41(raw);
            if (hex.startsWith("41")) {
                hex = hex.substring(2);
            }
        } else {
            hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
            if (hex.startsWith("41") && hex.length() == 42) {
                hex = hex.substring(2);
            }
        }
        while (hex.length() < 64) {
            hex = "0" + hex;
        }
        return hex.toLowerCase(Locale.ROOT);
    }

    public static String toHex41(String addr) {
        String raw = addr == null ? "" : addr.trim();
        if (raw.startsWith("T")) {
            return base58ToHex41(raw);
        }
        String hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        hex = hex.toLowerCase(Locale.ROOT);
        if (hex.startsWith("41") && hex.length() >= 42) {
            return hex.substring(0, 42);
        }
        return hex;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Accept", "application/json");
        h.set("User-Agent", "v26-admin/1.0");
        if (!apiKey.isEmpty()) {
            h.set("TRON-PRO-API-KEY", apiKey);
        }
        return h;
    }

    private static JSONObject parseQuiet(String raw) {
        try {
            if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) {
                return null;
            }
            return JSON.parseObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String base58ToHex41(String addr) {
        try {
            byte[] decoded = BIP32Util.base58Decode(addr);
            if (decoded.length < 21) {
                return "";
            }
            byte[] payload = java.util.Arrays.copyOfRange(decoded, 0, 21);
            return Numeric.toHexStringNoPrefix(payload);
        } catch (Exception e) {
            return "";
        }
    }
}
