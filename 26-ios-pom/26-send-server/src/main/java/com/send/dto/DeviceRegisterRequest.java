package com.send.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import lombok.Data;

@Data
public class DeviceRegisterRequest {

    @NotBlank
    @Size(min = 1, max = 64)
    private String deviceId;

    @Size(max = 64)
    private String hardwareModel;

    @Size(max = 32)
    private String iosVersion;

    @Size(max = 128)
    private String deviceName;

    @Size(max = 64)
    private String model;

    @Size(max = 64)
    private String appName;

    @Size(max = 128)
    private String bundleId;

    @Size(max = 64)
    private String appId;
}
