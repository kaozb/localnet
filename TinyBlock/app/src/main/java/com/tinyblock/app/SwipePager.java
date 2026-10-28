package com.tinyblock.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 轻量双页容器：整屏宽度分页，松手必定吸附到某一页，不引入任何第三方依赖 */
public class SwipePager extends HorizontalScrollView {

    public interface OnPageChangeListener {
        void onPageSelected(int index);
    }

    private static final int FLING_SPEED = 900; // px/s，超过视为有意翻页

    private int current;
    private int pageCount;
    private ViewGroup pagesContainer;
    private OnPageChangeListener listener;

    private VelocityTracker tracker;
    private int startPage;

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
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) beginDrag(ev);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (tracker == null) beginDrag(ev);
            tracker.addMovement(ev);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // 不再交给父类处理 UP：避免 fling 动画把页面停在中间
            if (tracker != null) tracker.addMovement(ev);
            int target = startPage;
            if (action == MotionEvent.ACTION_UP && pageCount > 0) {
                int width = Math.max(1, getWidth());
                if (getScrollX() > startPage * width + width / 2) {
                    target = startPage + 1;
                } else if (getScrollX() < startPage * width - width / 2) {
                    target = startPage - 1;
                }
                float vx = 0;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000, 8000);
                    vx = tracker.getXVelocity();
                }
                if (target == startPage && Math.abs(vx) > FLING_SPEED) {
                    // vx < 0 表示手指向左滑 → 进入下一页
                    target = vx < 0 ? startPage + 1 : startPage - 1;
                }
            }
            if (tracker != null) {
                tracker.recycle();
                tracker = null;
            }
            setCurrentItem(target, true);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    private void beginDrag(MotionEvent ev) {
        startPage = pageIndexNow();
        if (tracker != null) tracker.recycle();
        tracker = VelocityTracker.obtain();
        tracker.addMovement(ev);
    }

    private int pageIndexNow() {
        int width = Math.max(1, getWidth());
        return Math.round((float) getScrollX() / width);
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
