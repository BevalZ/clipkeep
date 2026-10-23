package com.clipkeep.android;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;

/** User-started connected-device foreground service. No background clipboard reads. */
public final class ReceiveService extends Service {
    static volatile boolean running;
    static volatile String state = "后台接收未开启";
    private LanReceiver receiver;
    private ConnectivityManager connectivity;
    private boolean watching, stopping;
    private int retries;
    private final Handler main = new Handler(Looper.getMainLooper());
    static boolean enabled(Context context) { return LanSettings.prefs(context).getBoolean("receive_enabled", false); }
    @android.annotation.SuppressLint("ApplySharedPref")
    static void setEnabled(Context context, boolean value) {
        // Persist before stopping: a sticky restart must observe an explicit user stop.
        LanSettings.prefs(context).edit().putBoolean("receive_enabled", value).commit();
    }
    private final Runnable recover = this::startReceiver;
    private final ConnectivityManager.NetworkCallback networks = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { main.post(() -> networkChanged(true)); }
        @Override public void onLost(Network network) { main.post(() -> networkChanged(false)); }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("receive", "设备后台接收", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
        connectivity = getSystemService(ConnectivityManager.class);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ReceiveService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "receive").setSmallIcon(com.clipkeep.android.R.drawable.ic_clipkeep)
            .setContentTitle("clipkeep 后台接收").setContentText(state)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "停止接收", stop).build()).build();
    }
    private void update(String value) {
        state = value;
        getSystemService(NotificationManager.class).notify(1, notification());
        ((ClipkeepApp)getApplication()).notifyChanged(null);
    }
    private void networkChanged(boolean available) {
        if (stopping || !enabled(this)) return;
        if (receiver == null && available) {
            retries = 0; main.removeCallbacks(recover); startReceiver();
        } else if (receiver != null) updateNetworkState();
    }
    private void updateNetworkState() {
        update(connectivity.getAllNetworks().length == 0 ? "等待网络恢复 · 接收服务保持开启" : "后台接收已开启 · 文本 / 图片 · Wi-Fi / Tailnet");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            setEnabled(this, false);
            stopping = true; stopSelf(); return START_NOT_STICKY;
        }
        if (intent == null && !enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null) setEnabled(this, true);
        stopping = false; state = "正在启动后台接收…";
        startForeground(1, notification());
        if (!watching) {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networks);
            watching = true;
        }
        retries = 0; main.removeCallbacks(recover); startReceiver();
        return START_STICKY;
    }
    private void startReceiver() {
        if (stopping || !enabled(this)) return;
        if (receiver != null) { updateNetworkState(); return; }
        ClipkeepApp app = (ClipkeepApp)getApplication();
        try {
            receiver = new LanReceiver(this, LanSettings.key(this), app.store, new LanReceiver.Listener() {
                public void received(long id) {
                    main.post(() -> {
                        if (receiver == null || stopping) return;
                        if (id >= 0 && LanSettings.prefs(ReceiveService.this).getBoolean("copy_received", false)) {
                            try { ClipboardActions.copy(ReceiveService.this, app.store, id); }
                            catch (Exception ignored) { /* Stored history remains available for manual copy. */ }
                        }
                        app.notifyChanged(null);
                    });
                }
                public void failed(Exception error) { /* Invalid packets never become notifications. */ }
                public void stopped(Exception error) { main.post(() -> failedReceiver(error)); }
            });
            running = true; updateNetworkState();
        } catch (Exception error) { failedReceiver(error); }
    }
    private void failedReceiver(Exception error) {
        if (stopping || !enabled(this)) return;
        running = false;
        if (receiver != null) receiver.close(); receiver = null;
        main.removeCallbacks(recover);
        if (retries < 3) {
            long delay = new long[]{1000, 3000, 6000}[retries++];
            update("接收暂不可用 · 正在重试 " + retries + "/3"); main.postDelayed(recover, delay);
        } else update("接收恢复失败，请关闭再开启接收；检查端口 45788 是否被占用");
    }
    @Override public void onDestroy() {
        stopping = true; running = false;
        if (watching) connectivity.unregisterNetworkCallback(networks);
        if (receiver != null) receiver.close(); receiver = null;
        main.removeCallbacksAndMessages(null); stopForeground(STOP_FOREGROUND_REMOVE);
        state = enabled(this) ? "后台接收已中断 · 打开应用可恢复" : "后台接收未开启";
        ((ClipkeepApp)getApplication()).notifyChanged(null); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
