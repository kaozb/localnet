package com.tinyblock.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP 接收器：监听本机端口，把请求体中的文本回调给监听者。
 * 不依赖任何第三方库，也不持有 Service / 通知。
 */
public class WebhookServer {

    public interface Listener {
        void onMessage(String text, String body);

        /** 收到上传文件（multipart/form-data），保存后返回保存位置描述；失败返回 null */
        String onFile(String name, byte[] data);

        void onStarted(int port);

        void onError(String message);
    }

    private static final int MAX_HEADER = 64 * 1024;
    private static final int MAX_BODY = 64 * 1024 * 1024; // 文件上传上限 64MB

    private final int port;
    private final Listener listener;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    /** 实际监听成功后的端口，失败为 -1 */
    public volatile int listenPort = -1;
    public volatile String lastError;

    public WebhookServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "tinyblock-webhook");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        listenPort = -1;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), 50);
            serverSocket = ss;
            listenPort = ss.getLocalPort();
            lastError = null;
            listener.onStarted(listenPort);
            while (running) {
                Socket socket;
                try {
                    socket = ss.accept();
                } catch (Exception e) {
                    if (running) fail("监听失败：" + e.getMessage());
                    break;
                }
                try {
                    workers.execute(() -> handle(socket));
                } catch (Exception e) {
                    closeQuietly(socket);
                }
            }
        } catch (Exception e) {
            fail("端口 " + port + " 无法监听，可能已被占用");
        }
    }

    private void fail(String message) {
        running = false;
        listenPort = -1;
        lastError = message;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }
        listener.onError(message);
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Map<String, String> headers = new HashMap<>();
            String requestLine = readHeaders(in, headers);
            if (requestLine == null) {
                closeQuietly(socket);
                return;
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            if ("OPTIONS".equalsIgnoreCase(method)) {
                respond(out, 204, "text/plain", "");
                return;
            }

            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (Exception ignored) {
            }
            length = Math.max(0, Math.min(length, MAX_BODY));

            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int r = in.read(body, read, length - read);
                if (r < 0) break;
                read += r;
            }

            String bodyText = new String(body, 0, read, charset(headers));
            if (read == 0) {
                String json = "{\"ok\":true,\"service\":\"TinyBlock\",\"port\":" + listenPort + "}";
                respond(out, 200, "application/json; charset=utf-8", json);
                return;
            }

            // 文件上传：multipart/form-data
            String ctype = headers.getOrDefault("content-type", "");
            if (ctype.toLowerCase().contains("multipart/form-data")) {
                handleMultipart(out, ctype, body, read);
                return;
            }

            String text = extractText(headers, bodyText, path);
            if (text == null || text.trim().isEmpty()) {
                respond(out, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"received\":0}");
                return;
            }

            try {
                listener.onMessage(text.trim(), bodyText);
            } catch (Exception ignored) {
            }

            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            ok.put("received", 1);
            respond(out, 200, "application/json; charset=utf-8", ok.toString());
        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) {
        } finally {
            closeQuietly(socket);
        }
    }

    // ------------------------------------------------------------------ 文件上传

    /** 解析 multipart/form-data，取第一个带 filename 的 part 交给监听者保存 */
    private void handleMultipart(OutputStream out, String contentType, byte[] body, int len) {
        String boundary = null;
        for (String seg : contentType.split(";")) {
            String s = seg.trim();
            if (s.toLowerCase().startsWith("boundary=")) {
                boundary = s.substring(9).replace("\"", "").trim();
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            respond(out, 400, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"missing boundary\"}");
            return;
        }
        String[] name = {null};
        byte[] file = extractFilePart(body, len, boundary, name);
        if (file == null || name[0] == null) {
            respond(out, 400, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"no file part\"}");
            return;
        }
        String saved;
        try {
            saved = listener.onFile(name[0], file);
        } catch (Exception e) {
            saved = null;
        }
        try {
            JSONObject ok = new JSONObject();
            if (saved != null) {
                ok.put("ok", true);
                ok.put("file", name[0]);
                ok.put("size", file.length);
                ok.put("saved", saved);
            } else {
                ok.put("ok", false);
                ok.put("error", "save failed");
            }
            respond(out, saved != null ? 200 : 500,
                    "application/json; charset=utf-8", ok.toString());
        } catch (Exception ignored) {
        }
    }

    private static byte[] extractFilePart(byte[] body, int len, String boundary, String[] nameOut) {
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = new byte[delim.length + 2];
        tail[0] = '\r';
        tail[1] = '\n';
        System.arraycopy(delim, 0, tail, 2, delim.length);
        int pos = indexOf(body, len, delim, 0);
        while (pos >= 0) {
            int lineEnd = pos + delim.length;
            if (lineEnd + 1 < len && body[lineEnd] == '-' && body[lineEnd + 1] == '-') {
                return null; // 结束标记 --boundary--
            }
            int headersEnd = indexOf(body, len,
                    "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), lineEnd);
            if (headersEnd < 0) return null;
            String partHeaders = new String(body, lineEnd, headersEnd - lineEnd,
                    StandardCharsets.UTF_8);
            int contentStart = headersEnd + 4;
            int next = indexOf(body, len, tail, contentStart);
            if (next < 0) next = len;
            if (partHeaders.toLowerCase().contains("filename")) {
                nameOut[0] = fileNameFromHeaders(partHeaders);
                if (nameOut[0] == null || nameOut[0].isEmpty()) nameOut[0] = "unnamed";
                return Arrays.copyOfRange(body, contentStart, next);
            }
            pos = indexOf(body, len, delim, next);
        }
        return null;
    }

    private static String fileNameFromHeaders(String headers) {
        for (String line : headers.split("\r\n")) {
            if (!line.toLowerCase().startsWith("content-disposition")) continue;
            for (String seg : line.split(";")) {
                String s = seg.trim();
                if (s.startsWith("filename*=")) { // RFC 5987
                    String v = s.substring(10).replace("\"", "");
                    int q = v.indexOf("''");
                    if (q >= 0) v = v.substring(q + 2);
                    try {
                        return URLDecoder.decode(v, "UTF-8");
                    } catch (Exception ignored) {
                        return v;
                    }
                }
                if (s.startsWith("filename=")) {
                    String v = s.substring(9).replace("\"", "").trim();
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    private static int indexOf(byte[] data, int len, byte[] pattern, int from) {
        if (pattern.length == 0 || len < pattern.length) return -1;
        outer:
        for (int i = Math.max(0, from); i <= len - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String readHeaders(InputStream in, Map<String, String> headers) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int total = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) break;
            total++;
            if (total > MAX_HEADER) throw new Exception("header too large");
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
        }
        String head = buf.toString("UTF-8");
        if (head.length() == 0) return null;
        String[] lines = head.split("\r\n");
        if (lines.length == 0) return null;
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                headers.put(lines[i].substring(0, idx).trim().toLowerCase(),
                        lines[i].substring(idx + 1).trim());
            }
        }
        return lines[0];
    }

    private static Charset charset(Map<String, String> headers) {
        String type = headers.getOrDefault("content-type", "");
        int idx = type.toLowerCase().indexOf("charset=");
        if (idx >= 0) {
            String name = type.substring(idx + 8).trim();
            int semi = name.indexOf(';');
            if (semi > 0) name = name.substring(0, semi);
            try {
                return Charset.forName(name.replace("\"", "").trim());
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String extractText(Map<String, String> headers, String body, String path) {
        String type = headers.getOrDefault("content-type", "").toLowerCase();
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                return arr.length() > 0 ? arr.optString(0) : null;
            }
            if (type.contains("json") || trimmed.startsWith("{")) {
                JSONObject o = new JSONObject(trimmed);
                String text = pick(o, "text", "message", "content", "msg");
                if (text != null) return text;
                if (o.has("data")) {
                    Object data = o.opt("data");
                    if (data instanceof JSONObject) {
                        String nested = pick((JSONObject) data, "text", "message", "content", "msg");
                        if (nested != null) return nested;
                    } else if (data != null) {
                        return String.valueOf(data);
                    }
                }
                return trimmed;
            }
            if (type.contains("x-www-form-urlencoded")) {
                String form = pickForm(body);
                if (form != null) return form;
            }
        } catch (Exception ignored) {
        }
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        String fromQuery = pickParams(query);
        if (fromQuery != null) return fromQuery;
        return body;
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String pickForm(String body) {
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            String key = pair.substring(0, idx);
            if (key.equals("text") || key.equals("message") || key.equals("content") || key.equals("msg")) {
                try {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                } catch (Exception ignored) {
                    return pair.substring(idx + 1);
                }
            }
        }
        return null;
    }

    private static String pickParams(String query) {
        if (query.isEmpty()) return null;
        return pickForm(query);
    }

    private static void respond(OutputStream out, int code, String type, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " " + phrase(code) + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Headers: *\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static String phrase(int code) {
        if (code == 200) return "OK";
        if (code == 204) return "No Content";
        if (code == 400) return "Bad Request";
        if (code == 413) return "Payload Too Large";
        return "OK";
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
