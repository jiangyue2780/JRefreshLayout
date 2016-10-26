package jy.refresh;

/**
 * Created by Jerry on 16/9/7.
 */
public interface IFooterHandler {

    void onRefreshReady();

    void onRefreshing();

    void onRefreshCompleted();
}
