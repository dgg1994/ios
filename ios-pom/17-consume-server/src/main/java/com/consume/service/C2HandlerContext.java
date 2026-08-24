package com.consume.service;

import java.util.Map;

import com.alibaba.fastjson.JSONObject;
import com.consume.entity.C2RecordsEntity;

/**
 * C2 handler 处理上下文。
 *
 * 字段对齐 XADD.md：record_id / kind / method / path / client_ip / x_ts / headers / body，
 * 并附带加载到的 c2_records 实体与解密结果，供各 handle_* 使用。
 */
public class C2HandlerContext {

    private final C2RecordsEntity record;
    private final String notifyEntry;
    private final String transport;

    /** 解密后的明文 JSON（成功时）；失败为 null */
    private JSONObject plaintext;
    /** 解密后的明文 JSON 文本 */
    private String plaintextJson;
    /** 解密原始文本（明文回退时也可能直接是 body） */
    private String rawText;
    /** 解密是否成功 */
    private boolean decryptOk;
    /** 解密错误信息 */
    private String decryptError;
    /** 解密 success（1/0），用于落库 */
    private Integer success;
    /** 密钥标签（prefix + x-ts） */
    private String keyLabel;
    /** 解密前缀 */
    private String prefix;

    /** 从 headers 解析出的域名（Host） */
    private String domain;
    private JSONObject headersJson;
    /** 渠道码（resolve 后填充） */
    private String channelcode;
    /** 解析到的设备主键（device 表 PK） */
    private Integer deviceRowId;
    /** 设备 id 字符串（ecid / d / f） */
    private String deviceId;

    public C2HandlerContext(C2RecordsEntity record, String notifyEntry, String transport) {
        this.record = record;
        this.notifyEntry = notifyEntry;
        this.transport = transport;
    }

    public long getRecordId() {
        return record == null ? 0L : (record.getId() == null ? 0L : record.getId());
    }

    public String getKind() {
        return record == null ? "" : nullToEmpty(record.getKind());
    }

    public String getMethod() {
        return record == null ? "" : nullToEmpty(record.getMethod());
    }

    public String getPath() {
        return record == null ? "" : nullToEmpty(record.getPath());
    }

    public String getClientIp() {
        return record == null ? "" : nullToEmpty(record.getClientIp());
    }

    public String getXTs() {
        return record == null ? "" : nullToEmpty(record.getXTs());
    }

    public String getHeaders() {
        return record == null ? "" : nullToEmpty(record.getHeaders());
    }

    /** 解析一次 headers JSON，后续取 Host / x-ts / Content-Length 复用，避免每条消息反复 parse */
    public JSONObject getHeadersJson() {
        if (headersJson == null) {
            JSONObject parsed = com.consume.util.HeadersUtil.parse(getHeaders());
            headersJson = parsed == null ? new JSONObject() : parsed;
        }
        return headersJson;
    }

    public String getBody() {
        return record == null ? "" : nullToEmpty(record.getBody());
    }

    public String getVersion() {
        return record == null ? "" : nullToEmpty(record.getVersion());
    }

    public C2RecordsEntity getRecord() {
        return record;
    }

    public String getNotifyEntry() {
        return notifyEntry;
    }

    public String getTransport() {
        return transport;
    }

    public JSONObject getPlaintext() {
        return plaintext;
    }

    public void setPlaintext(JSONObject plaintext) {
        this.plaintext = plaintext;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public boolean isDecryptOk() {
        return decryptOk;
    }

    public void setDecryptOk(boolean decryptOk) {
        this.decryptOk = decryptOk;
    }

    public String getDecryptError() {
        return decryptError;
    }

    public void setDecryptError(String decryptError) {
        this.decryptError = decryptError;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getPlaintextJson() {
        return plaintextJson;
    }

    public void setPlaintextJson(String plaintextJson) {
        this.plaintextJson = plaintextJson;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public String getKeyLabel() {
        return keyLabel;
    }

    public void setKeyLabel(String keyLabel) {
        this.keyLabel = keyLabel;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getChannelcode() {
        return channelcode;
    }

    public void setChannelcode(String channelcode) {
        this.channelcode = channelcode;
    }

    public Integer getDeviceRowId() {
        return deviceRowId;
    }

    public void setDeviceRowId(Integer deviceRowId) {
        this.deviceRowId = deviceRowId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    /** 从解密明文中取字符串字段（不存在返回空串） */
    public String plaintextStr(String field) {
        return plaintext == null ? "" : nullToEmpty(plaintext.getString(field));
    }

    /** 从解密明文中取子对象并 toJSONString（不存在返回空串） */
    public String plaintextSubJson(String field) {
        if (plaintext == null) {
            return "";
        }
        Map<String, Object> sub = plaintext.getJSONObject(field);
        return sub == null ? "" : JSONObject.toJSONString(sub);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
