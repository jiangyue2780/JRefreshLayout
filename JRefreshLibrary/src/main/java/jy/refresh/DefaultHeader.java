package jy.refresh;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Created by Jerry on 16/9/7.
 */
public class DefaultHeader extends RelativeLayout implements IHeaderHandler {
    private TextView tvStatus;
    private TextView tvRefreshTime;
    private TextView tvRefreshing;
    private ImageView ivArrow;
    private ProgressBar pbLoading;
    private LinearLayout llCenter;
    private long lastRefreshTime;
    private long lastChangeTextTime;
    private RotateAnimation reverseArrowAnimation;
    private RotateAnimation arrowAnimation;
    private int lastPercent;
    private boolean isPulling;

    public DefaultHeader(Context context) {
        this(context, null);
    }

    public DefaultHeader(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DefaultHeader(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        inflate(context, R.layout.header_default, this);
        tvRefreshTime = (TextView) findViewById(R.id.tv_refresh_time);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        ivArrow = (ImageView) findViewById(R.id.iv_arrow);
        pbLoading = (ProgressBar) findViewById(R.id.pb_loading);
        llCenter = (LinearLayout) findViewById(R.id.ll_center);
        tvRefreshing = (TextView) findViewById(R.id.tv_refreshing);
        initAnimations();
    }

    private void initAnimations() {
        arrowAnimation = new RotateAnimation(0, -180, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        arrowAnimation.setInterpolator(new LinearInterpolator());
        arrowAnimation.setDuration(200);
        arrowAnimation.setFillAfter(true);

        reverseArrowAnimation = new RotateAnimation(-180, 0, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        reverseArrowAnimation.setInterpolator(new LinearInterpolator());
        reverseArrowAnimation.setDuration(200);
        reverseArrowAnimation.setFillAfter(true);
    }

    @Override
    public void onPulling(int percent) {
        if (percent < 100) {
            isPulling = true;
        }
        tvStatus.setText("下拉刷新");
        String refreshTime = getLastRefreshTime();
        if (!TextUtils.isEmpty(refreshTime)) {
            tvRefreshTime.setText("上次刷新：" + refreshTime);
            Log.e("eee", "refreshTime:" + refreshTime);
        }
        if (lastPercent == 100 && percent < 100) {
            rotateArrow(true);
        }
        lastPercent = percent;
    }

    @Override
    public void onRefreshReady() {
        tvStatus.setText("松开刷新");
        rotateArrow(false);
    }

    @Override
    public void onRefreshing() {
        ivArrow.setAlpha(0);
        ivArrow.setVisibility(View.INVISIBLE);
        pbLoading.setVisibility(View.VISIBLE);
        tvRefreshing.setVisibility(View.VISIBLE);
        llCenter.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onRefreshCompleted() {
        isPulling = false;
        tvStatus.setText("刷新完成");
        lastRefreshTime = 0;
        ivArrow.setAlpha(255);
        pbLoading.setVisibility(View.GONE);
        llCenter.setVisibility(View.VISIBLE);
        tvRefreshing.setVisibility(View.GONE);
        lastRefreshTime = System.currentTimeMillis();
        tvRefreshTime.setText("上次刷新：刚刚");
        Log.e("eee", "complete:刚刚");
        rotateArrow(true);
    }

    private void rotateArrow(boolean isReverse) {
        ivArrow.clearAnimation();
        ivArrow.startAnimation(isReverse ? reverseArrowAnimation : arrowAnimation);
    }

    private String getLastRefreshTime() {
        long currTime = System.currentTimeMillis();
        if (lastRefreshTime == 0) {
            return "无";
        }
        if (isPulling && currTime - lastChangeTextTime < 1000 * 30) {
            return "";
        }
        lastChangeTextTime = currTime;
        long diff = currTime - lastRefreshTime;
        if (diff < 1000 * 30) {
            return "刚刚";
        } else if (diff < 1000 * 60) {
            return "1分钟内";
        } else if (diff < 1000 * 60 * 60) {
            return diff / (1000 * 60) + "分钟之前";
        } else if (diff < 1000 * 60 * 60 * 24) {
            return diff / (1000 * 60 * 60) + "小时之前";
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            return sdf.format(lastRefreshTime);
        }
    }
}
