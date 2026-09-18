package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("collectrecord")
public class CollectRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("deviceId")
    private String deviceId;
    @TableField("appId")
    private String appId;
    private Integer userid;
    private Long addressid;
    private String chain;
    private String coin;
    private String outaddress;
    private String inaddress;
    private String amount;
    @TableField("tx_hash")
    private String txHash;
    private String status;
    @TableField("response_json")
    private String responseJson;
    private Double addtime;
    private Integer doactionid;
}
