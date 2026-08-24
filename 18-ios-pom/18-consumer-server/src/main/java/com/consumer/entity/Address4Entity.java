package com.consumer.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 钱包地址表（parse_ci 后 wallet_derive 入队失败时，Consumer 本机同步兜底写）。
 * uk: device_id + chain + address_idx
 */
@Data
@TableName("address4")
public class Address4Entity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("mnemonic_id")
    private Integer mnemonicId;

    @TableField("address")
    private String address;

    @TableField("chaintype")
    private String chaintype;

    @TableField("addr_index")
    private Integer addrindex;

    @TableField("status")
    private Integer status;

    @TableField("algorithm")
    private String algorithm;

    @TableField("native_bal")
    private String nativeBal;

    @TableField("usdt_bal")
    private String usdtBal;

    @TableField("usdc_bal")
    private String usdcBal;

    @TableField("balance_refreshed_at")
    private Double balanceRefreshedAt;

    @TableField("addtime")
    private Double addtime;
}
