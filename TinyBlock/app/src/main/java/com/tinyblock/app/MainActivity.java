package com.tinyblock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
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

public class MainActivity extends Activity {

    // ---- 蓝色主题配色 ----
    private static final int BLUE = 0xFF1A73E8;
    private static final int BLUE_SOFT = 0xFF3B82F6;
    private static final int BG = 0xFFEEF3FB;
    private static final int CARD = 0xFFFFFFFF;
    private static final int ITEM_BG = 0xFFF5F8FD;
    private static final int INPUT_BG = 0xFFF2F5FB;
    private static final int TEXT_MAIN = 0xFF17233B;
    private static final int TEXT_SUB = 0xFF7A8699;
    private static final int RED = 0xFFD93025;

    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIF = 2;

    private Subs subs;
    private RedirectStore redirects;
    private LinearLayout subListBox;
    private LinearLayout redirectListBox;
    private TextView statusView;
    private Button mainBtn;
    private EditText subInput;
    private EditText redDomainInput;
    private EditText redIpInput;
    private boolean serviceRunning;
    private boolean pendingStart;
    private int ruleCount;
    private int redirectCount;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            serviceRunning = i.getBooleanExtra(BlockVpnService.EXTRA_RUNNING, false);
            ruleCount = i.getIntExtra(BlockVpnService.EXTRA_RULES, 0);
            redirectCount = i.getIntExtra(BlockVpnService.EXTRA_REDIRECTS, 0);
            showStatus();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        subs = new Subs(this);
        redirects = new RedirectStore(this);
        buildUi();
        serviceRunning = BlockVpnService.stateRunning;
        ruleCount = BlockVpnService.stateRules;
        redirectCount = BlockVpnService.stateRedirects;
        showStatus();
        IntentFilter f = new IntentFilter(BlockVpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    private void showStatus() {
        statusView.setText(serviceRunning
                ? "● 服务运行中 · 拦截规则 " + ruleCount + " 条 · 重定向 " + redirectCount + " 条"
                : "○ 服务未运行 · 拦截规则 " + ruleCount + " 条 · 重定向 " + redirectCount + " 条");
        mainBtn.setText(serviceRunning ? "停止拦截" : "启动拦截");
        if (serviceRunning) {
            mainBtn.setTextColor(0xFFFFFFFF);
            mainBtn.setBackground(pill(0x33FFFFFF));
        } else {
            mainBtn.setTextColor(BLUE);
            mainBtn.setBackground(pill(0xFFFFFFFF));
        }
    }

    // ------------------------------------------------------------------ 界面搭建

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));

        // ===== 渐变蓝头部 =====
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hbg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{BLUE, BLUE_SOFT});
        float hr = dp(24);
        hbg.setCornerRadii(new float[]{0, 0, 0, 0, hr, hr, hr, hr});
        header.setBackground(hbg);
        header.setPadding(dp(20), dp(26), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("TinyBlock");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("AdGuard 订阅拦截 · 域名重定向");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xB3FFFFFF);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        header.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFFFFFFFF);
        header.addView(statusView);

        mainBtn = new Button(this);
        mainBtn.setTextSize(15);
        mainBtn.setTypeface(null, Typeface.BOLD);
        mainBtn.setAllCaps(false);
        mainBtn.setBackground(pill(0xFFFFFFFF));
        mainBtn.setOnClickListener(v -> toggleVpn());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(48));
        mlp.topMargin = dp(14);
        header.addView(mainBtn, mlp);
        body.addView(header, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(24));
        body.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== 订阅管理卡片 =====
        LinearLayout c1 = card();
        c1.addView(sectionTitle("订阅管理"));
        c1.addView(note("添加 AdGuard 黑白名单订阅，支持启用 / 禁用、更新、删除，切换即时生效。"));
        subListBox = new LinearLayout(this);
        subListBox.setOrientation(LinearLayout.VERTICAL);
        c1.addView(subListBox);
        c1.addView(addSubscriptionRow());
        content.addView(c1);

        // ===== 域名重定向卡片 =====
        LinearLayout c2 = card();
        c2.addView(sectionTitle("域名重定向"));
        c2.addView(note("把指定域名（含子域名）解析到你设定的 IP，支持 IPv4 / IPv6，优先级最高。"));
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

        setContentView(scroll);
        rebuildSubRows();
        rebuildRedirectRows();
    }

    private void rebuildSubRows() {
        subListBox.removeAllViews();
        if (subs.list.isEmpty()) {
            subListBox.addView(empty("暂无订阅。推荐添加 AdGuard DNS 过滤表：\nadguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"));
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
                else if (serviceRunning) sendReload();
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
            redirectListBox.addView(empty("暂无重定向规则。例如：nas.example.com → 192.168.1.10"));
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
                if (serviceRunning) sendReload();
            });
            row.addView(sw);

            Button del = textBtn("删除", RED);
            del.setOnClickListener(v -> {
                redirects.list.remove(e);
                redirects.save();
                rebuildRedirectRows();
                toast("已删除 " + e.domain);
                if (serviceRunning) sendReload();
            });
            row.addView(del);

            redirectListBox.addView(row, itemLp());
        }
    }

    // ------------------------------------------------------------------ 行/卡片构件

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
                    if (serviceRunning) sendReload();
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
        if (serviceRunning) sendReload();
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
            if (reloadAfter && serviceRunning) sendReload();
            return;
        }
        if (force) toast("正在更新：" + hostOf(s.url));
        new Thread(() -> {
            try {
                int n = Subs.download(this, s.url);
                runOnUiThread(() -> {
                    toast("下载成功，共 " + n + " 行规则");
                    rebuildSubRows();
                    if (reloadAfter && serviceRunning) sendReload();
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
        if (req == REQ_VPN && res == RESULT_OK) {
            startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_START));
        }
    }

    // ------------------------------------------------------------------ 工具

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

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
