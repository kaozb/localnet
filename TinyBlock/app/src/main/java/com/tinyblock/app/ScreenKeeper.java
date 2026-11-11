package com.tinyblock.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 屏幕常亮与模拟锁屏，状态挂在静态字段上，离开本应用、Activity 销毁后依然生效：
 * 常亮用 WakeLock（系统级，无需额外权限）；锁屏球用系统级悬浮窗，
 * 需要「显示在其他应用上层」权限，无权限时只保常亮、不放锁屏球。
 * 黑屏本体是 {@link BlackActivity}——悬浮窗层级低于状态栏，盖不住时钟电量。
 */
public final class ScreenKeeper {

    private static PowerManager.WakeLock wake;
    private static WindowManager wm;
    private static TextView ball;
    /** 锁屏球的窗口参数：拖动靠改它的 x/y 实现全屏移动 */
    private static WindowManager.LayoutParams ballLp;
    private static boolean ballAttached;
    /** 黑屏 Activity 是否在显示 */
    private static boolean blackOn;

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
        if (!Settings.canDrawOverlays(app)) return;
        if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        if (ball == null) {
            ballLp = ballParams(app);
            ball = buildBall(app);
        }
        // 球可能因黑屏被摘掉过，这里无条件调一次让 attachBall 按当前状态挂回去
        attachBall(true);
    }

    private static void stop() {
        if (wake != null) {
            try {
                wake.release();
            } catch (Exception ignored) {
            }
            wake = null;
        }
        attachBall(false);
        ball = null;
    }

    /**
     * 黑屏期间把悬浮球从窗口里摘掉：GONE 只是不画，44dp 的悬浮窗仍在最上层，
     * 会吞掉那一片的点击（黑屏解锁靠下拉通知栏/底部上滑手势，不能被球挡住）。
     */
    static void setBlackVisible(boolean on) {
        blackOn = on;
        attachBall(!on);
    }

    private static void attachBall(boolean attach) {
        if (ball == null || wm == null) return;
        // 黑屏期间不挂球（服务重启会重新 sync，别把球又浮到黑屏上面）
        boolean want = attach && !blackOn;
        if (want == ballAttached) return;
        try {
            if (want) wm.addView(ball, ballLp);
            else wm.removeView(ball);
            ballAttached = want;
        } catch (Exception ignored) {
            // 挂载失败（权限被撤、窗口已存在）不记状态，下次 sync 重试
        }
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 44dp 圆形深蓝底，无边框，可拖到屏幕任意位置；点按进入黑屏 */
    private static TextView buildBall(Context c) {
        TextView btn = new TextView(c);
        btn.setText("锁屏");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(10);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xB317233B);
        btn.setBackground(bg);

        WindowManager.LayoutParams lp = ballLp;
        float[] base = new float[2];
        int[] origin = new int[2];
        boolean[] dragged = new boolean[1];
        int slop = ViewConfiguration.get(c).getScaledTouchSlop();
        btn.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    base[0] = ev.getRawX();
                    base[1] = ev.getRawY();
                    origin[0] = lp.x;
                    origin[1] = lp.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - base[0], dy = ev.getRawY() - base[1];
                    if (!dragged[0] && Math.hypot(dx, dy) < slop) return true;
                    dragged[0] = true;
                    // 球是独立悬浮窗，移动视图超出窗口会被裁掉（表现为只能在局部拖动），
                    // 必须改窗口坐标：拖动时直接把 lp.x / lp.y 挪过去
                    int sw = c.getResources().getDisplayMetrics().widthPixels;
                    int sh = c.getResources().getDisplayMetrics().heightPixels;
                    int w = lp.width, h = lp.height;
                    lp.x = Math.max(0, Math.min(sw - w, Math.round(origin[0] + dx)));
                    lp.y = Math.max(0, Math.min(sh - h, Math.round(origin[1] + dy)));
                    if (wm != null) {
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) {
                        // 持有「显示在其他应用上层」权限的应用不受后台启动 Activity 限制，
                        // 所以在别的应用里点球也能进黑屏
                        try {
                            c.startActivity(new Intent(c, BlackActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
            }
            return false;
        });
        return btn;
    }

    /** 用左上角定位，初始停在右下角偏上（TOP|START 才能用 lp.x / lp.y 做全屏拖动） */
    private static WindowManager.LayoutParams ballParams(Context c) {
        int size = dp(c, 44);
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        int sh = c.getResources().getDisplayMetrics().heightPixels;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, sw - size - dp(c, 16));
        lp.y = Math.max(0, sh - size - dp(c, 160));
        return lp;
    }
}
