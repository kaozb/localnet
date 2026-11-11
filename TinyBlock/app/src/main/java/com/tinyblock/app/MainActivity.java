package com.tinyblock.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
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
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {

    // ---- 配色：BLUE 固定，表面色随黑白主题切换 ----
    private static final int BLUE = 0xFF1A73E8;
    private static final int GREEN = 0xFF12A150;
    private static final int LIGHT_BG = 0xFFEEF3FB;
    private static final int LIGHT_CARD = 0xFFFFFFFF;
    private static final int LIGHT_ITEM = 0xFFF5F8FD;
    private static final int LIGHT_INPUT = 0xFFF2F5FB;
    private static final int LIGHT_MAIN = 0xFF17233B;
    private static final int LIGHT_SUB = 0xFF7A8699;
    private static final int LIGHT_RED = 0xFFD93025;
    private static final int DARK_BG = 0xFF11161F;
    private static final int DARK_CARD = 0xFF1A2230;
    private static final int DARK_ITEM = 0xFF243044;
    private static final int DARK_INPUT = 0xFF1E2839;
    private static final int DARK_MAIN = 0xFFE8EEF9;
    private static final int DARK_SUB = 0xFF8B98AD;
    private static final int DARK_RED = 0xFFFF7061;

    private int BG;
    private int CARD;
    private int ITEM_BG;
    private int INPUT_BG;
    private int TEXT_MAIN;
    private int TEXT_SUB;
    private int RED;

    /** 底部标签栏高度：标签栏浮在内容之上，页面内容按这个高度留出底部空白 */
    private static final int TAB_BAR_H = 56;
    /** 圆形启动按钮直径：独立浮在画板右下角，不占标签行 */
    private static final int FAB_SIZE = 56;

    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_RING = 3;
    private static final int REQ_SMS = 4;

    private Subs subs;
    private RedirectStore redirects;

    /** 圆形启动按钮：浮在标签栏上方的独立圆卡，按当前页显示 消息 / 短信 / DNS */
    private TextView fab;
    /** 圆形按钮当前对应的功能 key，设置页为空（按钮隐藏） */
    private String fabKey;
    private TextView dnsState;
    private TextView tabMessages;
    private TextView tabSms;
    private TextView tabBlock;
    private TextView tabSettings;

    private SwipePager pager;
    private int currentPage = 2;
    /** 深色主题 recreate 后要恢复的页码，-1 表示无 */
    private static int pendingPage = -1;

    private EditText portInput;
    private TextView addressView;
    private TextView portHint;
    private NeoSwitch swNotify;
    private NeoSwitch swSound;
    private NeoSwitch swVibrate;
    private NeoSwitch swFloat;
    private NeoSwitch swRing;
    private TextView ringValue;
    private TextView clipSummary;
    private TextView notifBtn;
    private TextView overlayBtn;
    private EditText dnsInput;

    private EditText subInput;
    private EditText redDomainInput;
    private EditText redIpInput;
    private EditText smsInput;
    private EditText smsBodyInput;

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
        applyTheme();
        installCrashGuard();
        File cf = crashFile();
        if (cf.isFile()) {
            showCrashReport(cf);
            return;
        }
        if (pendingPage >= 0) {
            currentPage = pendingPage;
            pendingPage = -1;
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
        ScreenKeeper.sync(this);
        refreshHeader();
        refreshMessageState();
        refreshClipSummary();
        refreshPermissions();
        refreshRingValue();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 从其他页面/应用切回来时不自动弹输入法：清掉残留焦点，点输入框才弹
            View f = getCurrentFocus();
            if (f instanceof EditText) {
                f.clearFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            }
        }
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
        if (pager != null && pager.getCurrentItem() > 0) {
            pager.setCurrentItem(pager.getCurrentItem() - 1, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 界面搭建

    private void buildUi() {
        // 整个界面就是一块画板：页面铺满全屏，底部标签与圆形启动按钮直接坐在上面，
        // 不再有「内容区 + 底部栏」两层容器，也就没有任何外框
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        pager = new SwipePager(this);
        root.addView(pager, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(buildTabBar(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(TAB_BAR_H), Gravity.BOTTOM));

        // 启动按钮是一张独立的圆形卡片，浮在画板右下角、标签行上方，不参与标签排布
        fab = buildFab();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                dp(FAB_SIZE), dp(FAB_SIZE), Gravity.BOTTOM | Gravity.END);
        flp.rightMargin = dp(16);
        flp.bottomMargin = dp(TAB_BAR_H + 16);
        root.addView(fab, flp);

        setContentView(root);

        // 沉浸式 insets：系统栏区域显示页面背景色（颜色跟随），实际内容顶到系统栏之下；
        // 底部只按导航栏高度留白，键盘弹出时直接盖住底部导航，不顶起
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

        pager.setPages(buildMessagePage(), buildSmsPage(), buildBlockPage(), buildSettingsPage());
        pager.setOnPageChangeListener(index -> {
            currentPage = index;
            refreshTabs();
            if (index == 0) {
                refreshMessageState();
                refreshClipSummary();
            }
        });
        pager.setCurrentItem(currentPage, false);
    }

    /** 圆形启动按钮：单独一个圆卡，只对应当前页功能 */
    private TextView buildFab() {
        TextView b = new TextView(this);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        // 浮在内容之上，靠投影和下面的卡片区分开（圆卡本身就是一层）
        b.setElevation(dp(6));
        b.setOnClickListener(v -> {
            if (fabKey == null) return;
            if (WebhookPrefs.KEY_SMS.equals(fabKey)) toggleSms();
            else toggleFeature(fabKey);
        });
        return b;
    }

    /** 底部标签行：四个纯文字标签等分整行，无底色无边框，单层坐在页面背景上 */
    private LinearLayout buildTabBar() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        // 与页面背景同色：看不出任何额外一层，但能挡住滑到底时从下面穿过的卡片
        tabs.setBackgroundColor(BG);

        tabMessages = addTab(tabs, "消息", 0);
        tabSms = addTab(tabs, "短信", 1);
        tabBlock = addTab(tabs, "DNS", 2);
        tabSettings = addTab(tabs, "设置", 3);
        return tabs;
    }

    /** 底部标签：等分宽度，选中变蓝加粗 */
    private TextView addTab(LinearLayout bar, String text, int page) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setTextSize(14);
        tab.setGravity(Gravity.CENTER);
        tab.setOnClickListener(v -> pager.setCurrentItem(page, true));
        bar.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
        return tab;
    }

    private ScrollView buildMessagePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(6), dp(14), dp(TAB_BAR_H + FAB_SIZE + 16));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== 消息接收 =====
        LinearLayout c1 = card();
        c1.addView(sectionTitle("消息接收"));

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

        swNotify = newSwitch();
        swNotify.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_NOTIFY, true));
        swNotify.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_NOTIFY, checked));

        swSound = newSwitch();
        swSound.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_SOUND, true));
        swSound.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_SOUND, checked));

        swVibrate = newSwitch();
        swVibrate.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_VIBRATE, true));
        swVibrate.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_VIBRATE, checked));

        swFloat = newSwitch();
        swFloat.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_FLOAT, false));
        swFloat.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !Settings.canDrawOverlays(this)) {
                WebhookPrefs.set(this, WebhookPrefs.KEY_FLOAT, false);
                b.setChecked(false);
                toast("请先在「设置」页授予悬浮窗权限");
                return;
            }
            WebhookPrefs.set(this, WebhookPrefs.KEY_FLOAT, checked);
        });

        swRing = newSwitch();
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
        Button open = filledBtn("剪切板");
        open.setOnClickListener(v -> startActivity(new Intent(this, ClipboardActivity.class)));
        clipBtns.addView(open, new LinearLayout.LayoutParams(0, dp(40), 1f));
        c3.addView(clipBtns);
        content.addView(c3);
        return scroll;
    }

    // ------------------------------------------------------------------ 短信转发

    private ScrollView buildSmsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(6), dp(14), dp(TAB_BAR_H + FAB_SIZE + 16));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== 转发地址 =====
        LinearLayout c1 = card();
        c1.addView(sectionTitle("短信转发"));

        smsInput = new EditText(this);
        smsInput.setHint("转发地址");
        smsInput.setTextSize(13);
        smsInput.setTextColor(TEXT_MAIN);
        smsInput.setHintTextColor(TEXT_SUB);
        smsInput.setBackground(roundedBox(INPUT_BG, 10));
        smsInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        smsInput.setMaxLines(2);
        smsInput.setText(WebhookPrefs.smsUrl(this));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(10);
        c1.addView(smsInput, slp);

        smsBodyInput = new EditText(this);
        smsBodyInput.setHint("JSON 模板");
        smsBodyInput.setTextSize(13);
        smsBodyInput.setTypeface(Typeface.MONOSPACE);
        smsBodyInput.setTextColor(TEXT_MAIN);
        smsBodyInput.setHintTextColor(TEXT_SUB);
        smsBodyInput.setBackground(roundedBox(INPUT_BG, 10));
        smsBodyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        smsBodyInput.setMinLines(3);
        smsBodyInput.setMaxLines(6);
        smsBodyInput.setGravity(Gravity.TOP);
        smsBodyInput.setText(WebhookPrefs.smsBody(this));
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(-1, -2);
        jlp.topMargin = dp(8);
        c1.addView(smsBodyInput, jlp);

        // 变量按钮：点击插入到 JSON 输入框光标处
        LinearLayout vars = new LinearLayout(this);
        vars.setOrientation(LinearLayout.HORIZONTAL);
        String[] varNames = {"{content}", "{sender}", "{time}", "{date}"};
        for (final String v : varNames) {
            TextView chip = new TextView(this);
            chip.setText(v);
            chip.setTextSize(12);
            chip.setTextColor(BLUE);
            chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(roundedBox(ITEM_BG, 14));
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setOnClickListener(x -> insertVar(v));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.rightMargin = dp(8);
            vlp.topMargin = dp(10);
            vars.addView(chip, vlp);
        }
        c1.addView(vars);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        Button apply = filledBtn("应用");
        apply.setOnClickListener(v -> {
            WebhookPrefs.setSmsUrl(this, smsInput.getText().toString().trim());
            WebhookPrefs.setSmsBody(this, smsBodyInput.getText().toString());
            toast(WebhookPrefs.smsUrl(this).isEmpty() ? "已清空转发地址" : "已保存转发配置");
        });
        btns.addView(apply, new LinearLayout.LayoutParams(-2, dp(40)));
        Button test = textBtn("发送测试", BLUE);
        test.setOnClickListener(v -> sendSmsTest());
        btns.addView(test);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(10);
        c1.addView(btns, blp);
        content.addView(c1);
        return scroll;
    }

    /** 把变量插入 JSON 输入框的光标处 */
    private void insertVar(String var) {
        if (smsBodyInput == null) return;
        int start = Math.max(0, smsBodyInput.getSelectionStart());
        int end = Math.max(start, smsBodyInput.getSelectionEnd());
        smsBodyInput.getText().replace(start, end, var);
    }

    private void sendSmsTest() {
        String url = SmsForwarder.expand(WebhookPrefs.smsUrl(this),
                "10086", "这是一条测试短信", System.currentTimeMillis());
        if (url == null) {
            toast("请先填写并应用转发地址");
            return;
        }
        String tpl = smsBodyInput.getText().toString().trim();
        String json = SmsForwarder.expandRaw(tpl.isEmpty()
                        ? "{\"sender\":\"{sender}\",\"content\":\"{content}\",\"time\":{time},\"date\":\"{date}\"}"
                        : tpl,
                "10086", "这是一条测试短信", System.currentTimeMillis());
        if (json == null) json = "{}";
        toast("发送中…");
        final String u = url;
        final String j = json;
        new Thread(() -> {
            try {
                int code = SmsForwarder.httpPostJson(u, j);
                runOnUiThread(() -> toast(code < 400 ? "测试发送成功（HTTP " + code + "）"
                        : "服务器返回 HTTP " + code));
            } catch (Exception e) {
                runOnUiThread(() -> toast("发送失败：" + e.getMessage()));
            }
        }, "sms-test").start();
    }

    private ScrollView buildBlockPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(6), dp(14), dp(TAB_BAR_H + FAB_SIZE + 16));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== DNS 拦截：状态 + 启动按钮 =====
        LinearLayout c0 = card();
        c0.addView(sectionTitle("DNS 拦截"));
        dnsState = new TextView(this);
        dnsState.setTextSize(13);
        dnsState.setPadding(0, dp(4), 0, 0);
        c0.addView(dnsState);
        content.addView(c0);

        // ===== 订阅管理卡片：输入在上，「添加 / 查询」并排在底部 =====
        LinearLayout c1 = card();
        c1.addView(sectionTitle("订阅管理"));
        subInput = boxInput("订阅链接");
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(4);
        c1.addView(subInput, qlp);
        Button addSub = filledBtn("添加");
        addSub.setOnClickListener(v -> addSub());
        Button querySub = textBtn("查询", BLUE);
        querySub.setOnClickListener(v -> openRulesList(RulesListActivity.MODE_SUBS));
        c1.addView(actionRow(addSub, querySub));
        content.addView(c1);

        // ===== 域名重定向卡片：与订阅管理同构，域名/IP 上下两行 =====
        LinearLayout c2 = card();
        c2.addView(sectionTitle("域名重定向"));
        redDomainInput = boxInput("域名");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        c2.addView(redDomainInput, rlp);
        redIpInput = boxInput("IP");
        LinearLayout.LayoutParams rilp = new LinearLayout.LayoutParams(-1, -2);
        rilp.topMargin = dp(8);
        c2.addView(redIpInput, rilp);
        Button addRed = filledBtn("添加");
        addRed.setOnClickListener(v -> addRedirect());
        Button queryRed = textBtn("查询", BLUE);
        queryRed.setOnClickListener(v -> openRulesList(RulesListActivity.MODE_REDIRECTS));
        c2.addView(actionRow(addRed, queryRed));
        content.addView(c2);

        // ===== 上游 DNS =====
        LinearLayout c3 = card();
        c3.addView(sectionTitle("上游 DNS"));
        dnsInput = boxInput("留空用默认");
        dnsInput.setInputType(InputType.TYPE_CLASS_PHONE);
        dnsInput.setText(WebhookPrefs.dnsUpstream(this));
        Button saveDns = filledBtn("保存");
        saveDns.setOnClickListener(v -> saveDns());
        LinearLayout dRow = new LinearLayout(this);
        dRow.setOrientation(LinearLayout.HORIZONTAL);
        dRow.setGravity(Gravity.CENTER_VERTICAL);
        dRow.addView(dnsInput, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, dp(40));
        dlp.leftMargin = dp(8);
        dRow.addView(saveDns, dlp);
        c3.addView(dRow);
        content.addView(c3);
        return scroll;
    }

    // ------------------------------------------------------------------ 设置页

    private ScrollView buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(6), dp(14), dp(TAB_BAR_H + FAB_SIZE + 16));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        // ===== 通用 =====
        // 消息接收 / DNS 拦截的开关已移到各自页面的卡片标题，这里只留全局项
        LinearLayout c1 = card();
        c1.addView(sectionTitle("启动"));

        NeoSwitch swBoot = newSwitch();
        swBoot.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_BOOT, false));
        swBoot.setOnCheckedChangeListener((b, checked) ->
                WebhookPrefs.set(this, WebhookPrefs.KEY_BOOT, checked));

        c1.addView(switchCell("开机自启", swBoot), new LinearLayout.LayoutParams(-1, -2));
        content.addView(c1);

        // ===== 显示 =====
        LinearLayout c2 = card();
        c2.addView(sectionTitle("显示"));

        NeoSwitch swKeep = newSwitch();
        swKeep.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_KEEP_ON, false));
        swKeep.setOnCheckedChangeListener((b, checked) -> {
            WebhookPrefs.set(this, WebhookPrefs.KEY_KEEP_ON, checked);
            if (checked && !Settings.canDrawOverlays(this)) {
                toast("常亮已开；锁屏球需要「悬浮窗权限」，请在下方权限卡片开启");
            }
            ScreenKeeper.sync(this);
        });

        NeoSwitch swDark = newSwitch();
        swDark.setChecked(WebhookPrefs.get(this, WebhookPrefs.KEY_DARK, false));
        swDark.setOnCheckedChangeListener((b, checked) -> {
            WebhookPrefs.set(this, WebhookPrefs.KEY_DARK, checked);
            pendingPage = currentPage; // recreate 后停在当前页
            recreate();
        });

        LinearLayout g2 = gridRow();
        g2.addView(switchCell("屏幕常亮", swKeep), cellLp());
        g2.addView(switchCell("深色主题", swDark), cellLp());
        c2.addView(g2);
        content.addView(c2);

        // ===== 权限 =====
        // 只放「用户可能在系统里关掉、且系统不会再弹框」的权限：
        // 短信权限在开关打开时自动申请，VPN 授权在启动时自动申请，无需手动引导
        LinearLayout cPerm = card();
        cPerm.addView(sectionTitle("权限"));

        notifBtn = permissionBtn("通知权限");
        notifBtn.setOnClickListener(v -> openNotificationSettings());
        overlayBtn = permissionBtn("悬浮窗权限");
        overlayBtn.setOnClickListener(v -> openOverlaySettings());

        LinearLayout pRow = gridRow();
        pRow.addView(notifBtn, cellLp());
        LinearLayout.LayoutParams p1 = cellLp();
        p1.leftMargin = dp(8);
        pRow.addView(overlayBtn, p1);
        cPerm.addView(pRow);
        content.addView(cPerm);
        return scroll;
    }

    // ------------------------------------------------------------------ 主题与屏幕

    /** 按设置切换黑白主题，并同步状态栏颜色与图标深浅 */
    private void applyTheme() {
        boolean dark = WebhookPrefs.get(this, WebhookPrefs.KEY_DARK, false);
        BG = dark ? DARK_BG : LIGHT_BG;
        CARD = dark ? DARK_CARD : LIGHT_CARD;
        ITEM_BG = dark ? DARK_ITEM : LIGHT_ITEM;
        INPUT_BG = dark ? DARK_INPUT : LIGHT_INPUT;
        TEXT_MAIN = dark ? DARK_MAIN : LIGHT_MAIN;
        TEXT_SUB = dark ? DARK_SUB : LIGHT_SUB;
        RED = dark ? DARK_RED : LIGHT_RED;

        // 沉浸式：状态栏/导航栏透明，内容延伸到系统栏底下（insets 顶开），颜色跟随页面背景
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        View decor = w.getDecorView();
        // 仅延伸布局、不隐藏系统栏（三件套让 compat 桥接保持 edge-to-edge，与下方新式 API 一致）；
        // 不要加旧式 FULLSCREEN/HIDE_NAVIGATION 隐藏 flag——它会把 API 30+ 的
        // decorFitsSystemWindows 桥接翻回 true，状态栏染色失效
        int uiFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT < 30 && !dark) {
            uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(uiFlags);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController ic = decor.getWindowInsetsController();
            if (ic != null) {
                ic.setSystemBarsAppearance(
                        dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }
    }

    // ------------------------------------------------------------------ 头部状态

    private void refreshHeader() {
        boolean blockOn = WebhookPrefs.get(this, WebhookPrefs.KEY_BLOCK, true);
        if (dnsState != null) {
            dnsState.setText(serviceRunning && blockOn
                    ? "运行中 · 拦截 " + BlockVpnService.stateRules
                            + " 条 · 重定向 " + BlockVpnService.stateRedirects + " 条"
                    : "未启动");
            dnsState.setTextColor(serviceRunning && blockOn ? GREEN : TEXT_SUB);
        }
        refreshTabs();
    }

    /** 当前页对应的功能是否已开启（设置页无对应功能，恒为 false） */
    private boolean fabOn() {
        if (fabKey == null) return false;
        if (WebhookPrefs.KEY_SMS.equals(fabKey)) {
            return WebhookPrefs.get(this, WebhookPrefs.KEY_SMS, false);
        }
        return serviceRunning && WebhookPrefs.get(this, fabKey, true);
    }

    /** 圆形启动按钮：未启动米白灰底，启动后绿底白字（两主题一致，米白底自带对比） */
    private void setFab(TextView b, boolean on) {
        if (b == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? GREEN : 0xFFF0EDE6);
        b.setBackground(g);
        b.setTextColor(on ? Color.WHITE : 0xFF7A8699);
    }

    private void refreshTabs() {
        styleTab(tabMessages, currentPage == 0);
        styleTab(tabSms, currentPage == 1);
        styleTab(tabBlock, currentPage == 2);
        styleTab(tabSettings, currentPage == 3);
        // 圆形按钮只对应当前页功能：消息 / 短信 / DNS
        if (fab == null) return;
        if (currentPage == 0) {
            fabKey = WebhookPrefs.KEY_WEBHOOK;
            fab.setText("消息");
        } else if (currentPage == 1) {
            fabKey = WebhookPrefs.KEY_SMS;
            fab.setText("短信");
        } else if (currentPage == 2) {
            fabKey = WebhookPrefs.KEY_BLOCK;
            fab.setText("DNS");
        } else {
            fabKey = null;
        }
        // 设置页没有对应功能：整张圆卡隐掉（它已经不占标签行，不需要留位）
        fab.setVisibility(fabKey == null ? View.GONE : View.VISIBLE);
        setFab(fab, fabOn());
    }

    /** 标签纯文字：选中变蓝加粗，不加底色不加边框（整屏只有一块背景） */
    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? BLUE : TEXT_SUB);
        tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** 保存自定义 DNS：设置单一上游，运行中通过 reload 立即生效 */
    private void saveDns() {
        String v = dnsInput.getText().toString().trim();
        WebhookPrefs.setDnsUpstream(this, v);
        toast(v.isEmpty() ? "已恢复默认 DNS" : "DNS 已设为 " + v);
        if (serviceRunning) sendReload();
    }

    private void refreshMessageState() {
        if (portInput == null) return;
        if (!WebhookPrefs.get(this, WebhookPrefs.KEY_WEBHOOK, true)) {
            addressView.setText("消息接收已关闭");
            addressView.setTextColor(TEXT_SUB);
            portHint.setVisibility(View.GONE);
            return;
        }
        if (!portInput.hasFocus()) {
            portInput.setText(String.valueOf(WebhookPrefs.port(this)));
        }
        String ip = localIpv4();
        int port = WebhookPrefs.port(this);
        if (ip == null) {
            addressView.setText("未连接局域网");
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
        if (notifBtn == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean notify = nm != null && nm.areNotificationsEnabled();
        setPermState(notifBtn, notify);
        setPermState(overlayBtn, Settings.canDrawOverlays(this));
    }

    // ------------------------------------------------------------------ 订阅 / 重定向

    /** 打开订阅 / 重定向清单独立页面 */
    private void openRulesList(String mode) {
        startActivity(new Intent(this, RulesListActivity.class).putExtra(RulesListActivity.EXTRA_MODE, mode));
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
        download(s, true, true);
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

    /** 短信转发独立开关：只改偏好并申请权限，不依赖后台服务 */
    private void toggleSms() {
        boolean on = !WebhookPrefs.get(this, WebhookPrefs.KEY_SMS, false);
        WebhookPrefs.set(this, WebhookPrefs.KEY_SMS, on);
        refreshHeader();
        if (on) {
            if (checkSelfPermission("android.permission.RECEIVE_SMS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestSmsPermission();
            } else {
                toast("短信转发已开启");
            }
        } else {
            toast("短信转发已关闭");
        }
    }

    /**
     * 圆形按钮：只切换自己对应的功能。服务是消息与 DNS 共用的，
     * 关掉一个而另一个还开着时不停服务，两个都关才停。
     */
    private void toggleFeature(String key) {
        boolean on = serviceRunning && WebhookPrefs.get(this, key, true);
        if (on) {
            WebhookPrefs.set(this, key, false);
            String other = WebhookPrefs.KEY_BLOCK.equals(key)
                    ? WebhookPrefs.KEY_WEBHOOK : WebhookPrefs.KEY_BLOCK;
            if (WebhookPrefs.get(this, other, true)) {
                sendReload();
            } else {
                serviceRunning = false;
                startService(new Intent(this, BlockVpnService.class)
                        .setAction(BlockVpnService.ACTION_STOP));
            }
        } else {
            WebhookPrefs.set(this, key, true);
            if (!serviceRunning) {
                startServiceFlow();
            } else if (WebhookPrefs.KEY_BLOCK.equals(key) && VpnService.prepare(this) != null) {
                // 服务已在跑（仅消息接收），此时开 DNS 必须先拿 VPN 授权，
                // 否则 establish() 会失败，DNS 会静默地不生效
                startActivityForResult(VpnService.prepare(this), REQ_VPN);
            } else {
                sendReload();
            }
        }
        refreshHeader();
    }

    /** 启动后台服务：先确认通知权限（前台服务通知），再走 VPN 授权 */
    private void startServiceFlow() {
        pendingStart = true;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            // 已申请过且系统不再弹框，直接引导到系统设置，不再静默失败
            if (WebhookPrefs.get(this, WebhookPrefs.KEY_ASKED_NOTIF, false)
                    && !shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS")) {
                pendingStart = false;
                openNotificationSettings();
                toast("需要通知权限，请在系统设置中开启");
                return;
            }
            WebhookPrefs.set(this, WebhookPrefs.KEY_ASKED_NOTIF, true);
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        startFlow();
    }

    private void startFlow() {
        pendingStart = false;
        // 只启动已打开的功能：拦截关闭时不请求 VPN 授权
        if (WebhookPrefs.get(this, WebhookPrefs.KEY_BLOCK, true)) {
            Intent i = VpnService.prepare(this);
            if (i != null) {
                startActivityForResult(i, REQ_VPN);
                return;
            }
        }
        serviceRunning = true; // 先变色再等服务确认
        refreshHeader();
        startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_START));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_NOTIF && pendingStart) startFlow();
        if (code == REQ_SMS && res.length > 0
                && res[0] != PackageManager.PERMISSION_GRANTED) {
            // 未获授权则回滚，避免显示为已启用但实际不工作
            WebhookPrefs.set(this, WebhookPrefs.KEY_SMS, false);
            refreshHeader();
            toast("未授予短信权限，短信转发不会生效");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) {
                serviceRunning = true; // 先变色再等服务确认
                refreshHeader();
                startService(new Intent(this, BlockVpnService.class).setAction(BlockVpnService.ACTION_START));
            } else {
                // 拒授权：DNS 无法建 TUN，回滚拦截开关让界面如实反映；
                // 消息接收不依赖 VPN，开着就继续以仅接收模式启动
                WebhookPrefs.set(this, WebhookPrefs.KEY_BLOCK, false);
                pendingStart = false;
                if (WebhookPrefs.get(this, WebhookPrefs.KEY_WEBHOOK, true)) {
                    serviceRunning = true;
                    startService(new Intent(this, BlockVpnService.class)
                            .setAction(BlockVpnService.ACTION_START));
                    toast("未授权 VPN，DNS 拦截已关闭，仅启动消息接收");
                } else {
                    toast("未授权 VPN，已取消启动");
                }
                refreshHeader();
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

    /** 拟物风格开关：白天浅灰底 / 黑夜深灰底，各自配高光与阴影色 */
    private NeoSwitch newSwitch() {
        boolean dark = WebhookPrefs.get(this, WebhookPrefs.KEY_DARK, false);
        return dark
                ? new NeoSwitch(this, 0xFF242E3E, 0xFF34415A, 0xFF141A24, 0xFF303C50)
                : new NeoSwitch(this, 0xFFEDF1F7, 0xFFFFFFFF, 0xFFC3CBD9, 0xFFF7FAFF);
    }

    private LinearLayout switchCell(String label, NeoSwitch sw) {
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

    /** 权限小按钮：上为名称，下为状态（已开启 / 去开启） */
    private TextView permissionBtn(String label) {
        TextView btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(roundedBox(ITEM_BG, 12));
        btn.setPadding(dp(8), dp(10), dp(8), dp(10));
        btn.setClickable(true);
        btn.setTag(label);
        return btn;
    }

    /** 更新权限按钮的状态文案与颜色 */
    private void setPermState(TextView btn, boolean granted) {
        if (btn == null) return;
        String label = (String) btn.getTag();
        btn.setText(label + "\n" + (granted ? "已开启" : "去开启"));
        btn.setTextColor(granted ? TEXT_MAIN : RED);
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

    /** 卡片标题行：左侧蓝色竖条 + 标题 */
    private LinearLayout sectionTitle(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(8));
        View bar = new View(this);
        bar.setBackground(roundedBox(BLUE, 2));
        row.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(16)));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(TEXT_MAIN);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
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

    /** 卡片底部操作行：两个等宽按钮并排（主操作实心、次操作文字） */
    private LinearLayout actionRow(Button primary, Button secondary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(primary, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        sp.leftMargin = dp(8);
        row.addView(secondary, sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
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

    private void requestSmsPermission() {
        if (checkSelfPermission("android.permission.RECEIVE_SMS")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // 已申请过且系统不再弹框（用户选了「不再询问」），引导到系统设置
        if (WebhookPrefs.get(this, WebhookPrefs.KEY_ASKED_SMS, false)
                && !shouldShowRequestPermissionRationale("android.permission.RECEIVE_SMS")) {
            openAppSettings();
            return;
        }
        WebhookPrefs.set(this, WebhookPrefs.KEY_ASKED_SMS, true);
        requestPermissions(new String[]{"android.permission.RECEIVE_SMS"}, REQ_SMS);
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("无法打开应用设置");
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
        t.setText("检测到崩溃日志");
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
