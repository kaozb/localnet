package com.tinyblock.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;

/** 订阅列表的持久化与下载 */
public class Subs {

    public static class Sub {
        public String url;
        public boolean enabled;

        public Sub(String url, boolean enabled) {
            this.url = url;
            this.enabled = enabled;
        }
    }

    private final SharedPreferences prefs;
    public final ArrayList<Sub> list = new ArrayList<>();

    public Subs(Context ctx) {
        prefs = ctx.getSharedPreferences("subs", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        list.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Sub(o.getString("url"), o.getBoolean("enabled")));
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Sub s : list) {
                JSONObject o = new JSONObject();
                o.put("url", s.url);
                o.put("enabled", s.enabled);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("list", arr.toString()).apply();
    }

    public static File fileFor(Context ctx, String url) {
        return new File(ctx.getFilesDir(), "sub_" + md5(url) + ".txt");
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "h" + Integer.toHexString(s.hashCode());
        }
    }

    /** 下载订阅到本地文件，返回行数；失败抛异常 */
    public static int download(Context ctx, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TinyBlock/1.0");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

            File tmp = new File(ctx.getFilesDir(), "tmp_" + md5(url));
            int lines = 0;
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                w.write(l);
                w.write('\n');
                lines++;
            }
            w.close();
            r.close();
            if (lines < 1) {
                tmp.delete();
                throw new Exception("列表为空");
            }
            File dst = fileFor(ctx, url);
            dst.delete();
            if (!tmp.renameTo(dst)) throw new Exception("保存失败");
            return lines;
        } finally {
            conn.disconnect();
        }
    }

    /** 已缓存文件的行数，用于展示 */
    public static int cachedLines(Context ctx, String url) {
        try (BufferedReader r = new BufferedReader(new FileReader(fileFor(ctx, url)))) {
            int n = 0;
            while (r.readLine() != null) n++;
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
