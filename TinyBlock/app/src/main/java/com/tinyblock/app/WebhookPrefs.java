package com.tinyblock.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/** 消息接收与提醒相关配置 */
public final class WebhookPrefs {

    public static final String KEY_NOTIFY = "notify";
    public static final String KEY_SOUND = "sound";
    public static final String KEY_VIBRATE = "vibrate";
    public static final String KEY_FLOAT = "floatMsg";
    public static final String KEY_RING_LOOP = "ringLoop";
    public static final String KEY_BLOCK = "block";       // DNS 拦截总开关
    public static final String KEY_WEBHOOK = "webhook";   // 消息接收总开关
    public static final String KEY_SMS = "smsForward";    // 短信转发总开关
    public static final String KEY_KEEP_ON = "keepOn";    // 屏幕常亮
    public static final String KEY_DARK = "darkTheme";    // 深色主题

    private static final String NAME = "webhook";

    private WebhookPrefs() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 首次启动随机分配 10000 以上端口，之后沿用 */
    public static int port(Context c) {
        int v = p(c).getInt("port", 0);
        if (v < 1 || v > 65535) {
            v = 10000 + new Random().nextInt(55536);
            p(c).edit().putInt("port", v).apply();
        }
        return v;
    }

    public static void setPort(Context c, int v) {
        p(c).edit().putInt("port", v).apply();
    }

    public static int keep(Context c) {
        int v = p(c).getInt("keep", 50);
        return v < 1 ? 1 : v;
    }

    public static void setKeep(Context c, int v) {
        p(c).edit().putInt("keep", v).apply();
    }

    public static boolean get(Context c, String key, boolean def) {
        return p(c).getBoolean(key, def);
    }

    public static void set(Context c, String key, boolean v) {
        p(c).edit().putBoolean(key, v).apply();
    }

    public static String ringtone(Context c) {
        return p(c).getString("ringtone", null);
    }

    public static void setRingtone(Context c, String uri) {
        p(c).edit().putString("ringtone", uri).apply();
    }

    /** 短信转发地址模板，可含 {content} {sender} {time} {date} 变量 */
    public static String smsUrl(Context c) {
        return p(c).getString("smsUrl", "");
    }

    public static void setSmsUrl(Context c, String v) {
        p(c).edit().putString("smsUrl", v == null ? "" : v.trim()).apply();
    }

    /** 供界面在服务未广播时先显示上次的计数 */
    public static int lastRules(Context c) {
        return p(c).getInt("lastRules", 0);
    }

    public static int lastRedirects(Context c) {
        return p(c).getInt("lastRedirects", 0);
    }

    public static void setLastCounts(Context c, int rules, int redirects) {
        p(c).edit().putInt("lastRules", rules).putInt("lastRedirects", redirects).apply();
    }
}
