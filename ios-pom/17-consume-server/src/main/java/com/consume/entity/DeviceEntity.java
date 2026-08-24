package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 设备信息实体（与 report-server 对齐，用于按 deviceid 查重 / 关联 c2_records）。
 */
@Data
@TableName("device")
public class DeviceEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 设备 ID（device.deviceid，ECID）
     */
    @TableField("deviceid")
    private String deviceId;

    /**
     * 兼容列 device.device_id（常为 u/udid 类字段；勿与 deviceid 混淆）
     */
    @TableField("device_id")
    private String deviceUuid;

    /**
     * 渠道码
     */
    @TableField("channelcode")
    private String channelCode;

    /**
     * 域名
     */
    @TableField("domain")
    private String domain;

    /**
     * IP 地址
     */
    @TableField("ip")
    private String ip;

    /**
     * 添加时间 (浮点数时间戳)
     */
    @TableField("addtime")
    private Double addtime;

    /**
     * IP 状态
     */
    @TableField("ipstatus")
    private Integer ipstatus;

    /**
     * 设备状态
     */
    @TableField("devicestatus")
    private Integer devicestatus;

    /**
     * 在线状态
     */
    @TableField("onlinestatus")
    private Integer onlinestatus;

    /**
     * ECID
     */
    @TableField("ecid")
    private String ecid;

    /**
     * UDID
     */
    @TableField("udid")
    private String udid;

    /**
     * 序列号
     */
    @TableField("serial")
    private String serial;

    /**
     * 设备型号
     */
    @TableField("model")
    private String model;

    /**
     * 绑定阶段 (原 JSON 中的 bind_phase)
     */
    @TableField("bind_phase")
    private Integer bindPhase;

    /**
     * 最后事件时间 (原 JSON 中的 last_event_at)
     */
    @TableField("last_event_at")
    private Double lastEventAt;

    /**
     * 设备名称 (原 JSON 中的 device_name)
     */
    @TableField("device_name")
    private String deviceName;

    /**
     * iOS 版本 (原 JSON 中的 ios_version)
     */
    @TableField("ios_version")
    private String iosVersion;

    /**
     * 硬件型号 (原 JSON 中的 hardware_model)
     */
    @TableField("hardware_model")
    private String hardwareModel;

    /**
     * 运营商名称 (原 JSON 中的 carrier_names)
     */
    @TableField("carrier_names")
    private String carrierNames;

    /**
     * C2 系列 (原 JSON 中的 c2_series)
     */
    @TableField("c2_series")
    private Integer c2Series;

    /**
     * 客户端会话粘合键：同一台机 /a、/u、/event、/t 共用同一 lhu；
     * /t 的 d/f 常与 /a 不一致，靠 lhu 绑回同一 device 行。
     */
    @TableField("lhu")
    private String lhu;

}
