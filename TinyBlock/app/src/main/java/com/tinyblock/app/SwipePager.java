package com.tinyblock.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 轻量双页容器：整屏宽度分页 + 松手吸附，不引入任何第三方依赖 */
public class SwipePager extends HorizontalScrollView {

    public interface OnPageChangeListener {
        void onPageSelected(int index);
    }

    private int current;
    private int pageCount;
    private ViewGroup pagesContainer;
    private OnPageChangeListener listener;

    public SwipePager(Context context) {
        this(context, null);
    }

    public SwipePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setSmoothScrollingEnabled(true);
    }

    public void setPages(View left, View right) {
        removeAllViews();
        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        // HorizontalScrollView 只允许一个直接子 View：两页放进一个水平容器里并排
        LinearLayout pages = new LinearLayout(getContext());
        pages.setOrientation(LinearLayout.HORIZONTAL);
        pages.addView(left, new LinearLayout.LayoutParams(width, LayoutParams.MATCH_PARENT));
        pages.addView(right, new LinearLayout.LayoutParams(width, LayoutParams.MATCH_PARENT));
        pagesContainer = pages;
        addView(pages, new FrameLayout.LayoutParams(width, LayoutParams.MATCH_PARENT));
        pageCount = 2;
        post(() -> {
            if (getWidth() > 0) {
                resizePages(getWidth());
                scrollTo(current * getWidth(), 0);
            }
        });
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        this.listener = l;
    }

    public int getCurrentItem() {
        return current;
    }

    public void setCurrentItem(int index, boolean smooth) {
        if (pageCount <= 0) return;
        int target = Math.max(0, Math.min(pageCount - 1, index));
        int width = getWidth();
        if (width <= 0) {
            width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        }
        current = target;
        if (smooth) smoothScrollTo(target * width, 0);
        else scrollTo(target * width, 0);
        if (listener != null) listener.onPageSelected(target);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && pageCount > 0) {
            resizePages(w);
            scrollTo(current * w, 0);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            snapToNearest();
        }
        return handled;
    }

    private void snapToNearest() {
        int width = getWidth();
        if (width <= 0 || pageCount <= 0) return;
        int target = Math.round((float) getScrollX() / width);
        target = Math.max(0, Math.min(pageCount - 1, target));
        setCurrentItem(target, true);
    }

    private void resizePages(int width) {
        ViewGroup container = pagesContainer;
        if (container == null) return;
        ViewGroup.LayoutParams clp = container.getLayoutParams();
        if (clp != null && clp.width != width) {
            clp.width = width;
            container.setLayoutParams(clp);
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp.width != width) {
                lp.width = width;
                child.setLayoutParams(lp);
            }
        }
    }
}
