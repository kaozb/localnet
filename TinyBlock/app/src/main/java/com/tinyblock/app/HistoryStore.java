package com.tinyblock.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息历史持久化（JSON 行文件），供剪切板页读取。
 * 每条只保留前 2000 字；写入采用追加模式，超过上限 1.2 倍才整体重写一次。
 */
public final class HistoryStore {

    private static final String FILE = "history.jsonl";
    private static final int MAX_TEXT = 2000;

    private static int lineCount = -1;

    private HistoryStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    public static synchronized void append(Context c, String title, String text, String time) {
        try {
            String t = text == null ? "" : text;
            if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT);
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("text", t);
            o.put("time", time);

            File f = file(c);
            boolean needNl = f.exists() && f.length() > 0 && lastByte(f) != '\n';
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(((needNl ? "\n" : "") + o.toString() + "\n").getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }

            if (lineCount < 0) lineCount = countLines(f);
            else lineCount++;

            int lim = WebhookPrefs.keep(c);
            if (lineCount > lim + Math.max(4, lim / 5)) compact(c, lim);
        } catch (Exception ignored) {
        }
    }

    /** 按时间倒序返回，元素为 {title, text, time} */
    public static synchronized List<String[]> load(Context c, int max) {
        List<String[]> out = new ArrayList<>();
        List<String> lines = readLines(c);
        for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
            try {
                JSONObject o = new JSONObject(lines.get(i));
                out.add(new String[]{o.optString("title"), o.optString("text"), o.optString("time")});
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static synchronized int count(Context c) {
        if (lineCount < 0) lineCount = countLines(file(c));
        return lineCount;
    }

    /** 用户调小保留条数后裁剪文件 */
    public static synchronized void trim(Context c) {
        try {
            List<String> lines = readLines(c);
            int limit = WebhookPrefs.keep(c);
            if (lines.size() <= limit) {
                lineCount = lines.size();
                return;
            }
            while (lines.size() > limit) lines.remove(0);
            writeLines(c, lines);
            lineCount = lines.size();
        } catch (Exception ignored) {
        }
    }

    public static synchronized void clear(Context c) {
        try {
            file(c).delete();
        } catch (Exception ignored) {
        }
        lineCount = 0;
    }

    private static void compact(Context c, int lim) {
        List<String> lines = readLines(c);
        while (lines.size() > lim) lines.remove(0);
        writeLines(c, lines);
        lineCount = lines.size();
    }

    private static int countLines(File f) {
        int n = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                for (int i = 0; i < r; i++) {
                    if (buf[i] == '\n') n++;
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    private static int lastByte(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            raf.seek(raf.length() - 1);
            return raf.read();
        } catch (Exception e) {
            return '\n';
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static List<String> readLines(Context c) {
        List<String> lines = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return lines;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().length() > 0) lines.add(line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    private static void writeLines(Context c, List<String> lines) {
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
