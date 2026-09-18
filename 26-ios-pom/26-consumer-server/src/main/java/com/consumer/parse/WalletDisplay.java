package com.consumer.parse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class WalletDisplay {
    private String name;
    private String sourceFile;
    private String walletKey;
    private String message;
    private boolean statusOk;
    private String phrase;
    private List<ParsedNote> notes = new ArrayList<>();
    private Path archivePath;
    private List<String> archiveRoots = new ArrayList<>();
    private String accountName;
    private String passwordHint;
    private String walletInstanceId;
}
