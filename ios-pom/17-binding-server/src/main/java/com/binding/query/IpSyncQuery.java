package com.binding.query;

import lombok.Data;

@Data
public class IpSyncQuery {

    private String channelCode;

    private String ip;

    private String deviceVersion;

    private String domain;
}
