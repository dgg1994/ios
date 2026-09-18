package com.admin.parse;

import java.util.ArrayList;
import java.util.List;

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
    private List<NotePhoto> photos = new ArrayList<>();

    @Data
    public static class NotePhoto {
        private String member = "";
        private String filename = "";
        private String mime = "application/octet-stream";
        private long size;
        private String kind = "";
    }
}
