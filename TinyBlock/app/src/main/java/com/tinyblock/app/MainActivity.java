package com.tinyblock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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

    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIF = 2;

    private Subs subs;
    private LinearLayout listBox;
    private TextView statusView;
    private Button mainBtn;
    private EditText input;
    private boolean serviceRunning;
    private boolean pendingStart;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            serviceRunning = i.getBooleanExtra(BlockVpnService.EXTRA_RUNNING, false);
            showStatus(i.getIntExtra(BlockVpnService.EXTRA_RULES, 0));
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        subs = new Subs(this);
        buildUi();
        serviceRunning = BlockVpnService.stateRunning;
        showStatus(BlockVpnService.stateRules);
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

    private void showStatus(int rules) {
        statusView.setText(serviceRunning
                ? "● 服务运行中，已加载 " + rules + " 条域名规则"
                : "○ 服务未运行");
        mainBtn.setText(serviceRunning ? "停止拦截" : "启动拦截");
    }

    // ------------------------------------------------------------------ 界面

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("TinyBlock");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setPadding(0, dp(6), 0, dp(6));
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF444444);
        root.addView(statusView);

        mainBtn = new Button(this);
        mainBtn.setTextSize(16);
        mainBtn.setOnClickListener(v -> toggleVpn());
        root.addView(mainBtn, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFDDDDDD);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        TextView subLabel = new TextView(this);
        subLabel.setText("订阅列表（AdGuard 黑白名单）");
        subLabel.setTextSize(15);
        subLabel.setTypeface(null, Typeface.BOLD);
        subLabel.setPadding(0, dp(12), 0, dp(2));
        root.addView(subLabel);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, dp(8), 0, 0);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(13);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://… AdGuard 规则订阅链接");
        addRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = new Button(this);
        add.setText("添加");
        add.setOnClickListener(v -> addSub());
        addRow.addView(add);
        root.addView(addRow);

        TextView hint = new TextView(this);
        hint.setText("支持 AdGuard 语法（||example.org^ 拦截、@@||example.org^ 放行）及 hosts 格式，"
                + "放行规则优先于拦截规则。拦截通过本地 VPN 在 DNS 层完成，其它流量不经过本应用。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF888888);
        hint.setPadding(0, dp(12), 0, dp(8));
        root.addView(hint);

        setContentView(scroll);
        rebuildRows();
    }

    private void rebuildRows() {
        listBox.removeAllViews();
        if (subs.list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无订阅。可添加 AdGuard DNS 过滤表，例如：\nadguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt");
            empty.setTextSize(13);
            empty.setTextColor(0xFF888888);
            empty.setPadding(0, dp(6), 0, dp(6));
            listBox.addView(empty);
        }
        for (int i = 0; i < subs.list.size(); i++) {
            final Subs.Sub s = subs.list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView name = new TextView(this);
            name.setText(hostOf(s.url) + "\n" + Subs.cachedLines(this, s.url) + " 行规则");
            name.setTextSize(13);
            name.setSingleLine(false);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setOnClickListener(v -> toast(s.url));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(this);
            sw.setChecked(s.enabled);
            sw.setOnCheckedChangeListener((btn, checked) -> {
                s.enabled = checked;
                subs.save();
                if (checked) download(s, false, true);
                else if (serviceRunning) sendReload();
            });
            row.addView(sw);

            Button refresh = borderless("更新");
            refresh.setOnClickListener(v -> download(s, true, true));
            row.addView(refresh);

            Button del = borderless("删除");
            del.setOnClickListener(v -> confirmDelete(s));
            row.addView(del);

            listBox.addView(row);
        }
    }

    private Button borderless(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextSize(12);
        b.setMinWidth(dp(52));
        return b;
    }

    // ------------------------------------------------------------------ 操作

    private void addSub() {
        String url = input.getText().toString().trim();
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
        input.setText("");
        rebuildRows();
        download(s, true, true);
    }

    private void confirmDelete(Subs.Sub s) {
        new AlertDialog.Builder(this)
                .setTitle("删除订阅")
                .setMessage(hostOf(s.url))
                .setPositiveButton("删除", (d, w) -> {
                    subs.list.remove(s);
                    subs.save();
                    Subs.fileFor(this, s.url).delete();
                    rebuildRows();
                    if (serviceRunning) sendReload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** @param force true=强制重新下载；false=仅缺文件时下载 */
    private void download(Subs.Sub s, boolean force, boolean reloadAfter) {
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
                    rebuildRows();
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
