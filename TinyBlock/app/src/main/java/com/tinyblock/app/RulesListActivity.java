package com.tinyblock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 订阅 / 重定向清单页：独立页面，顶部返回按钮，跟随 App 深色主题 */
public class RulesListActivity extends Activity {

    static final String EXTRA_MODE = "mode";
    static final String MODE_SUBS = "subs";
    static final String MODE_REDIRECTS = "redirects";

    private static final int BLUE = 0xFF1A73E8;

    private int BG;
    private int TEXT;
    private int MUTED;
    private int CARD;
    private int RED;

    private boolean subsMode;
    private final List<Object> rows = new ArrayList<>(); // Subs.Sub 或 RedirectStore.Entry
    private ListView list;
    private TextView empty;
    private Adapter adapter;
    private Subs subs;
    private RedirectStore redirectStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        subsMode = MODE_SUBS.equals(getIntent().getStringExtra(EXTRA_MODE));
        boolean dark = WebhookPrefs.get(this, WebhookPrefs.KEY_DARK, false);
        BG = dark ? 0xFF11161F : 0xFFEEF3FB;
        TEXT = dark ? 0xFFE8EEF9 : 0xFF1B2735;
        MUTED = dark ? 0xFF8B98AD : 0xFF7C8DA6;
        CARD = dark ? 0xFF1A2230 : 0xFFFFFFFF;
        RED = dark ? 0xFFFF7061 : 0xFFD93025;

        // 沉浸式：状态栏透明、内容延伸到状态栏底下，颜色跟随页面背景
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        View decor = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController ic = decor.getWindowInsetsController();
            if (ic != null) {
                ic.setSystemBarsAppearance(
                        dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }

        super.onCreate(savedInstanceState);

        subs = new Subs(this);
        redirectStore = new RedirectStore(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(TEXT);
        back.setPadding(dp(8), 0, dp(12), dp(2));
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(subsMode ? "已订阅规则" : "重定向规则");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(this);
        hint.setText(subsMode ? "点链接启用/停用" : "点规则启用/停用");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(MUTED);
        bar.addView(hint);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setBackgroundColor(BG);
        list.setDivider(new ColorDrawable(0x00000000));
        list.setDividerHeight(dp(8));
        list.setPadding(dp(14), dp(10), dp(14), dp(18));
        list.setClipToPadding(false);
        list.setCacheColorHint(0);
        list.setSelector(new ColorDrawable(0x00000000));
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);
        empty.setText(subsMode
                ? "暂无订阅\n回到拦截页添加订阅链接" : "暂无重定向规则\n回到拦截页添加域名和 IP");
        empty.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        elp.setMargins(dp(28), 0, dp(28), 0);
        root.addView(empty, elp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return Build.VERSION.SDK_INT >= 30
                    ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });

        setContentView(root);
    }

    private void refresh() {
        rows.clear();
        if (subsMode) {
            rows.addAll(subs.list);
        } else {
            rows.addAll(redirectStore.list);
        }
        adapter.notifyDataSetChanged();
        boolean isEmpty = rows.isEmpty();
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private static String tailOf(String url) {
        int i = url.lastIndexOf('/');
        String t = i >= 0 ? url.substring(i + 1) : url;
        return t.isEmpty() ? hostOf(url) : t;
    }

    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h != null && h.length() > 0) return h;
        } catch (Exception ignored) {
        }
        return url;
    }

    private void toggle(Object o) {
        if (subsMode) {
            Subs.Sub s = (Subs.Sub) o;
            s.enabled = !s.enabled;
            subs.save();
            if (s.enabled) {
                toast("已启用，正在拉取规则");
                download(s, false, true);
            } else {
                toast("已停用");
                reload();
            }
        } else {
            RedirectStore.Entry e = (RedirectStore.Entry) o;
            e.enabled = !e.enabled;
            redirectStore.save();
            toast(e.enabled ? "已启用" : "已停用");
            reload();
        }
        refresh();
    }

    private void download(final Subs.Sub s, boolean force, boolean reloadAfter) {
        java.io.File f = Subs.fileFor(this, s.url);
        if (!force && f.isFile() && f.length() > 0) {
            if (reloadAfter) reload();
            return;
        }
        if (force) toast("正在更新：" + hostOf(s.url));
        new Thread(() -> {
            try {
                int n = Subs.download(this, s.url);
                runOnUiThread(() -> {
                    toast("下载成功，共 " + n + " 行规则");
                    refresh();
                    if (reloadAfter) reload();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("下载失败：" + e.getMessage()));
            }
        }, "download").start();
    }

    private void confirmDelete(Object o) {
        if (!subsMode) {
            deleteRow(o);
            return;
        }
        Subs.Sub s = (Subs.Sub) o;
        new AlertDialog.Builder(this)
                .setTitle("删除订阅")
                .setMessage(hostOf(s.url))
                .setPositiveButton("删除", (d, w) -> deleteRow(s))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteRow(Object o) {
        if (subsMode) {
            Subs.Sub s = (Subs.Sub) o;
            subs.list.remove(s);
            subs.save();
            Subs.fileFor(this, s.url).delete();
        } else {
            RedirectStore.Entry e = (RedirectStore.Entry) o;
            redirectStore.list.remove(e);
            redirectStore.save();
        }
        toast("已删除");
        reload();
        refresh();
    }

    private void reload() {
        startService(new Intent(this, BlockVpnService.class)
                .setAction(BlockVpnService.ACTION_RELOAD));
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            boolean enabled;
            String name;
            String meta;

            LinearLayout row = new LinearLayout(RulesListActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(12), dp(6), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(10));
            row.setBackground(bg);

            if (subsMode) {
                Subs.Sub s = (Subs.Sub) o;
                enabled = s.enabled;
                name = tailOf(s.url);
                meta = Subs.cachedLines(RulesListActivity.this, s.url) + " 行规则"
                        + (enabled ? "" : " · 已停用");
            } else {
                RedirectStore.Entry e = (RedirectStore.Entry) o;
                enabled = e.enabled;
                name = e.domain;
                meta = "→ " + e.ip + (enabled ? "" : " · 已停用");
            }

            TextView del = new TextView(RulesListActivity.this);
            del.setText("删除");
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            del.setTextColor(RED);
            del.setPadding(dp(10), dp(6), dp(6), dp(6));
            del.setOnClickListener(v -> confirmDelete(o));
            row.addView(del);

            LinearLayout info = new LinearLayout(RulesListActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(8), 0, dp(8), 0);
            TextView nameView = new TextView(RulesListActivity.this);
            nameView.setText(name);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            nameView.setTextColor(enabled ? BLUE : TEXT);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(nameView);
            TextView metaView = new TextView(RulesListActivity.this);
            metaView.setText(meta);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            metaView.setTextColor(MUTED);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(metaView);
            info.setOnClickListener(v -> toggle(o));
            row.addView(info, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (subsMode) {
                TextView upd = new TextView(RulesListActivity.this);
                upd.setText("更新");
                upd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                upd.setTextColor(BLUE);
                upd.setPadding(dp(6), dp(6), dp(10), dp(6));
                upd.setOnClickListener(v -> download((Subs.Sub) o, true, true));
                row.addView(upd);
            }
            return row;
        }
    }
}
