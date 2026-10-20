package com.clipkeep.android;

import android.app.Application;
import java.util.concurrent.*;

public final class ClipkeepApp extends Application {
    ClipStore store;
    // One worker with a bounded queue; idle threads disappear after 15 seconds.
    final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    @Override public void onCreate() { super.onCreate(); store = new ClipStore(this); }
}
