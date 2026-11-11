package com.tinyblock.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 模拟锁屏：全屏纯黑 Activity。
 * <p>
 * 必须用 Activity 而不是悬浮窗：TYPE_APPLICATION_OVERLAY 层级低于状态栏，
 * 遮罩盖不住状态栏的时钟与电量；只有 Activity 窗口才能收起系统栏。
 * 启动它依赖「显示在其他应用上层」权限——持有该权限的应用可从后台启动 Activity，
 * 所以在别的应用里点悬浮球也能进入黑屏。
 * <p>
 * 遮盖视频小窗：画中画窗口的层级高于普通 Activity 但低于「全屏型窗口」。
 * 锁屏期间窗口挂 FLAG_FULLSCREEN（锁死全屏、无视子窗口占用的 inset）并从
 * LAYOUT_IN_SCREEN / LAYOUT_NO_LIMITS 改成 LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES，
 * 画中画小窗就盖不到遮罩之上；FLAG_FULLSCREEN 只能窗口创建前挂，这里在主题里配置。
 * <p>
 * 解锁无屏幕内按钮，两种手势等价：
 * 下拉通知栏——沉浸模式下滑入的是透明瞬时栏，拉到顶会盖住本窗口并夺走窗口焦点；
 * 底部上滑手势——系统手势导航接管，切走后窗口同样失去焦点。
 * 两路在 onWindowFocusChanged(false) 汇合 finish()，轻微下拉的半透明遮罩
 * 不抢焦点、松手自动收回，不会误触。
 * <p>
 * 启动静默期：onCreate 到沉浸标记生效之间系统会回调一次「无栏可见」，
 * 窗口焦点在启动转场时也会 true→false 抖动——两路信号在头几百毫秒必然
 * 各误触发一次，黑屏刚点开就自己退出（暗一下又亮）。故启动后 800ms 内
 * 解锁信号一律忽略。
 */
public class BlackActivity extends Activity {

    /** 解锁信号的生效时刻（onCreate 起算），之前的信号是启动过渡的抖动 */
    private long armedAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 背光压到最低省电（AMOLED 纯黑像素不发光），窗口销毁自动恢复
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = 0.01f;
        // 让窗口自己铺满整块屏幕（含刘海/挖孔区域），画中画等子窗口盖不上来
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
        setContentView(new FrameLayout(this));
        armedAt = SystemClock.elapsedRealtime() + 800;
        listenUnlock();
    }

    private boolean armed() {
        return SystemClock.elapsedRealtime() >= armedAt;
    }

    /**
     * 手势解锁信号源：系统栏从隐藏变可见的瞬间。全版本可用
     * （API 30+ 标记过时但仍工作），等价换 WindowInsets 回调要按版本写两套，不值。
     */
    private void listenUnlock() {
        // 只盯状态栏+导航栏这两个标记：全屏沉浸下二者都清零，滑出瞬时栏即触发；
        // 窗口自身的 inset 变化（刘海避让等）不动这两个标记，不会误触
        final int bars = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & bars) != bars && armed()) finish();
        });
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

    /**
     * 通知栏拉满盖住窗口、底部上滑手势切走，都会走到这里——第二路解锁信号，
     * 与系统栏可见回调互为兜底（各家系统手势路径不一，双保险）。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
        else if (armed()) finish();
    }

    /** 屏蔽返回，防止绕过模拟锁屏 */
    @Override
    public void onBackPressed() {
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
}
