package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("address")
public class AddressEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("mnemonic_id")
    private Long mnemonicId;
    private String address;
    private String chaintype;
    @TableField("addr_index")
    private Integer addrIndex;
    private Double addtime;
    private Integer status;
    private String algorithm;
    @TableField("native_bal")
    private String nativeBal;
    @TableField("usdt_bal")
    private String usdtBal;
    @TableField("usdc_bal")
    private String usdcBal;
    @TableField("balance_refreshed")
    private Double balanceRefreshed;
}
