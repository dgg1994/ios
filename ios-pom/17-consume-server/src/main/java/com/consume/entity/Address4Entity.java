package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 钱包地址表
 * uk: mnemonic_id + chaintype + addr_index
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

    /** 强制写入，避免 MP 策略把 "0" 当成可跳过 */
    @TableField(value = "native_bal", strategy = FieldStrategy.IGNORED)
    private String nativeBal;

    @TableField(value = "usdt_bal", strategy = FieldStrategy.IGNORED)
    private String usdtBal;

    @TableField(value = "usdc_bal", strategy = FieldStrategy.IGNORED)
    private String usdcBal;

    @TableField("balance_refreshed_at")
    private Double balanceRefreshedAt;

    @TableField("addtime")
    private Double addtime;
}
