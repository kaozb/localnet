package com.tinyblock.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;

/** 域名重定向规则（自定义 DNS 记录）：域名 -> 固定 IP，优先级最高 */
public class RedirectStore {

    public static class Entry {
        public String domain;
        public String ip;
        public boolean enabled;

        public Entry(String domain, String ip, boolean enabled) {
            this.domain = domain;
            this.ip = ip;
            this.enabled = enabled;
        }
    }

    private final SharedPreferences prefs;
    public final ArrayList<Entry> list = new ArrayList<>();

    public RedirectStore(Context ctx) {
        prefs = ctx.getSharedPreferences("redirects", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        list.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Entry(o.getString("domain"), o.getString("ip"), o.getBoolean("enabled")));
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("domain", e.domain);
                o.put("ip", e.ip);
                o.put("enabled", e.enabled);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("list", arr.toString()).apply();
    }

    /** 校验并解析 IP 字面量：返回 4 字节（IPv4）或 16 字节（IPv6），非法返回 null */
    public static byte[] parseIp(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.contains(":")) {
                // IPv6：仅允许十六进制字符、冒号及内嵌 IPv4 的点
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                    if (!hex && c != ':' && c != '.') return null;
                }
            } else {
                String[] parts = s.split("\\.");
                if (parts.length != 4) return null;
                for (String p : parts) {
                    int v = Integer.parseInt(p);
                    if (v < 0 || v > 255) return null;
                }
            }
            byte[] addr = InetAddress.getByName(s).getAddress();
            return (addr.length == 4 || addr.length == 16) ? addr : null;
        } catch (Exception e) {
            return null;
        }
    }
}
