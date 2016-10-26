package jy.refresh;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * Created by Jerry on 16/9/7.
 */
public class DefaultFooter extends LinearLayout implements IFooterHandler {

    public DefaultFooter(Context context) {
        super(context);
    }

    public DefaultFooter(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DefaultFooter(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void onRefreshReady() {

    }

    @Override
    public void onRefreshing() {

    }

    @Override
    public void onRefreshCompleted() {

    }
}
