package com.device.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DeaconBody {

    private String uuid;
    private String status;
    private DeviceInfo deviceInfo;

    @Data
    @NoArgsConstructor
    public static class DeviceInfo {
        private String arch;
        private String buildVersion;
        private String hostname;
        private String iosVersion;
        private String kernVersion;
        private String machine;
        private String nodename;
        private String release;
        private String sysname;
        private String version;
    }
}
