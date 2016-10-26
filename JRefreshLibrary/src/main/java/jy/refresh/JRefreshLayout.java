package jy.refresh;

import android.content.Context;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.NestedScrollingChild;
import android.support.v4.view.NestedScrollingChildHelper;
import android.support.v4.view.NestedScrollingParent;
import android.support.v4.view.NestedScrollingParentHelper;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;


/**
 * Created by Jerry on 16/9/7.
 */
public class JRefreshLayout extends ViewGroup implements NestedScrollingParent, NestedScrollingChild {

    private final String LOG_TAG = "JRefreshLayout";

    private final static int STATE_IDLE = 101;
    private final static int STATE_PULLING = 102;
    private final static int STATE_PULL_REFRESH_READY = 103;
    private final static int STATE_PULL_REFRESHING = 104;
    private final static int STATE_REFRESH_COMPLETED = 105;

    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;

    private static final float DRAG_RATE = .45f;

    private static final int MIN_REFRESH_TRIGGER_DISTANCE = 100;

    private static final int DIFF_MAX_PULL_DISTANCE_WITH_TRIGGER = 50;

    private static final int ANIMATE_TO_START_DURATION = 200;

    private static final int ANIMATE_TO_TRIGGER_DURATION = 200;

    private static final int INVALID_POINTER = -1;

    private View mContentView;
    private int mHeaderHeight;
    private View mHeaderView;
    private IHeaderHandler mHeaderHandler;
    private OnRefreshListener mRefreshListener;
    private final NestedScrollingParentHelper mNestedScrollingParentHelper;
    private final NestedScrollingChildHelper mNestedScrollingChildHelper;

    private int mState = STATE_IDLE;
    private int mActivePointerId = INVALID_POINTER;
    private DecelerateInterpolator mDecelerateInterpolator;
    private float mStartMotionY;
    private int mCurrentContentOffsetTop;
    private int mFrom;
    private boolean mIsBeingDragged;
    private boolean mNestedScrollInProgress;
    private int mTouchSlop;
    private int mMaxPullDistance;
    private int mTriggerDistance;
    private int mProgressingAnimationCount;
    // If nested scrolling is enabled, the total amount that needed to be
    // consumed by this as the nested scrolling parent is used in place of the
    // overscroll determined by MOVE events in the onTouch handler
    private float mTotalUnconsumed;
    private final int[] mParentScrollConsumed = new int[2];
    private final int[] mParentOffsetInWindow = new int[2];

    public JRefreshLayout(Context context) {
        this(context, null);
    }

