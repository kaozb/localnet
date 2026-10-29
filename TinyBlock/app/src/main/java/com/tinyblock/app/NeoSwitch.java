package com.tinyblock.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.CompoundButton;

/**
 * 拟物（Neumorphism）风格开关：同色底 + 左上高光 / 右下阴影的凸起外观，
 * 按下时阴影翻转成内凹。API 与系统 Switch 一致（继承 CompoundButton）。
 */
public class NeoSwitch extends CompoundButton {

    private static final int BLUE = 0xFF1A73E8;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int baseColor;    // 轨道底色
    private final int lightColor;   // 高光色（左上）
    private final int darkColor;    // 阴影色（右下）
    private final int knobOff;      // 关闭时滑块色

    private float progress;         // 0 = 关，1 = 开
    private ValueAnimator animator;

    private final float density;
    private final float shadowBlur;
    private final float shadowOffset;
    private final float trackInset;

    public NeoSwitch(Context c, int baseColor, int lightColor, int darkColor, int knobOff) {
        super(c);
        this.baseColor = baseColor;
        this.lightColor = lightColor;
        this.darkColor = darkColor;
        this.knobOff = knobOff;
        density = c.getResources().getDisplayMetrics().density;
        shadowBlur = 3 * density;
        shadowOffset = 1.5f * density;
        trackInset = 5 * density;
        setButtonDrawable(null);
        // 双色阴影需要软件渲染
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (56 * density);
        int h = (int) (34 * density);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = isChecked() != checked;
        super.setChecked(checked);
        // 初次设置（初始化）直接到位，避免从关闭态滑开
        if (changed) {
            if (getWidth() == 0) {
                progress = checked ? 1f : 0f;
                invalidate();
            } else {
                animateTo(checked ? 1f : 0f);
            }
        }
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(160);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float tl = trackInset;
        float top = trackInset;
        float rr = w - trackInset;
        float bottom = h - trackInset;
        float trackR = (bottom - top) / 2f;
        float knobR = trackR - 1 * density;
        boolean pressed = isPressed();

        // 轨道：默认凸起（左上高光、右下阴影），按下翻转为内凹
        rect.set(tl, top, rr, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(baseColor);
        paint.setShadowLayer(shadowBlur, pressed ? shadowOffset : -shadowOffset,
                pressed ? shadowOffset : -shadowOffset, lightColor);
        canvas.drawRoundRect(rect, trackR, trackR, paint);
        paint.setShadowLayer(shadowBlur, pressed ? -shadowOffset : shadowOffset,
                pressed ? -shadowOffset : shadowOffset, darkColor);
        canvas.drawRoundRect(rect, trackR, trackR, paint);
        paint.clearShadowLayer();
        canvas.drawRoundRect(rect, trackR, trackR, paint);

        // 滑块：跟随开关状态左右移动，开启时为蓝色
        float left = tl + knobR;
        float right = rr - knobR;
        float cx = left + (right - left) * progress;
        float cy = (top + bottom) / 2f;
        paint.setColor(isChecked() ? BLUE : knobOff);
        paint.setShadowLayer(shadowBlur, -shadowOffset, -shadowOffset, lightColor);
        canvas.drawCircle(cx, cy, knobR, paint);
        paint.setShadowLayer(shadowBlur, shadowOffset, shadowOffset, darkColor);
        canvas.drawCircle(cx, cy, knobR, paint);
        paint.clearShadowLayer();
        canvas.drawCircle(cx, cy, knobR, paint);
    }
}
