package com.consumer.parse;

import lombok.Data;

@Data
public class ParsedNote {
    private String identifier = "";
    private String title = "";
    private String body = "";
    private String snippet = "";
    private String folder = "";
    private String account = "";
    private String createdLabel = "";
    private String modifiedLabel = "";
    private boolean locked;
}
