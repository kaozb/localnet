package com.tinyblock.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 屏幕常亮与模拟锁屏，状态挂在静态字段上，离开本应用、Activity 销毁后依然生效：
 * 常亮用 WakeLock（系统级，无需额外权限）；锁屏球与黑遮罩用系统级悬浮窗，
 * 需要「显示在其他应用上层」权限，无权限时只保常亮、不放锁屏球。
 */
public final class ScreenKeeper {

    private static PowerManager.WakeLock wake;
    private static WindowManager wm;
    private static TextView ball;
    private static FrameLayout black;

    private ScreenKeeper() {
    }

    /** 按设置开启 / 关闭，可重复调用 */
    public static void sync(Context c) {
        if (WebhookPrefs.get(c, WebhookPrefs.KEY_KEEP_ON, false)) start(c);
        else stop();
    }

    private static void start(Context c) {
        if (wake == null) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // SCREEN_BRIGHT_WAKE_LOCK 已废弃，但仍是唯一不依赖前台窗口的公开 API：
                // Activity 的 FLAG_KEEP_SCREEN_ON 只在自家窗口在前台时有效，切到别的
                // 应用就失效，做不到「所有地方都生效」
                wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "TinyBlock:keepOn");
                wake.setReferenceCounted(false);
                try {
                    wake.acquire();
                } catch (Exception ignored) {
                    wake = null;
                }
            }
        }
        Context app = c.getApplicationContext();
        if (ball != null || !Settings.canDrawOverlays(app)) return;
        if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        ball = buildBall(app);
        try {
            wm.addView(ball, ballParams(app));
        } catch (Exception e) {
            ball = null;
        }
    }

    private static void stop() {
        hideBlack();
        if (wake != null) {
            try {
                wake.release();
            } catch (Exception ignored) {
            }
            wake = null;
        }
        if (ball != null && wm != null) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
        }
        ball = null;
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 44dp 圆形深蓝底 + 浅蓝描边，可拖动；点按切换黑屏 */
    private static TextView buildBall(Context c) {
        TextView btn = new TextView(c);
        btn.setText("锁屏");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(10);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xB317233B);
        bg.setStroke(dp(c, 1), 0x806DA8FF);
        btn.setBackground(bg);
        btn.setElevation(dp(c, 6));

        float[] base = new float[2];
        float[] start = new float[2];
        boolean[] dragged = new boolean[1];
        int slop = ViewConfiguration.get(c).getScaledTouchSlop();
        btn.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    base[0] = ev.getRawX();
                    base[1] = ev.getRawY();
                    start[0] = v.getTranslationX();
                    start[1] = v.getTranslationY();
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - base[0], dy = ev.getRawY() - base[1];
                    if (!dragged[0] && Math.hypot(dx, dy) < slop) return true;
                    dragged[0] = true;
                    int sw = c.getResources().getDisplayMetrics().widthPixels;
                    int sh = c.getResources().getDisplayMetrics().heightPixels;
                    float tx = Math.max(-v.getLeft(), Math.min(
                            sw - v.getWidth() - v.getLeft(), start[0] + dx));
                    float ty = Math.max(-v.getTop(), Math.min(
                            sh - v.getHeight() - v.getTop(), start[1] + dy));
                    v.setTranslationX(tx);
                    v.setTranslationY(ty);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) {
                        if (black == null) showBlack(c);
                        else hideBlack();
                    }
                    return true;
            }
            return false;
        });
        return btn;
    }

    private static WindowManager.LayoutParams ballParams(Context c) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(c, 44), dp(c, 44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = dp(c, 16);
        lp.y = dp(c, 160);
        return lp;
    }

    /** 全屏纯黑遮罩：盖住其他应用（含画中画/小窗）；点遮罩显隐「解锁」，点「解锁」解除 */
    private static void showBlack(Context c) {
        if (black != null || wm == null) return;
        FrameLayout overlay = new FrameLayout(c);
        overlay.setBackgroundColor(0xFF000000);

        TextView btn = new TextView(c);
        btn.setText("解锁");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(c, 24));
        bg.setStroke(dp(c, 1), 0x66FFFFFF);
        btn.setBackground(bg);
        btn.setVisibility(View.GONE);
        btn.setOnClickListener(v -> hideBlack());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(c, 96), dp(c, 44));
        blp.gravity = Gravity.CENTER;
        overlay.addView(btn, blp);

        overlay.setOnClickListener(v ->
                btn.setVisibility(btn.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        // 黑屏期间把背光压到最低省电（AMOLED 纯黑像素不发光），随遮罩移除自动恢复
        lp.screenBrightness = 0.01f;
        try {
            wm.addView(overlay, lp);
        } catch (Exception e) {
            return;
        }
        black = overlay;
        if (ball != null) ball.setVisibility(View.GONE);
    }

    private static void hideBlack() {
        if (black != null && wm != null) {
            try {
                wm.removeView(black);
            } catch (Exception ignored) {
            }
        }
        black = null;
        if (ball != null) ball.setVisibility(View.VISIBLE);
    }
}
