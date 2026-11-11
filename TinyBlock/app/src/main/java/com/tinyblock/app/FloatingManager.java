package com.tinyblock.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 悬浮通知条：显示在状态栏下方，可点击确认 */
public class FloatingManager {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onAck;
    private final float density;

    private LinearLayout card;
    private TextView titleView;
    private TextView bodyView;
    private TextView timeView;
    private TextView ackView;
    private boolean attached;

    private final Runnable hideRunnable = this::hideNow;

    public FloatingManager(Context context, Runnable onAck) {
        this.ctx = context.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.onAck = onAck;
        this.density = this.ctx.getResources().getDisplayMetrics().density;
    }

    public void show(String title, String text, String time, long durationMs, boolean showAck) {
        if (!WebhookPrefs.get(ctx, WebhookPrefs.KEY_FLOAT, false)) return;
        if (!Settings.canDrawOverlays(ctx)) return;
        main.post(() -> showOnMain(title, text, time, durationMs, showAck));
    }

    private void showOnMain(String title, String text, String time, long durationMs, boolean showAck) {
        if (card == null) build();
        titleView.setText(title);
        bodyView.setText(text);
        timeView.setText(time);
        ackView.setVisibility(showAck ? View.VISIBLE : View.GONE);

        main.removeCallbacks(hideRunnable);
        if (!attached) {
            try {
                wm.addView(card, params());
                attached = true;
            } catch (Exception ignored) {
                return;
            }
        }
        card.setAlpha(0f);
        card.setTranslationY(-dp(8));
        card.animate().alpha(1f).translationY(0f).setDuration(180).start();
        main.postDelayed(hideRunnable, Math.max(1200, durationMs));
    }

    public void dismiss() {
        main.removeCallbacks(hideRunnable);
        main.post(this::hideNow);
    }

    public void shutdown() {
        main.removeCallbacks(hideRunnable);
        main.post(() -> {
            if (card != null) {
                try {
                    if (attached) wm.removeView(card);
                } catch (Exception ignored) {
                }
                attached = false;
                card = null;
            }
        });
    }

    private void hideNow() {
        if (card == null || !attached) return;
        card.animate().alpha(0f).translationY(-dp(8)).setDuration(140)
                .withEndAction(() -> {
                    try {
                        if (attached) wm.removeView(card);
                    } catch (Exception ignored) {
                    }
                    attached = false;
                }).start();
    }

    private WindowManager.LayoutParams params() {
        int width = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(32), dp(480));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(48);
        return lp;
    }

    private void build() {
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF217233B);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x4D5B8DEF);
        card.setBackground(bg);
        card.setElevation(dp(10));
        card.setClickable(true);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(ctx);
        titleView.setTextColor(0xFFA9C7FF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleView.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(titleView, tlp);

        ackView = new TextView(ctx);
        ackView.setText("确认");
        ackView.setTextColor(0xFF6DA8FF);
        ackView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ackView.setPadding(dp(10), dp(2), dp(2), dp(2));
        ackView.setOnClickListener(v -> {
            if (onAck != null) onAck.run();
            dismiss();
        });
        top.addView(ackView);

        card.addView(top);

        bodyView = new TextView(ctx);
        bodyView.setTextColor(Color.WHITE);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bodyView.setMaxLines(3);
        bodyView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bodyView.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        card.addView(bodyView, blp);

        timeView = new TextView(ctx);
        timeView.setTextColor(0xFF8FA4C2);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams tmlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tmlp.topMargin = dp(6);
        card.addView(timeView, tmlp);

        card.setOnClickListener(v -> dismiss());
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
