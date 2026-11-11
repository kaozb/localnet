package com.tinyblock.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模拟锁屏：全屏纯黑 Activity，点屏幕调出「解锁」，双击「解锁」解除。
 * <p>
 * 必须用 Activity 而不是悬浮窗：TYPE_APPLICATION_OVERLAY 层级低于状态栏，
 * 遮罩盖不住状态栏的时钟与电量；只有 Activity 窗口才能收起系统栏。
 * 启动它依赖「显示在其他应用上层」权限——持有该权限的应用可从后台启动 Activity，
 * 所以在别的应用里点悬浮球也能进入黑屏。
 */
public class BlackActivity extends Activity {

    private TextView unlock;
    /** 上一次点击「解锁」的时刻，双击窗口 600ms */
    private long lastUnlockTap;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 背光压到最低省电（AMOLED 纯黑像素不发光），窗口销毁自动恢复
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = 0.01f;
        w.setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnClickListener(v -> unlock.setVisibility(
                unlock.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        unlock = new TextView(this);
        unlock.setText("解锁");
        unlock.setTextColor(Color.WHITE);
        unlock.setTextSize(14);
        unlock.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0x66FFFFFF);
        unlock.setBackground(bg);
        unlock.setVisibility(View.GONE);
        // 双击才解锁：第一次点击只提示，两次间隔超过 600ms 重新计数，防单次误触
        unlock.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastUnlockTap > 600) {
                lastUnlockTap = now;
                toast("再点一次解锁");
                return;
            }
            lastUnlockTap = 0;
            finish();
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(96), dp(44));
        blp.gravity = Gravity.CENTER;
        root.addView(unlock, blp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScreenKeeper.setBlackVisible(true);
        hideBars();
    }

    /** 用 onStop 而不是 onPause：权限弹窗等瞬时界面盖上来时，黑屏还在，球不能又浮回去 */
    @Override
    protected void onStop() {
        super.onStop();
        ScreenKeeper.setBlackVisible(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    /** 屏蔽返回，防止绕过模拟锁屏 */
    @Override
    public void onBackPressed() {
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    /** 收起系统栏：黑屏必须连状态栏一起盖住，否则时钟电量还亮着 */
    private void hideBars() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = decor.getWindowInsetsController();
            if (ic == null) return;
            ic.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            ic.hide(WindowInsets.Type.systemBars());
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
