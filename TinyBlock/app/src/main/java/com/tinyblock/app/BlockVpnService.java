package com.tinyblock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量 DNS 拦截 VPN：
 * TUN 只路由常见 DNS 服务器地址（不代理其它流量），
 * 命中黑名单的域名返回 NXDOMAIN，其余请求经受保护的 socket 转发给上游 DNS。
 */
public class BlockVpnService extends VpnService {

    public static final String ACTION_START = "com.tinyblock.app.START";
    public static final String ACTION_STOP = "com.tinyblock.app.STOP";
    public static final String ACTION_RELOAD = "com.tinyblock.app.RELOAD";
    public static final String ACTION_STOP_RING = "com.tinyblock.app.STOP_RING";
    public static final String ACTION_STATUS = "com.tinyblock.app.STATUS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_RULES = "rules";
    public static final String EXTRA_REDIRECTS = "redirects";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PORT_ERROR = "portError";

    // 供 UI 在服务未广播时读取的即时状态
    public static volatile boolean stateRunning = false;
    public static volatile int stateRules = 0;
    public static volatile int stateRedirects = 0;
    public static volatile int statePort = -1;
    public static volatile String statePortError = null;

    private static final String VPN_ADDR4 = "10.111.222.1";
    private static final String FAKE_DNS4 = "10.111.222.3";
    private static final String VPN_ADDR6 = "fdfe:dcba:9876::1";
    private static final String FAKE_DNS6 = "fdfe:dcba:9876::3";

    // 一并接管系统/常见公共 DNS 地址，让写死 DNS 的应用也走拦截
    private static final String[] EXTRA_DNS4 = {
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "208.67.222.222", "208.67.220.220"
    };
    private static final String[] EXTRA_DNS6 = {
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2606:4700:4700::1111", "2606:4700:4700::1001"
    };

    // 上游 DNS：先国内可达的，再 Google
    private static final String[] UPSTREAM = {"223.5.5.5", "8.8.8.8"};

    private static final String CHANNEL = "vpn";
    private static final Object OUT_LOCK = new Object();

    private final Rules rules = new Rules();
    // 域名重定向表：小写域名 -> IP 字节（4 或 16 字节），含子域名匹配，优先级最高
    private final java.util.HashMap<String, byte[]> redirects = new java.util.HashMap<>();
    private volatile ParcelFileDescriptor tun;
    private volatile FileOutputStream out;
    private volatile Thread worker;
    private volatile ExecutorService pool;
    private volatile boolean running = false;
    /** DNS 拦截开关（设置页）：关闭时 DNS 全部直通上游 */
    private volatile boolean dnsEnabled = true;

