package com.tinyblock.app;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 短信转发：把发件人与内容按用户配置的地址模板展开后 GET 转发。
 * 模板变量：{content} 短信内容、{sender} 发件人、{time} 秒级时间戳、{date} 格式化时间。
 */
public final class SmsForwarder {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sms-fwd");
        t.setDaemon(true);
        return t;
    });

    private SmsForwarder() {
    }

    /** 展开模板（URL 用，值做 URL 编码）；未配置返回 null */
    public static String expand(String template, String sender, String body, long time) {
        if (template == null || template.trim().isEmpty()) return null;
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(time));
        return template
                .replace("{content}", enc(body))
                .replace("{sender}", enc(sender))
                .replace("{time}", String.valueOf(time / 1000))
                .replace("{date}", enc(date));
    }

    /** 展开模板（JSON body 用，原样替换不做编码） */
    public static String expandRaw(String template, String sender, String body, long time) {
        if (template == null || template.trim().isEmpty()) return null;
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(time));
        return template
                .replace("{content}", body == null ? "" : body)
                .replace("{sender}", sender == null ? "" : sender)
                .replace("{time}", String.valueOf(time / 1000))
                .replace("{date}", date);
    }

    /** 收到短信后异步转发：POST JSON，失败静默（后台无法弹提示） */
    public static void forward(String urlTemplate, String jsonTemplate,
                               String sender, String body, long time) {
        final String url = expand(urlTemplate, sender, body, time);
        if (url == null) return;
        String tpl = (jsonTemplate == null || jsonTemplate.trim().isEmpty())
                ? "{\"sender\":\"{sender}\",\"content\":\"{content}\",\"time\":{time},\"date\":\"{date}\"}"
                : jsonTemplate;
        final String json = expandRaw(tpl, sender, body, time);
        if (json == null) return;
        POOL.execute(() -> {
            try {
                httpPostJson(url, json);
            } catch (Exception ignored) {
            }
        });
    }

    /** 同步 POST JSON，供测试按钮获取结果；返回 HTTP 状态码 */
    public static int httpPostJson(String url, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = json.getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(data.length);
        try {
            conn.getOutputStream().write(data);
            conn.getOutputStream().flush();
            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (in != null) {
                byte[] buf = new byte[1024];
                while (in.read(buf) != -1) { /* 丢弃内容，仅排空连接 */ }
                in.close();
            }
            return code;
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
