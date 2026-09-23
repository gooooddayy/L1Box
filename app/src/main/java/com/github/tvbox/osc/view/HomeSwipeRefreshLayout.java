package com.github.tvbox.osc.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * 首页下拉刷新容器。
 *
 * ViewPager 不是 NestedScrollingChild，RecyclerView 的嵌套滚动事件传不到 SwipeRefreshLayout，
 * 会导致列表已经向下滚动了仍然能触发下拉刷新。这里把「子 View 是否还能向上滚」的判断
 * 交给当前页面的列表，保证只有列表滚到顶部时下拉才生效。
 */
public class HomeSwipeRefreshLayout extends SwipeRefreshLayout {

    public interface ChildScrollCheck {
        /** true 表示内容还能继续向上滚动（即没到顶部），此时不应触发下拉刷新 */
        boolean canScrollUp();
    }

    private ChildScrollCheck childScrollCheck;

    private float lastInterceptX;
    private float lastInterceptY;

    public HomeSwipeRefreshLayout(Context context) {
        super(context);
    }

    public HomeSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setChildScrollCheck(ChildScrollCheck check) {
        this.childScrollCheck = check;
    }

    @Override
    public boolean canChildScrollUp() {
        if (childScrollCheck != null && childScrollCheck.canScrollUp()) {
            return true;
        }
        return super.canChildScrollUp();
    }

    /**
     * 横向滑动时立即放行给子 View（分类 ViewPager / 分类 tab 栏的左右切换），
     * 不让 SwipeRefreshLayout 的纵向手势判定延迟或吞掉横向事件，解决分类栏左右滑动卡顿。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastInterceptX = ev.getX();
                lastInterceptY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - lastInterceptX;
                float dy = ev.getY() - lastInterceptY;
                // 明显横向滑动（且未被判定为纵向下拉）时，不拦截，交还给 ViewPager
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 20) {
                    return false;
                }
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }
}