    private volatile WebhookServer server;
    private volatile MessageCenter messages;
    private volatile Alerts alerts;
    private volatile int serverPort = -1;

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_RING.equals(action)) {
            Alerts a = alerts;
            if (a != null) a.acknowledge();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action) && !running) {
            // 服务未运行：只刷新规则缓存，不启动 VPN
            loadRules();
            broadcastStatus();
            stopSelf();
            return START_NOT_STICKY;
        }

        loadRules();
        if (running) {
            applyToggles();
            return START_STICKY;
        }

        startForegroundCompat();
        running = true;
        dnsEnabled = WebhookPrefs.get(this, WebhookPrefs.KEY_BLOCK, true);
        if (dnsEnabled) ensureTunnel();
        if (!running) return START_STICKY; // TUN 建立失败，已自行停止
        if (WebhookPrefs.get(this, WebhookPrefs.KEY_WEBHOOK, true)) startWebhook();
        refreshMissingInBackground();
        updateNotification();
        broadcastStatus();
        return START_STICKY;
    }

    // ------------------------------------------------------------------ 生命周期

    private void loadRules() {
        rules.clear();
        Subs subs = new Subs(this);
        for (Subs.Sub s : subs.list) {
            if (!s.enabled) continue;
            java.io.File f = Subs.fileFor(this, s.url);
            if (f.isFile()) rules.loadFile(f.getAbsolutePath());
        }
        redirects.clear();
        RedirectStore store = new RedirectStore(this);
        for (RedirectStore.Entry e : store.list) {
            if (!e.enabled) continue;
            byte[] ip = RedirectStore.parseIp(e.ip);
            if (ip != null) redirects.put(e.domain.toLowerCase(), ip);
        }
        WebhookPrefs.setLastCounts(this, rules.blockCount(), redirects.size());
    }

    /** 域名重定向匹配（含子域名），返回 IP 字节，未命中返回 null */
    private byte[] matchRedirect(String host) {
        String d = host;
        while (d.length() > 0) {
            byte[] ip = redirects.get(d);
            if (ip != null) return ip;
            int i = d.indexOf('.');
            if (i < 0) return null;
            d = d.substring(i + 1);
        }
        return null;
    }

    /**
     * 建立 TUN（若尚未建立）。只负责 DNS 通道；
     * HTTP 消息接收由 startWebhook 独立启动，两者可分别开关。
     */
    private void ensureTunnel() {
        if (tun != null) return;
        Builder b = new Builder()
                .addAddress(VPN_ADDR4, 32)
                .addAddress(VPN_ADDR6, 128)
                .addDnsServer(FAKE_DNS4)
                .addDnsServer(FAKE_DNS6)
                .addRoute(FAKE_DNS4, 32)
                .addRoute(FAKE_DNS6, 128)
                .setMtu(1500)
                .setSession("TinyBlock");
        try {
            for (String dns : EXTRA_DNS4) b.addRoute(dns, 32);
            for (String dns : EXTRA_DNS6) b.addRoute(dns, 128);
        } catch (Exception ignored) {
        }

        ParcelFileDescriptor fd = null;
        try {
            fd = b.establish();
        } catch (Exception ignored) {
        }
        if (fd == null) {
            // 仅拦截模式建立失败 → 整体停止；接收模式 → 放弃 TUN，保持接收
            if (server == null) {
                stopEverything();
            } else {
                dnsEnabled = false;
                updateNotification();
                broadcastStatus();
            }
            return;
        }

        tun = fd;
        pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "dns-fwd");
            t.setDaemon(true);
            return t;
        });
        worker = new Thread(this::loop, "tun-reader");
        worker.start();
    }

    /** 运行中重载：按设置开关启用 / 停用 DNS 拦截与消息接收 */
    private void applyToggles() {
        boolean block = WebhookPrefs.get(this, WebhookPrefs.KEY_BLOCK, true);
        boolean hook = WebhookPrefs.get(this, WebhookPrefs.KEY_WEBHOOK, true);
        dnsEnabled = block;
        if (block) ensureTunnel(); // 关闭时保留 TUN 但 DNS 全部直通，避免频繁建断
        if (hook) {
            reloadWebhookIfNeeded();
        } else {
            stopWebhook();
        }
        updateNotification();
        broadcastStatus();
    }

    private void stopEverything() {
        running = false;
        stopWebhook();
        ParcelFileDescriptor fd = tun;
        tun = null;
        if (fd != null) try {
            fd.close(); // 使阻塞中的 read 抛异常退出
        } catch (IOException ignored) {
        }
        ExecutorService p = pool;
        pool = null;
        if (p != null) p.shutdownNow();
        worker = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        stopSelf();
        broadcastStatus();
    }

    @Override
    public void onRevoke() {
        stopEverything();
    }

    @Override
    public void onDestroy() {
        running = false;
        stopWebhook();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 消息接收

    private void startWebhook() {
        int port = WebhookPrefs.port(this);
        if (server != null && server.isRunning() && serverPort == port && server.lastError == null) {
            return;
        }
        stopWebhook();
        if (alerts == null) alerts = new Alerts(this);
        if (messages == null) messages = new MessageCenter(this, alerts);
        serverPort = port;

        WebhookServer s = new WebhookServer(port, new WebhookServer.Listener() {
            @Override
            public void onMessage(String text, String body) {
                MessageCenter m = messages;
                if (m != null) m.onMessage(text);
            }

            @Override
            public void onStarted(int boundPort) {
                statePort = boundPort;
                statePortError = null;
                broadcastStatus();
                updateNotification();
            }

            @Override
            public void onError(String message) {
                statePort = -1;
                statePortError = message;
                broadcastStatus();
                updateNotification();
            }
        });
        server = s;
        s.start();
    }

    private void reloadWebhookIfNeeded() {
        int port = WebhookPrefs.port(this);
        WebhookServer s = server;
        if (s == null || !s.isRunning() || serverPort != port || s.lastError != null) {
            startWebhook();
        }
    }

    private void stopWebhook() {
        WebhookServer s = server;
        server = null;
        serverPort = -1;
        statePort = -1;
        statePortError = null;
        if (s != null) s.stop();

        MessageCenter m = messages;
        messages = null;
        if (m != null) m.stop();

        Alerts a = alerts;
        alerts = null;
        if (a != null) a.stopAll();
    }

    /** 服务启动时补充下载缺失的订阅缓存 */
    private void refreshMissingInBackground() {
        new Thread(() -> {
            Subs subs = new Subs(this);
            boolean changed = false;
            for (Subs.Sub s : subs.list) {
                if (!s.enabled) continue;
                java.io.File f = Subs.fileFor(this, s.url);
                if (f.isFile() && f.length() > 0) continue;
                try {
                    Subs.download(this, s.url);
                    changed = true;
                } catch (Exception ignored) {
                }
            }
            if (changed) {
                loadRules();
                updateNotification();
                broadcastStatus();
            }
        }, "sub-refresh").start();
    }

    // ------------------------------------------------------------------ TUN 读取

    private void loop() {
        ParcelFileDescriptor fd = tun;
        if (fd == null) return;
        try (FileInputStream in = new FileInputStream(fd.getFileDescriptor());
             FileOutputStream o = new FileOutputStream(fd.getFileDescriptor())) {
            out = o;
            byte[] pkt = new byte[65536];
            while (running) {
                int n = in.read(pkt);
                if (n > 0) handlePacket(pkt, n);
            }
        } catch (Exception ignored) {
        } finally {
            out = null;
        }
    }

    private void handlePacket(byte[] pkt, int n) {
        int ver = (pkt[0] >> 4) & 0xF;
        if (ver == 4) {
            int ihl = (pkt[0] & 0xF) * 4;
            if (ihl < 20 || n < ihl + 8 || pkt[9] != 17) return; // 仅处理 UDP
            handleUdp(pkt, ihl, n, readAddr(pkt, 12, 4), readAddr(pkt, 16, 4), true);
        } else if (ver == 6) {
            if (n < 48 || pkt[6] != 17) return; // 不处理 IPv6 扩展头
            handleUdp(pkt, 40, n, readAddr(pkt, 8, 16), readAddr(pkt, 24, 16), false);
        }
    }

    private void handleUdp(byte[] pkt, int off, int n, byte[] src, byte[] dst, boolean v4) {
        int dport = u16(pkt, off + 2);
        int ulen = u16(pkt, off + 4);
        if (dport != 53) return;
        int dnsLen = ulen - 8;
        if (dnsLen < 12 || off + 8 + dnsLen > n) return;

        byte[] dns = new byte[dnsLen];
        System.arraycopy(pkt, off + 8, dns, 0, dnsLen);

        int sport = u16(pkt, off);
        // DNS 拦截已关闭：全部直通上游
        if (!dnsEnabled) {
            forward(dns, v4, dst, src, sport);
            return;
        }
        int opcode = (dns[2] >> 3) & 0xF;
        StringBuilder name = new StringBuilder();
        int qend = parseQuestion(dns, name);
        if (qend < 0 || opcode != 0) {
            forward(dns, v4, dst, src, sport);
            return;
        }
        // 1) 重定向优先：直接返回用户设置的 IP
        byte[] rip = matchRedirect(name.toString());
        if (rip != null) {
            int qtype = u16(dns, qend - 4);
            writeReply(v4, dst, src, sport, redirectAnswer(dns, qend, qtype, rip));
            return;
        }
        // 2) 黑名单拦截（白名单在 match 内优先）
        if (rules.match(name.toString()) == 1) {
            writeReply(v4, dst, src, sport, nxdomain(dns, qend));
        } else {
            ExecutorService p = pool;
            if (p != null) p.execute(() -> forward(dns, v4, dst, src, sport));
        }
    }

    // ------------------------------------------------------------------ DNS 处理

    /** 解析问题区，返回问题区结束下标（-1 为格式错误），域名写入 name（小写） */
    private static int parseQuestion(byte[] dns, StringBuilder name) {
        if (dns.length < 12) return -1;
        int qd = u16(dns, 4);
        if (qd < 1) return -1;
        int i = 12;
        int len = dns.length;
        boolean first = true;
        while (i < len) {
            int b = dns[i] & 0xFF;
            if (b == 0) {
                i++;
                break;
            }
            if ((b & 0xC0) == 0xC0) { // 指针（查询中不应出现）
                i += 2;
                break;
            }
            i++;
            if (i + b > len) return -1;
            if (!first) name.append('.');
            first = false;
            for (int j = 0; j < b; j++) {
                char ch = (char) (dns[i + j] & 0xFF);
                if (ch >= 'A' && ch <= 'Z') ch += 32;
                name.append(ch);
            }
            i += b;
        }
        if (i + 4 > len) return -1;
        return i + 4; // 跳过 QTYPE + QCLASS
    }

    /** 命中黑名单：返回 NXDOMAIN 应答 */
    private static byte[] nxdomain(byte[] q, int qend) {
        byte[] r = new byte[qend];
        System.arraycopy(q, 0, r, 0, qend);
        r[2] = (byte) (0x80 | (q[2] & 0x01)); // QR=1，保留 RD
        r[3] = (byte) 0x83;                    // RA=1, RCODE=3 (NXDOMAIN)
        r[6] = r[7] = 0;                       // ANCOUNT
        r[8] = r[9] = 0;                       // NSCOUNT
        r[10] = r[11] = 0;                     // ARCOUNT
        return r;
    }

    /**
     * 命中重定向：返回携带自定义 A / AAAA 记录的应答。
     * 查询类型与 IP 版本不匹配时返回空应答（NOERROR，0 条记录）。
     */
    private static byte[] redirectAnswer(byte[] q, int qend, int qtype, byte[] ip) {
        boolean v4ip = ip.length == 4;
        boolean match = (qtype == 1 && v4ip) || (qtype == 28 && !v4ip);
        int rdlen = v4ip ? 4 : 16;
        int anlen = match ? (2 + 10 + rdlen) : 0; // 名字指针2 + 固定段10 + RDATA
        byte[] r = new byte[qend + anlen];
        System.arraycopy(q, 0, r, 0, qend);
        r[2] = (byte) (0x80 | (q[2] & 0x01)); // QR=1，保留 RD
        r[3] = (byte) 0x80;                    // RA=1, RCODE=0
        if (match) {
            r[6] = 0;
            r[7] = 1;                          // ANCOUNT=1
            int o = qend;
            r[o++] = (byte) 0xC0;              // 指向问题区偏移 12 的域名
            r[o++] = (byte) 0x0C;
            r[o++] = (byte) (qtype >> 8);
            r[o++] = (byte) qtype;
            r[o++] = 0;
            r[o++] = 1;                        // CLASS=IN
            r[o++] = 0;
            r[o++] = 0;
            r[o++] = 0;
            r[o++] = 60;                       // TTL=60s
            r[o++] = (byte) (rdlen >> 8);
            r[o++] = (byte) rdlen;
            System.arraycopy(ip, 0, r, o, rdlen);
        }
        return r;
    }

    /** 超大应答截断为 TC=1 的头部应答，促使客户端改走 TCP（罕见） */
    private static byte[] cap(byte[] resp) {
        if (resp.length <= 1400) return resp;
        byte[] t = new byte[12];
        System.arraycopy(resp, 0, t, 0, 12);
        t[2] |= 0x02;
        t[6] = t[7] = 0;
        return t;
    }

    /** 转发到上游 DNS（socket 已 protect，不会回流进 TUN） */
    private void forward(byte[] query, boolean v4, byte[] srvAddr, byte[] cliAddr, int cliPort) {
        for (String up : UPSTREAM) {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                protect(sock);
                sock.setSoTimeout(4000);
                sock.send(new DatagramPacket(query, query.length, InetAddress.getByName(up), 53));
                byte[] buf = new byte[4096];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                sock.receive(rp);
                int len = rp.getLength();
                if (len < 12) continue;
                byte[] resp = new byte[len];
                System.arraycopy(buf, 0, resp, 0, len);
                writeReply(v4, srvAddr, cliAddr, cliPort, cap(resp));
                return;
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
            }
        }
    }

    private void writeReply(boolean v4, byte[] src, byte[] dst, int dport, byte[] dns) {
        FileOutputStream o = out;
        if (o == null) return;
        try {
            byte[] pkt = buildPacket(v4, src, dst, 53, dport, dns);
            synchronized (OUT_LOCK) {
                o.write(pkt);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ 组包

    private static byte[] buildPacket(boolean v4, byte[] src, byte[] dst, int sport, int dport, byte[] dns) {
        if (v4) {
            int total = 20 + 8 + dns.length;
            byte[] p = new byte[total];
            p[0] = 0x45;
            p[2] = (byte) (total >> 8);
            p[3] = (byte) total;
            int id = (int) (System.nanoTime() & 0xFFFF);
            p[4] = (byte) (id >> 8);
            p[5] = (byte) id;
            p[6] = 0x40; // DF
            p[8] = 64;
            p[9] = 17;
            System.arraycopy(src, 0, p, 12, 4);
            System.arraycopy(dst, 0, p, 16, 4);
            int c = checksum(p, 0, 20);
            p[10] = (byte) (c >> 8);
            p[11] = (byte) c;
            putUdp(p, 20, sport, dport, dns, src, dst, false);
            return p;
        } else {
            int total = 40 + 8 + dns.length;
            byte[] p = new byte[total];
            p[0] = 0x60;
            int pl = 8 + dns.length;
            p[4] = (byte) (pl >> 8);
            p[5] = (byte) pl;
            p[6] = 17;
            p[7] = 64;
            System.arraycopy(src, 0, p, 8, 16);
            System.arraycopy(dst, 0, p, 24, 16);
            putUdp(p, 40, sport, dport, dns, src, dst, true);
            return p;
        }
    }

    private static void putUdp(byte[] p, int off, int sport, int dport, byte[] dns,
                               byte[] src, byte[] dst, boolean v6) {
        int ulen = 8 + dns.length;
        p[off] = (byte) (sport >> 8);
        p[off + 1] = (byte) sport;
        p[off + 2] = (byte) (dport >> 8);
        p[off + 3] = (byte) dport;
        p[off + 4] = (byte) (ulen >> 8);
        p[off + 5] = (byte) ulen;
        System.arraycopy(dns, 0, p, off + 8, dns.length);
        int c = udpChecksum(p, off, ulen, src, dst, v6);
        if (c == 0 && v6) c = 0xFFFF; // IPv6 不允许 UDP 校验和为 0
        p[off + 6] = (byte) (c >> 8);
        p[off + 7] = (byte) c;
    }

    private static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = 0; i < len - 1; i += 2) sum += u16(b, off + i);
        if ((len & 1) != 0) sum += (b[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }

    private static int udpChecksum(byte[] p, int off, int ulen, byte[] src, byte[] dst, boolean v6) {
        int sum = 0;
        if (!v6) {
            sum += u16(src, 0) + u16(src, 2) + u16(dst, 0) + u16(dst, 2);
            sum += 17 + ulen;
        } else {
            for (int i = 0; i < 16; i += 2) sum += u16(src, i);
            for (int i = 0; i < 16; i += 2) sum += u16(dst, i);
            sum += ulen + 17;
        }
        for (int i = 0; i < ulen - 1; i += 2) sum += u16(p, off + i);
        if ((ulen & 1) != 0) sum += (p[off + ulen - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static byte[] readAddr(byte[] p, int off, int len) {
        byte[] a = new byte[len];
        System.arraycopy(p, off, a, 0, len);
        return a;
    }

    // ------------------------------------------------------------------ 状态与通知

    private void broadcastStatus() {
        stateRunning = running;
        stateRules = rules.blockCount();
        stateRedirects = redirects.size();
        WebhookServer s = server;
        statePort = s != null && s.isRunning() ? s.listenPort : -1;
        statePortError = s == null ? null : s.lastError;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING, running);
        i.putExtra(EXTRA_RULES, stateRules);
        i.putExtra(EXTRA_REDIRECTS, stateRedirects);
        i.putExtra(EXTRA_PORT, statePort);
        i.putExtra(EXTRA_PORT_ERROR, statePortError);
        sendBroadcast(i);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "拦截服务",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle("TinyBlock")
                .setContentText(notificationText())
                .setContentIntent(pi)
                .setOngoing(true);
        return nb.build();
    }

    private String notificationText() {
        boolean block = WebhookPrefs.get(this, WebhookPrefs.KEY_BLOCK, true);
        boolean hook = WebhookPrefs.get(this, WebhookPrefs.KEY_WEBHOOK, true);
        StringBuilder sb = new StringBuilder();
        if (block) {
            sb.append("拦截 ").append(rules.blockCount()).append(" 条")
              .append(" · 重定向 ").append(redirects.size()).append(" 条");
        }
        if (hook) {
            if (sb.length() > 0) sb.append(" · ");
            if (statePort > 0) sb.append("端口 ").append(statePort);
            else if (statePortError != null) sb.append("端口未监听");
            else sb.append("接收中");
        }
        if (sb.length() == 0) sb.append("运行中");
        return sb.toString();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(1, n);
        }
    }
}
