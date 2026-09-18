package com.admin.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public final class TimeLabels {

    private TimeLabels() {
    }

    public static String isoZ(Date dt) {
        if (dt == null) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(dt);
    }

    public static String beijing(Date dt) {
        if (dt == null) {
            return "—";
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return f.format(dt);
    }

    public static String beijingUnix(Double ts) {
        if (ts == null || ts <= 0) {
            return "—";
        }
        return beijing(new Date((long) (ts * 1000)));
    }

    public static String relativeBeijing(Date dt) {
        if (dt == null) {
            return "—";
        }
        long diff = System.currentTimeMillis() - dt.getTime();
        if (diff < 0) {
            diff = 0;
        }
        long sec = diff / 1000;
        if (sec < 60) {
            return "刚刚";
        }
        if (sec < 3600) {
            return (sec / 60) + " 分钟前";
        }
        if (sec < 86400) {
            return (sec / 3600) + " 小时前";
        }
        return (sec / 86400) + " 天前";
    }
}
