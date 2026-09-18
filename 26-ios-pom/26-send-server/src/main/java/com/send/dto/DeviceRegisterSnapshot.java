package com.send.dto;

import lombok.Data;

/** 注册热路径快照：校验后交给异步 Writer 入库。 */
@Data
public class DeviceRegisterSnapshot {

    private String deviceId;
    private String hardwareModel;
    private String iosVersion;
    private String deviceName;
    private String model;
    private String appName;
    private String bundleId;
    private String appId;
    private String clientIp;
    /** v1 / v2 */
    private String interversion;
    /** V2 注册会重置 finished */
    private boolean resetFinished;
    private boolean dumpRequestJson;
}