    public JRefreshLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public JRefreshLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mDecelerateInterpolator = new DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR);
        mNestedScrollingParentHelper = new NestedScrollingParentHelper(this);
        mNestedScrollingChildHelper = new NestedScrollingChildHelper(this);
        mAnimateToTriggerPosition.setAnimationListener(animationToTriggerListener);
        setNestedScrollingEnabled(true);
    }

    @Override
    protected void onFinishInflate() {
        if (getChildCount() < 0) {
            return;
        }
        if (mContentView == null) {
            mContentView = getChildAt(0);
        }
        addHeaderView();
        if (mHeaderView != null) {
            mHeaderView.bringToFront();
        }
        super.onFinishInflate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        log(String.format("onMeasure frame: width: %s, height: %s, padding: %s %s %s %s",
            getMeasuredHeight(), getMeasuredWidth(),
            getPaddingLeft(), getPaddingRight(), getPaddingTop(), getPaddingBottom()));
        if (getChildCount() < 0) {
            return;
        }
        if (mHeaderView != null) {
            measureChildren(widthMeasureSpec, heightMeasureSpec);
            mHeaderHeight = mHeaderView.getMeasuredHeight();
            mTriggerDistance = Math.max(mHeaderHeight, MIN_REFRESH_TRIGGER_DISTANCE);
            mMaxPullDistance = mTriggerDistance + DIFF_MAX_PULL_DISTANCE_WITH_TRIGGER;
        }
        if (mContentView != null) {
            measureChildren(widthMeasureSpec, heightMeasureSpec);
            log(String.format("onMeasure content, width: %s, height: %s",
                mContentView.getMeasuredWidth(), mContentView.getMeasuredHeight()));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() < 0) {
            return;
        }
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        if (mHeaderView != null) {
            mHeaderView.layout(getPaddingLeft(), mCurrentContentOffsetTop - mHeaderHeight, paddingLeft + mHeaderView.getMeasuredWidth(), mCurrentContentOffsetTop + 1);
        }
        if (mContentView != null) {
            mContentView.layout(getPaddingLeft(), mCurrentContentOffsetTop + paddingTop, paddingLeft + mContentView.getMeasuredWidth(), mCurrentContentOffsetTop + paddingTop + mContentView.getMeasuredHeight());
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEnabled() || mState == STATE_PULL_REFRESHING || canChildScrollUp() || mNestedScrollInProgress || mProgressingAnimationCount > 0) {
            return false;
        }
        int action = MotionEventCompat.getActionMasked(ev);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mIsBeingDragged = false;
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                mStartMotionY = MotionEventCompat.getY(ev, 0);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mActivePointerId == INVALID_POINTER) {
                    Log.e(LOG_TAG, "Got ACTION_MOVE event but don't have an active pointer id.");
                    return false;
                }
                float y = getMotionEventY(ev, mActivePointerId);
                if (y == -1) {
                    return false;
                }
                float yDiff = y - mStartMotionY;
                if (yDiff > mTouchSlop && !mIsBeingDragged) {
                    mStartMotionY = mStartMotionY + mTouchSlop;
                    mIsBeingDragged = true;
                }
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                break;
            default:
                break;
        }
        return mIsBeingDragged;
    }

    private float getMotionEventY(MotionEvent ev, int activePointerId) {
        final int index = MotionEventCompat.findPointerIndex(ev, activePointerId);
        if (index < 0) {
            return -1;
        }
        return MotionEventCompat.getY(ev, index);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || mState == STATE_PULL_REFRESHING || canChildScrollUp() || mNestedScrollInProgress || mProgressingAnimationCount > 0) {
            return false;
        }
        int action = MotionEventCompat.getActionMasked(event);
        int pointerIndex;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = MotionEventCompat.getPointerId(event, 0);
                mStartMotionY = MotionEventCompat.getY(event, 0);
                break;
            case MotionEvent.ACTION_MOVE:
                pointerIndex = MotionEventCompat.findPointerIndex(event, mActivePointerId);
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG, "Got ACTION_MOVE event but have an invalid active pointer id.");
                    return false;
                }
                int y = (int) MotionEventCompat.getY(event, pointerIndex);
                float pullDistance = (y - mStartMotionY) * DRAG_RATE;
                offsetTops(pullDistance, y);
                if (pullDistance < 0) {
                    return false;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerIndex = MotionEventCompat.getActionIndex(event);
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG, "Got ACTION_POINTER_DOWN event but have an invalid action index.");
                    return false;
                }
                int oldPointerIndex = MotionEventCompat.findPointerIndex(event, mActivePointerId);
                if (oldPointerIndex < 0) {
                    Log.e(LOG_TAG, "Got ACTION_POINTER_DOWN event but have an invalid active pointer id.");
                    return false;
                }
                mStartMotionY = mStartMotionY + MotionEventCompat.getY(event, pointerIndex) - MotionEventCompat.getY(event, oldPointerIndex);
                mActivePointerId = MotionEventCompat.getPointerId(event, pointerIndex);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mActivePointerId = INVALID_POINTER;
                finishPull();
                break;
            default:
                break;
        }
        return true;
    }

    private void onSecondaryPointerUp(MotionEvent event) {
        int pointerIndex = MotionEventCompat.getActionIndex(event);
        int pointerId = MotionEventCompat.getPointerId(event, pointerIndex);
        if (pointerId == mActivePointerId) {
            int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mStartMotionY = mStartMotionY + MotionEventCompat.getY(event, newPointerIndex) - MotionEventCompat.getY(event, pointerIndex);
            mActivePointerId = MotionEventCompat.getPointerId(event, newPointerIndex);
        }
    }

    private void animateOffsetToTriggerPosition() {
        mFrom = mContentView.getTop();
        mAnimateToTriggerPosition.reset();
        mAnimateToTriggerPosition.setDuration(ANIMATE_TO_TRIGGER_DURATION);
        mAnimateToTriggerPosition.setInterpolator(mDecelerateInterpolator);
        mHeaderView.clearAnimation();
        mHeaderView.startAnimation(mAnimateToTriggerPosition);
    }

    private void animateOffsetToStartPosition(boolean isPullFinished) {
        mFrom = mContentView.getTop();
        mAnimateToStartPosition.reset();
        mAnimateToStartPosition.setDuration(ANIMATE_TO_START_DURATION);
        mAnimateToStartPosition.setInterpolator(mDecelerateInterpolator);
        mAnimateToStartPosition.setAnimationListener(isPullFinished ? pullFinishedToStartListener : completedToStartListener);
        mHeaderView.clearAnimation();
        mHeaderView.startAnimation(mAnimateToStartPosition);
    }

    private final Animation mAnimateToTriggerPosition = new Animation() {
        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            int finalContentTop = mHeaderHeight;
            if (MIN_REFRESH_TRIGGER_DISTANCE > mHeaderHeight) {
                finalContentTop = MIN_REFRESH_TRIGGER_DISTANCE;
            }
            int targetContentTop = (mFrom + (int) ((finalContentTop - mFrom) * interpolatedTime));
            offsetTops(targetContentTop);
        }
    };

    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            int targetContentTop = (mFrom + (int) (-mFrom * interpolatedTime));
            offsetTops(targetContentTop);
        }
    };

    private Animation.AnimationListener pullFinishedToStartListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            mProgressingAnimationCount++;
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            mProgressingAnimationCount--;
            refreshState(STATE_IDLE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    private Animation.AnimationListener completedToStartListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            mProgressingAnimationCount++;
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            mProgressingAnimationCount--;
            refreshState(STATE_REFRESH_COMPLETED);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    private Animation.AnimationListener animationToTriggerListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            mProgressingAnimationCount++;
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            mProgressingAnimationCount--;
            refreshState(STATE_PULL_REFRESHING);
            if (mRefreshListener != null) {
                mRefreshListener.onRefresh();
            }
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    private void offsetTops(float targetContentTop) {
        offsetTops(targetContentTop, -1);
    }

    private void offsetTops(float targetContentTop, int motionY) {
        checkPullDistance(targetContentTop);
        if (targetContentTop < 0) {
            targetContentTop = 0;
        } else if (targetContentTop >= mMaxPullDistance) {
            if (motionY != -1) {
                mStartMotionY = motionY - (mMaxPullDistance / DRAG_RATE);
            }
            return;
        }
        mCurrentContentOffsetTop = (int) targetContentTop;

        int targetHeaderTop = (int) (targetContentTop - mHeaderHeight);
        int offsetContent = (int) (targetContentTop - mContentView.getTop());
        int offsetHeader = targetHeaderTop - mHeaderView.getTop();
        mHeaderView.offsetTopAndBottom(offsetHeader);
        mContentView.offsetTopAndBottom(offsetContent);
    }

    private void checkPullDistance(float pullDistance) {
        if (pullDistance > 0 && pullDistance < mTriggerDistance) {
            refreshState(STATE_PULLING, pullDistance / mTriggerDistance * 100);
        } else if (pullDistance >= mTriggerDistance) {
            refreshState(STATE_PULL_REFRESH_READY);
        }
    }

    private void finishPull() {
        if (mState == STATE_PULL_REFRESH_READY) {
            animateOffsetToTriggerPosition();
        } else {
            animateOffsetToStartPosition(true);
        }
    }

    private void refreshState(int state) {
        refreshState(state, 0);
    }

    private void refreshState(int state, float pullPercent) {
        if (!checkIfSafe(state)) {
            return;
        }
        if (mState == state && state != STATE_PULLING) {
            return;
        }
        mState = state;
        switch (mState) {
            case STATE_PULLING:
                if (mHeaderHandler != null) {
                    mHeaderHandler.onPulling((int) pullPercent);
                }
                break;
            case STATE_PULL_REFRESH_READY:
                if (mHeaderHandler != null) {
                    mHeaderHandler.onPulling(100);
                    mHeaderHandler.onRefreshReady();
                }
                break;
            case STATE_PULL_REFRESHING:
                if (mHeaderHandler != null) {
                    mHeaderHandler.onRefreshing();
                }
                break;
            case STATE_REFRESH_COMPLETED:
                mState = STATE_IDLE;
                if (mHeaderHandler != null) {
                    mHeaderHandler.onRefreshCompleted();
                }
                break;
            default:
                break;
        }
    }

    //avoid error change
    private boolean checkIfSafe(int state) {
        switch (state) {
            case STATE_PULL_REFRESH_READY:
                if (mState != STATE_PULLING) {
                    return false;
                }
                break;
            case STATE_PULLING:
                if (!(mState == STATE_IDLE || mState == STATE_PULLING || mState == STATE_PULL_REFRESH_READY)) {
                    return false;
                }
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * @return Whether it is possible for the child view of this layout to
     * scroll up. Override this if the child view is a custom view.
     */
    public boolean canChildScrollUp() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (mContentView instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) mContentView;
                return absListView.getChildCount() > 0
                    && (absListView.getFirstVisiblePosition() > 0 || absListView.getChildAt(0)
                    .getTop() < absListView.getPaddingTop());
            } else {
                return ViewCompat.canScrollVertically(mContentView, -1) || mContentView.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(mContentView, -1);
        }
    }

    // NestedScrollingParent

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return isEnabled() && !(mState == STATE_PULL_REFRESHING)
            && (nestedScrollAxes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0 && mProgressingAnimationCount <= 0;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        // Reset the counter of how much leftover scroll needs to be consumed.
        mNestedScrollingParentHelper.onNestedScrollAccepted(child, target, axes);
        // Dispatch up to the nested parent
        startNestedScroll(axes & ViewCompat.SCROLL_AXIS_VERTICAL);
        mTotalUnconsumed = 0;
        mNestedScrollInProgress = true;
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        // If we are in the middle of consuming, a scroll, then we want to move the spinner back up
        // before allowing the list to scroll
        if (dy > 0 && mTotalUnconsumed > 0) {
            if (dy > mTotalUnconsumed) {
                consumed[1] = dy - (int) mTotalUnconsumed;
                mTotalUnconsumed = 0;
            } else {
                mTotalUnconsumed -= dy;
                consumed[1] = dy;
            }
            float pullDistance = mTotalUnconsumed * DRAG_RATE;
            if (pullDistance > mMaxPullDistance) {
                //ignore redundant pull distance
                mTotalUnconsumed = mMaxPullDistance / DRAG_RATE;
                pullDistance = mMaxPullDistance;
            }
            offsetTops((int) pullDistance);
        }

        // Now let our nested parent consume the leftovers
        final int[] parentConsumed = mParentScrollConsumed;
        if (dispatchNestedPreScroll(dx - consumed[0], dy - consumed[1], parentConsumed, null)) {
            consumed[0] += parentConsumed[0];
            consumed[1] += parentConsumed[1];
        }
    }

    @Override
    public int getNestedScrollAxes() {
        return mNestedScrollingParentHelper.getNestedScrollAxes();
    }

    @Override
    public void onStopNestedScroll(View target) {
        mNestedScrollingParentHelper.onStopNestedScroll(target);
        mActivePointerId = INVALID_POINTER;
        mNestedScrollInProgress = false;
        // Finish the spinner for nested scrolling if we ever consumed any
        // unconsumed nested scroll
        if (mTotalUnconsumed > 0) {
            finishPull();
            mTotalUnconsumed = 0;
        }
        // Dispatch up our nested parent
        stopNestedScroll();
    }

    @Override
    public void onNestedScroll(final View target, final int dxConsumed, final int dyConsumed,
                               final int dxUnconsumed, final int dyUnconsumed) {
        // Dispatch up to the nested parent first
        dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
            mParentOffsetInWindow);

        // This is a bit of a hack. Nested scrolling works from the bottom up, and as we are
        // sometimes between two nested scrolling views, we need a way to be able to know when any
        // nested scrolling parent has stopped handling events. We do that by using the
        // 'offset in window 'functionality to see if we have been moved from the event.
        // This is a decent indication of whether we should take over the event stream or not.
        final int dy = dyUnconsumed + mParentOffsetInWindow[1];
        if (dy < 0 && !canChildScrollUp()) {
            mTotalUnconsumed += Math.abs(dy);
            float pullDistance = mTotalUnconsumed * DRAG_RATE;
            if (pullDistance > mMaxPullDistance) {
                //ignore redundant pull distance
                mTotalUnconsumed = mMaxPullDistance / DRAG_RATE;
                pullDistance = mMaxPullDistance;
            }
            offsetTops((int) pullDistance);
        }
    }

    // NestedScrollingChild

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mNestedScrollingChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mNestedScrollingChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return mNestedScrollingChildHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        mNestedScrollingChildHelper.stopNestedScroll();
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return mNestedScrollingChildHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed,
                                        int dyUnconsumed, int[] offsetInWindow) {
        return mNestedScrollingChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed,
            dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
        return mNestedScrollingChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX,
                                    float velocityY) {
        return dispatchNestedPreFling(velocityX, velocityY);
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY,
                                 boolean consumed) {
        return dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mNestedScrollingChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mNestedScrollingChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    private void addHeaderView() {
        if (mHeaderView == null) {
            mHeaderView = new DefaultHeader(getContext());
            setHeadHandler((IHeaderHandler) mHeaderView);
        }
        addView(mHeaderView, 0);
    }

    public void setHeaderView(View headerView) {
        if (headerView == null) {
            return;
        }
        if (mHeaderView != null && mHeaderView != headerView) {
            removeView(mHeaderView);
        }
        LayoutParams lp = headerView.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(-1, -2);
            headerView.setLayoutParams(lp);
        }
        this.mHeaderView = headerView;
        addView(mHeaderView, 0);
        if (headerView instanceof IHeaderHandler) {
            setHeadHandler(((IHeaderHandler) mHeaderView));
        }
    }

    public void setHeadHandler(IHeaderHandler headHandler) {
        this.mHeaderHandler = headHandler;
    }

    public void setRefreshCompleted() {
        animateOffsetToStartPosition(false);
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        this.mRefreshListener = listener;
    }

    public void startRefreshing() {
        animateOffsetToTriggerPosition();
    }

    private void log(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, message);
        }
    }

    public interface OnRefreshListener {
        void onRefresh();
    }
}
