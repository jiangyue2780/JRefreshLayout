package jy.refresh;

/**
 * Created by Jerry on 16/9/7.
 */
public interface IHeaderHandler {

    void onPulling(int percent);

    void onRefreshReady();

    void onRefreshing();

    void onRefreshCompleted();
}
