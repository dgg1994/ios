package com.ctwo.entity;


import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 设备信息实体类
 */
@Data
@TableName("device")
public class DeviceEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("deviceid")
    private String deviceId;
    
    @TableField("device_id")
    private String device_id;

    @TableField("channelcode")
    private String channelCode;

    @TableField("domain")
    private String domain;

    @TableField("ip")
    private String ip;

    @TableField("addtime")
    private Double addtime;

    @TableField("ipstatus")
    private Integer ipstatus;

    @TableField("devicestatus")
    private Integer devicestatus;

    @TableField("onlinestatus")
    private Integer onlinestatus;

    @TableField("ecid")
    private String ecid;

    @TableField("udid")
    private String udid;
    
    @TableField("serial")
    private String serial;

    @TableField("model")
    private String model;

    @TableField("bind_phase")
    private Integer bindPhase;

    @TableField("last_event_at")
    private Double lastEventAt;

    @TableField("device_name")
    private String deviceName;

    @TableField("ios_version")
    private String iosVersion;

    @TableField("hardware_model")
    private String hardwareModel;
   
    @TableField("carrier_names")
    private String carrierNames;

    @TableField("c2_series")
    private Integer c2Series;

    @TableField("applist")
    private String applist;

}
