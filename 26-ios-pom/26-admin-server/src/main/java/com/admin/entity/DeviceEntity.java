package com.admin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("devices")
public class DeviceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("deviceId")
    private String deviceId;
    @TableField("hardwareModel")
    private String hardwareModel;
    @TableField("iosVersion")
    private String iosVersion;
    @TableField("deviceName")
    private String deviceName;
    private String model;
    @TableField("appName")
    private String appName;
    @TableField("bundleId")
    private String bundleId;
    private Integer finished;
    @TableField("finishedAt")
    private Date finishedAt;
    @TableField("created_at")
    private Date createdAt;
    @TableField("updated_at")
    private Date updatedAt;
    @TableField("appId")
    private String appId;
    private String ip;
    @TableField("wallet_usdt_max")
    private Double walletUsdtMax;
}
