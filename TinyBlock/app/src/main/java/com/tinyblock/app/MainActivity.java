package com.tinyblock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {

    // ---- 蓝色主题配色 ----
    private static final int BLUE = 0xFF1A73E8;
    private static final int BLUE_DEEP = 0xFF0B3FA8;
    private static final int BG = 0xFFEEF3FB;
    private static final int CARD = 0xFFFFFFFF;
    private static final int ITEM_BG = 0xFFF5F8FD;
    private static final int INPUT_BG = 0xFFF2F5FB;
    private static final int TEXT_MAIN = 0xFF17233B;
    private static final int TEXT_SUB = 0xFF7A8699;
    private static final int RED = 0xFFD93025;

    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_RING = 3;

    private Subs subs;
    private RedirectStore redirects;

    private Button mainBtn;
    private TextView tabMessages;
    private TextView tabBlock;

    private SwipePager pager;
    private int currentPage = 1;

    private EditText portInput;
    private TextView addressView;
    private TextView portHint;
    private Switch swNotify;
    private Switch swSound;
    private Switch swVibrate;
    private Switch swFloat;
    private Switch swRing;
    private TextView ringValue;
    private TextView clipSummary;
    private TextView notifValue;
    private TextView overlayValue;

    private LinearLayout subListBox;
    private LinearLayout redirectListBox;
    private EditText subInput;
    private EditText redDomainInput;
    private EditText redIpInput;

    private boolean serviceRunning;
    private boolean pendingStart;
    private Ringtone previewTone;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            serviceRunning = i.getBooleanExtra(BlockVpnService.EXTRA_RUNNING, false);
            BlockVpnService.statePort = i.getIntExtra(BlockVpnService.EXTRA_PORT, -1);
            BlockVpnService.statePortError = i.getStringExtra(BlockVpnService.EXTRA_PORT_ERROR);
            refreshHeader();
            refreshMessageState();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashGuard();
        File cf = crashFile();
        if (cf.isFile()) {
            showCrashReport(cf);
            return;
        }
        subs = new Subs(this);
        redirects = new RedirectStore(this);
        buildUi();

        serviceRunning = BlockVpnService.stateRunning;
        BlockVpnService.statePortError = null;

        refreshHeader();
        refreshMessageState();
        refreshClipSummary();
        refreshRingValue();
        refreshPermissions();

        IntentFilter f = new IntentFilter(BlockVpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMessageState();
        refreshClipSummary();
        refreshPermissions();
        refreshRingValue();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (previewTone != null) {
            try {
                previewTone.stop();
            } catch (Exception ignored) {
            }
            previewTone = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (pager != null && pager.getCurrentItem() == 0) {
            pager.setCurrentItem(1, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 界面搭建

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        pager = new SwipePager(this);
        root.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE1E8F2);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        root.addView(buildBottomBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        pager.setPages(buildMessagePage(), buildBlockPage());
        pager.setOnPageChangeListener(index -> {
            currentPage = index;
            refreshTabs();
            if (index == 0) {
                refreshMessageState();
                refreshClipSummary();
            }
        });
        pager.setCurrentItem(1, false);

        rebuildSubRows();
        rebuildRedirectRows();
    }

    /** 底部导航栏：消息 | 圆形启动按钮 | 拦截 */
    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(6), dp(8), dp(6), dp(8));

        tabMessages = new TextView(this);
        tabMessages.setText("消息");
        tabMessages.setTextSize(14);
        tabMessages.setTypeface(null, Typeface.BOLD);
        tabMessages.setGravity(Gravity.CENTER);
        tabMessages.setOnClickListener(v -> pager.setCurrentItem(0, true));
        bar.addView(tabMessages, new LinearLayout.LayoutParams(0, -1, 1f));

        mainBtn = new Button(this);
        mainBtn.setText("启动");
        mainBtn.setTextSize(13);
        mainBtn.setTypeface(null, Typeface.BOLD);
        mainBtn.setTextColor(0xFFFFFFFF);
        mainBtn.setAllCaps(false);
        mainBtn.setElevation(dp(6));
        // 清空系统/厂商主题遗留的背景着色，否则自定义背景会被整体染成主题色
        mainBtn.setBackgroundTintList(null);
        mainBtn.setOnClickListener(v -> toggleVpn());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(54), dp(54));
        blp.leftMargin = dp(12);
        blp.rightMargin = dp(12);
        bar.addView(mainBtn, blp);

        tabBlock = new TextView(this);
        tabBlock.setText("拦截");
        tabBlock.setTextSize(14);
        tabBlock.setTypeface(null, Typeface.BOLD);
        tabBlock.setGravity(Gravity.CENTER);
        tabBlock.setOnClickListener(v -> pager.setCurrentItem(1, true));
        bar.addView(tabBlock, new LinearLayout.LayoutParams(0, -1, 1f));
        return bar;
    }

    private ScrollView buildMessagePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(24));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== 消息接收 =====
        LinearLayout c1 = card();
        c1.addView(sectionTitle("消息接收"));
        c1.addView(note("启动后同时开启 DNS 拦截与 HTTP 接收。同一局域网内，向下方地址 POST 消息即可。\n"
                + "支持 JSON {\"text\":\"...\"}、表单 text=... 或任意纯文本。"));

        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        portRow.setPadding(0, dp(4), 0, 0);

        portInput = new EditText(this);
        portInput.setHint("监听端口");
        portInput.setTextSize(13);
        portInput.setTextColor(TEXT_MAIN);
        portInput.setHintTextColor(0xFFA9B4C6);
        portInput.setBackground(roundedBox(INPUT_BG, 10));
        portInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        portInput.setSingleLine(true);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portRow.addView(portInput, new LinearLayout.LayoutParams(0, -2, 1f));

        Button apply = filledBtn("应用");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, dp(40));
        alp.leftMargin = dp(8);
        portRow.addView(apply, alp);
        apply.setOnClickListener(v -> applyPort());
        c1.addView(portRow);

        addressView = new TextView(this);
        addressView.setTextSize(13);
        addressView.setPadding(0, dp(8), 0, dp(2));
        addressView.setOnClickListener(v -> copyText(addressView.getText().toString()));
        c1.addView(addressView);

        portHint = new TextView(this);
        portHint.setTextSize(11);
        portHint.setTextColor(RED);
        portHint.setPadding(0, dp(2), 0, 0);
        portHint.setVisibility(View.GONE);
        c1.addView(portHint);
        content.addView(c1);

        // ===== 提醒 =====
        LinearLayout c2 = card();
        c2.addView(sectionTitle("提醒"));
        c2.addView(note("可多选。持续响铃最长 60 秒，点通知或悬浮条上的「确认」即可停止。"));

        swNotify = new Switch(this);
        swNotify.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_NOTIFY, true));
        swNotify.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_NOTIFY, checked));

        swSound = new Switch(this);
        swSound.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_SOUND, true));
        swSound.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_SOUND, checked));

        swVibrate = new Switch(this);
        swVibrate.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_VIBRATE, true));
        swVibrate.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_VIBRATE, checked));

        swFloat = new Switch(this);
        swFloat.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_FLOAT, false));
        swFloat.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !Settings.canDrawOverlays(this)) {
                WebhookPrefs.set(this, WebhookPrefs.KEY_FLOAT, false);
                b.setChecked(false);
                toast("请先授予悬浮窗权限");
                openOverlaySettings();
                return;
            }
            WebhookPrefs.set(this, WebhookPrefs.KEY_FLOAT, checked);
        });

        swRing = new Switch(this);
        swRing.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_RING_LOOP, false));
        swRing.setOnCheckedChangeListener((b, checked) -> {
            WebhookPrefs.set(this, WebhookPrefs.KEY_RING_LOOP, checked);
            if (!checked && serviceRunning) {
                startService(new Intent(this, BlockVpnService.class)
                        .setAction(BlockVpnService.ACTION_STOP_RING));
            }
        });

        // 一排两个：通知 / 声音、震动 / 悬浮、持续响铃 / 自定义铃声
        LinearLayout g1 = gridRow();
        g1.addView(switchCell("消息通知", swNotify), cellLp());
        g1.addView(switchCell("声音", swSound), cellLp());
        c2.addView(g1);

        LinearLayout g2 = gridRow();
        g2.addView(switchCell("震动", swVibrate), cellLp());
        g2.addView(switchCell("悬浮通知", swFloat), cellLp());
        c2.addView(g2);

        LinearLayout g3 = gridRow();
        g3.addView(switchCell("持续响铃", swRing), cellLp());
        g3.addView(ringCell(), cellLp());
        c2.addView(g3);
        content.addView(c2);

        // ===== 消息与剪切板 =====
        LinearLayout c3 = card();
        c3.addView(sectionTitle("消息与剪切板"));
        clipSummary = new TextView(this);
        clipSummary.setTextSize(12);
        clipSummary.setTextColor(TEXT_SUB);
        clipSummary.setPadding(0, dp(4), 0, dp(8));
        clipSummary.setLineSpacing(dp(2), 1f);
        clipSummary.setMaxLines(3);
        clipSummary.setEllipsize(TextUtils.TruncateAt.END);
        c3.addView(clipSummary);

        LinearLayout clipBtns = new LinearLayout(this);
        clipBtns.setOrientation(LinearLayout.HORIZONTAL);
        clipBtns.setGravity(Gravity.CENTER_VERTICAL);
        Button open = filledBtn("查看剪切板");
        open.setOnClickListener(v -> startActivity(new Intent(this, ClipboardActivity.class)));
        clipBtns.addView(open, new LinearLayout.LayoutParams(-2, dp(40)));
        Button clear = textBtn("清空消息", RED);
        clear.setOnClickListener(v -> confirmClearHistory());
        clipBtns.addView(clear);
        c3.addView(clipBtns);
        content.addView(c3);

        // ===== 权限 =====
        LinearLayout c4 = card();
        c4.addView(sectionTitle("权限"));
        c4.addView(note("通知权限影响消息通知；悬浮通知需要悬浮窗权限。"));
        LinearLayout prow = gridRow();
        LinearLayout notifCell = permissionRow("通知权限");
        notifValue = (TextView) notifCell.getTag();
        notifCell.setOnClickListener(v -> openNotificationSettings());
        prow.addView(notifCell, cellLp());
        LinearLayout overlayCell = permissionRow("悬浮窗权限");
        overlayValue = (TextView) overlayCell.getTag();
        overlayCell.setOnClickListener(v -> openOverlaySettings());
        LinearLayout.LayoutParams olp = cellLp();
        olp.leftMargin = dp(8);
        prow.addView(overlayCell, olp);
        c4.addView(prow);
        content.addView(c4);

        // ===== 说明 =====
        LinearLayout c5 = card();
        c5.addView(sectionTitle("说明"));
        c5.addView(note("· 任意路径、任意方法均可；GET 带 query 也能接收\n"
                + "· 与 DNS 拦截共用同一个开关，启动后同时生效\n"
                + "· 端口监听在本机局域网，请勿直接暴露到公网\n"
                + "· 锁屏后部分系统可能延迟送达，可将本应用加入后台白名单"));
        content.addView(c5);
        return scroll;
    }

    private ScrollView buildBlockPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(24));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== 订阅管理卡片 =====
        LinearLayout c1 = card();
        c1.addView(sectionTitle("订阅管理"));
        c1.addView(note("添加 AdGuard 规则订阅，切换即时生效"));
        subListBox = new LinearLayout(this);
        subListBox.setOrientation(LinearLayout.VERTICAL);
        c1.addView(subListBox);
        c1.addView(addSubscriptionRow());
        content.addView(c1);

        // ===== 域名重定向卡片 =====
        LinearLayout c2 = card();
        c2.addView(sectionTitle("域名重定向"));
        c2.addView(note("域名（含子域名）→ 指定 IP，优先级最高"));
        redirectListBox = new LinearLayout(this);
        redirectListBox.setOrientation(LinearLayout.VERTICAL);
        c2.addView(redirectListBox);
        c2.addView(addRedirectRow());
        content.addView(c2);

        // ===== 说明卡片 =====
        LinearLayout c3 = card();
        c3.addView(sectionTitle("说明"));
        c3.addView(note("· 拦截规则：||example.org^ 拦截（含子域名）；@@||example.org^ 放行，优先于拦截\n"
                + "· 重定向规则优先级最高，命中后直接返回你设置的 IP\n"
                + "· 拦截通过本地 VPN 在 DNS 层完成，其它流量不经过本应用\n"
                + "· 系统开启「私人 DNS」时可能绕过拦截，建议关闭"));
        content.addView(c3);
        return scroll;
    }

    // ------------------------------------------------------------------ 头部状态

    private void refreshHeader() {
        // 圆形启动按钮：径向渐变 + 白圈 + 投影营造 3D 感；运行后变纯深蓝、「启动」两字消失
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setStroke(dp(3), 0xFFFFFFFF);
        if (serviceRunning) {
            circle.setColor(BLUE_DEEP);
        } else {
            circle.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            circle.setGradientRadius(dp(28));
            circle.setColors(new int[]{0xFF2C6FE8, 0xFF0A3D9E});
        }
        mainBtn.setBackground(circle);
        mainBtn.setText(serviceRunning ? "" : "启动");
        refreshTabs();
    }

    private void refreshTabs() {
        styleTab(tabMessages, currentPage == 0);
        styleTab(tabBlock, currentPage == 1);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? BLUE : TEXT_SUB);
    }

    private void refreshMessageState() {
        if (portInput == null) return;
        if (!portInput.hasFocus()) {
            portInput.setText(String.valueOf(WebhookPrefs.port(this)));
        }
        String ip = localIpv4();
        int port = WebhookPrefs.port(this);
        if (ip == null) {
            addressView.setText("未连接到局域网，连接 Wi‑Fi 或热点后即可接收");
            addressView.setTextColor(TEXT_SUB);
        } else {
            addressView.setText("http://" + ip + ":" + port);
            addressView.setTextColor(BLUE);
        }

        String error = BlockVpnService.statePortError;
        if (error != null && !error.isEmpty()) {
            portHint.setText(error);
            portHint.setVisibility(View.VISIBLE);
        } else {
            portHint.setVisibility(View.GONE);
        }
        refreshPermissions();
    }

    private void refreshClipSummary() {
        if (clipSummary == null) return;
        int total = HistoryStore.count(this);
        if (total == 0) {
            clipSummary.setText("还没有收到消息。");
            return;
        }
        List<String[]> last = HistoryStore.load(this, 1);
        String recent = last.isEmpty() ? "" : oneLine(last.get(0)[1]);
        clipSummary.setText("共 " + total + " 条消息\n最近：" + recent);
    }

    private void refreshRingValue() {
        if (ringValue != null) ringValue.setText(Alerts.ringtoneTitle(this));
    }

    private void refreshPermissions() {
        if (notifValue == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean notify = nm != null && nm.areNotificationsEnabled();
        notifValue.setText(notify ? "已开启" : "去开启");
        notifValue.setTextColor(notify ? TEXT_SUB : RED);

        boolean overlay = Settings.canDrawOverlays(this);
        overlayValue.setText(overlay ? "已开启" : "去开启");
        overlayValue.setTextColor(overlay ? TEXT_SUB : RED);
    }

    // ------------------------------------------------------------------ 订阅 / 重定向

    private void rebuildSubRows() {
        subListBox.removeAllViews();
        if (subs.list.isEmpty()) {
            subListBox.addView(empty("暂无订阅，请在下方添加订阅链接"));
        }
        for (final Subs.Sub s : subs.list) {
            LinearLayout row = itemRow();

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(hostOf(s.url));
            name.setTextSize(14);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(TEXT_MAIN);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(name);
            TextView meta = new TextView(this);
            meta.setText(Subs.cachedLines(this, s.url) + " 行规则"
                    + (s.enabled ? "" : " · 已停用"));
            meta.setTextSize(11);
            meta.setTextColor(TEXT_SUB);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(meta);
            info.setOnClickListener(v -> toast(s.url));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

            Switch sw = new Switch(this);
            sw.setChecked(s.enabled);
            sw.setOnCheckedChangeListener((btn, checked) -> {
                s.enabled = checked;
                subs.save();
                if (checked) download(s, false, true);
                else sendReload();
                rebuildSubRows();
            });
            row.addView(sw);

            Button upd = textBtn("更新", BLUE);
            upd.setOnClickListener(v -> download(s, true, true));
            row.addView(upd);

            Button del = textBtn("删除", RED);
            del.setOnClickListener(v -> confirmDeleteSub(s));
            row.addView(del);

            subListBox.addView(row, itemLp());
        }
    }

    private void rebuildRedirectRows() {
        redirectListBox.removeAllViews();
        if (redirects.list.isEmpty()) {
            redirectListBox.addView(empty("暂无重定向规则"));
        }
        for (final RedirectStore.Entry e : redirects.list) {
            LinearLayout row = itemRow();

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(e.domain);
            name.setTextSize(14);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(e.enabled ? TEXT_MAIN : TEXT_SUB);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(name);
            TextView meta = new TextView(this);
            meta.setText("→ " + e.ip + (e.enabled ? "" : " · 已停用"));
            meta.setTextSize(11);
            meta.setTextColor(TEXT_SUB);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(meta);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

            Switch sw = new Switch(this);
            sw.setChecked(e.enabled);
            sw.setOnCheckedChangeListener((btn, checked) -> {
                e.enabled = checked;
                redirects.save();
                rebuildRedirectRows();
                sendReload();
            });
            row.addView(sw);

            Button del = textBtn("删除", RED);
            del.setOnClickListener(v -> {
                redirects.list.remove(e);
                redirects.save();
                rebuildRedirectRows();
                toast("已删除 " + e.domain);
                sendReload();
            });
            row.addView(del);

            redirectListBox.addView(row, itemLp());
        }
    }

    // ------------------------------------------------------------------ 操作

    private void addSub() {
        String url = subInput.getText().toString().trim();
        if (url.length() < 9 || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("请输入 http(s):// 开头的订阅链接");
            return;
        }
        for (Subs.Sub s : subs.list) {
            if (s.url.equals(url)) {
                toast("该订阅已存在");
                return;
            }
        }
        Subs.Sub s = new Subs.Sub(url, true);
        subs.list.add(s);
        subs.save();
        subInput.setText("");
        rebuildSubRows();
        download(s, true, true);
    }

    private void confirmDeleteSub(final Subs.Sub s) {
        new AlertDialog.Builder(this)
                .setTitle("删除订阅")
                .setMessage(hostOf(s.url))
                .setPositiveButton("删除", (d, w) -> {
                    subs.list.remove(s);
                    subs.save();
                    Subs.fileFor(this, s.url).delete();
                    rebuildSubRows();
                    sendReload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addRedirect() {
        String domain = redDomainInput.getText().toString().trim().toLowerCase();
        String ip = redIpInput.getText().toString().trim();
        if (!validDomain(domain)) {
            toast("请输入合法域名（a-z、0-9、.、-）");
            return;
        }
        if (RedirectStore.parseIp(ip) == null) {
            toast("请输入合法的 IPv4 / IPv6 地址");
            return;
        }
        boolean updated = false;
        for (RedirectStore.Entry e : redirects.list) {
            if (e.domain.equals(domain)) {
                e.ip = ip;
                e.enabled = true;
                updated = true;
                break;
            }
        }
        if (!updated) redirects.list.add(new RedirectStore.Entry(domain, ip, true));
        redirects.save();
        redDomainInput.setText("");
        redIpInput.setText("");
        rebuildRedirectRows();
        toast(updated ? "已更新重定向规则" : "已添加重定向规则");
        sendReload();
    }

    private static boolean validDomain(String d) {
        if (d.isEmpty() || d.length() > 253) return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_')) return false;
        }
        return true;
    }

    /** @param force true=强制重新下载；false=仅缺文件时下载 */
    private void download(final Subs.Sub s, boolean force, boolean reloadAfter) {
        File f = Subs.fileFor(this, s.url);
        if (!force && f.isFile() && f.length() > 0) {
            if (reloadAfter) sendReload();
            return;
        }
        if (force) toast("正在更新：" + hostOf(s.url));
        new Thread(() -> {
            try {
                int n = Subs.download(this, s.url);
                runOnUiThread(() -> {
                    toast("下载成功，共 " + n + " 行规则");
                    rebuildSubRows();
                    if (reloadAfter) sendReload();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("下载失败：" + e.getMessage()));
            }
        }, "download").start();
    }

    private void sendReload() {
        startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_RELOAD));
    }

    private void toggleVpn() {
        if (serviceRunning) {
            startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_STOP));
            return;
        }
        pendingStart = true;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        startFlow();
    }

    private void startFlow() {
        pendingStart = false;
        Intent i = VpnService.prepare(this);
        if (i != null) {
            startActivityForResult(i, REQ_VPN);
        } else {
            startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_START));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_NOTIF && pendingStart) startFlow();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) {
                startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_START));
            } else {
                pendingStart = false;
                toast("未授权 VPN，已取消启动");
            }
        } else if (req == REQ_RING) {
            Uri uri = data == null ? null
                    : data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (uri == null) {
                WebhookPrefs.setRingtone(this, "");
            } else {
                WebhookPrefs.setRingtone(this, uri.toString());
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
            }
            refreshRingValue();
            previewRingtone();
        }
    }

    private void applyPort() {
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception e) {
            toast("请输入端口号");
            return;
        }
        if (port < 1024 || port > 65535) {
            toast("端口范围 1024–65535");
            return;
        }
        WebhookPrefs.setPort(this, port);
        portInput.clearFocus();
        toast("端口已保存：" + port);
        refreshMessageState();
        sendReload();
    }

    private void pickRingtone() {
        Intent it = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        it.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        it.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        it.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        it.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        String current = WebhookPrefs.ringtone(this);
        if (current != null && !current.isEmpty()) {
            it.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current));
        }
        try {
            startActivityForResult(it, REQ_RING);
        } catch (Exception e) {
            toast("无法打开铃声选择器");
        }
    }

    private void previewRingtone() {
        String value = WebhookPrefs.ringtone(this);
        if (value == null || value.isEmpty()) return;
        try {
            previewTone = RingtoneManager.getRingtone(this, Uri.parse(value));
            if (previewTone != null) previewTone.play();
        } catch (Exception ignored) {
        }
    }

    private void confirmClearHistory() {
        int total = HistoryStore.count(this);
        if (total == 0) {
            toast("暂无消息");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空消息")
                .setMessage("将删除全部 " + total + " 条消息记录，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    HistoryStore.clear(this);
                    refreshClipSummary();
                    toast("已清空");
                })
                .show();
    }

    // ------------------------------------------------------------------ 组件

    /** 两列布局的一行 */
    private LinearLayout gridRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams cellLp() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout switchCell(String label, Switch sw) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(2), dp(5), dp(2), dp(5));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13);
        text.setTextColor(TEXT_MAIN);
        cell.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        cell.addView(sw);
        return cell;
    }

    private LinearLayout ringCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(2), dp(5), dp(2), dp(5));
        cell.setClickable(true);
        cell.setOnClickListener(v -> pickRingtone());

        TextView label = new TextView(this);
        label.setText("自定义铃声");
        label.setTextSize(13);
        label.setTextColor(TEXT_MAIN);
        cell.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        ringValue = new TextView(this);
        ringValue.setTextSize(11);
        ringValue.setTextColor(TEXT_SUB);
        ringValue.setSingleLine(true);
        ringValue.setEllipsize(TextUtils.TruncateAt.END);
        ringValue.setMaxWidth(dp(72));
        cell.addView(ringValue);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(15);
        arrow.setTextColor(TEXT_SUB);
        arrow.setPadding(dp(4), 0, 0, 0);
        cell.addView(arrow);
        return cell;
    }

    private LinearLayout permissionRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundedBox(ITEM_BG, 12));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setClickable(true);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(12);
        text.setTextColor(TEXT_MAIN);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView value = new TextView(this);
        value.setTextSize(11);
        value.setTextColor(TEXT_SUB);
        row.addView(value);
        row.setTag(value);
        return row;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundedBox(CARD, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.MarginLayoutParams lp =
                new LinearLayout.MarginLayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout sectionTitle(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackground(roundedBox(BLUE, 2));
        row.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(16)));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(TEXT_MAIN);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(TEXT_SUB);
        t.setPadding(0, dp(4), 0, dp(6));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView empty(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(TEXT_SUB);
        t.setPadding(dp(4), dp(6), dp(4), dp(8));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private LinearLayout itemRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundedBox(ITEM_BG, 12));
        row.setPadding(dp(12), dp(8), dp(4), dp(8));
        return row;
    }

    private LinearLayout.MarginLayoutParams itemLp() {
        LinearLayout.MarginLayoutParams lp =
                new LinearLayout.MarginLayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private EditText boxInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(TEXT_MAIN);
        e.setHintTextColor(0xFFA9B4C6);
        e.setBackground(roundedBox(INPUT_BG, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private Button textBtn(String text, int color) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setMinWidth(dp(46));
        return b;
    }

    private Button filledBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        b.setBackground(pill(BLUE));
        b.setBackgroundTintList(null);
        b.setPadding(dp(18), 0, dp(18), 0);
        return b;
    }

    private LinearLayout addSubscriptionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        subInput = boxInput("https://… AdGuard 规则订阅链接");
        row.addView(subInput, new LinearLayout.LayoutParams(0, -2, 1f));
        Button add = filledBtn("添加");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.leftMargin = dp(8);
        row.addView(add, lp);
        add.setOnClickListener(v -> addSub());
        return row;
    }

    private LinearLayout addRedirectRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(4), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        redDomainInput = boxInput("域名，如 example.com");
        redIpInput = boxInput("IP，如 10.0.0.2");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1.2f);
        row.addView(redDomainInput, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1f);
        p2.leftMargin = dp(8);
        row.addView(redIpInput, p2);
        wrap.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.END);
        Button add = filledBtn("添加重定向");
        row2.addView(add, new LinearLayout.LayoutParams(-2, dp(40)));
        add.setOnClickListener(v -> addRedirect());
        LinearLayout.MarginLayoutParams lp2 =
                new LinearLayout.MarginLayoutParams(-2, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(8);
        wrap.addView(row2, lp2);
        return wrap;
    }

    // ------------------------------------------------------------------ 权限跳转

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            toast("无法打开通知设置");
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("无法打开悬浮窗设置");
        }
    }

    // ------------------------------------------------------------------ 工具

    private String localIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TinyBlock", text));
            toast("已复制");
        } catch (Exception ignored) {
        }
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        String s = text.replace('\n', ' ').trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h != null && h.length() > 0) return h;
        } catch (Exception ignored) {
        }
        return url;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(24));
        return g;
    }

    private GradientDrawable roundedBox(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    // ------------------------------------------------------------------ 崩溃日志

    private File crashFile() {
        File dir = getExternalFilesDir(null);
        return dir == null ? new File(getFilesDir(), "crash.txt") : new File(dir, "crash.txt");
    }

    /** 崩溃时把堆栈写入 crash.txt，供下次启动展示 */
    private void installCrashGuard() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(crashFile(), true), "UTF-8"));
                pw.println("==== " + new java.util.Date() + " ====");
                e.printStackTrace(pw);
                pw.close();
            } catch (Exception ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private void showCrashReport(File f) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 20000) break;
            }
        } catch (Exception ignored) {
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        TextView t = new TextView(this);
        t.setText("检测到崩溃日志\n（点「复制日志」发送给开发者；点「清除并继续」尝试正常打开）");
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(TEXT_MAIN);
        root.addView(t);

        TextView body = new TextView(this);
        body.setText(sb.length() == 0 ? "(日志为空)" : sb.toString());
        body.setTextSize(11);
        body.setTextColor(0xFF444444);
        body.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svlp.topMargin = dp(10);
        root.addView(sv, svlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("复制日志");
        copy.setOnClickListener(v -> copyText(sb.toString()));
        btns.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        Button cont = new Button(this);
        cont.setText("清除并继续");
        cont.setOnClickListener(v -> {
            f.delete();
            recreate();
        });
        btns.addView(cont, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(10);
        root.addView(btns, blp);
        setContentView(root);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
