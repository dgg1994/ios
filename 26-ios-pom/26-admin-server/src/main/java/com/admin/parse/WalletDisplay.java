package com.admin.parse;

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
    private String statusLabel;
    private boolean statusOk;
    private boolean unlockable;
    private boolean brute;
    private String phrase;
    private List<ParsedNote> notes = new ArrayList<>();
    private Path archivePath;
    private List<String> archiveRoots = new ArrayList<>();
    private String accountName;
    private String passwordHint;
    private String walletInstanceId;
}
