package com.clipkeep.android;

import android.app.*;
import android.content.*;
import android.os.*;

/** User-started connected-device foreground service. No background clipboard reads. */
public final class ReceiveService extends Service {
    static volatile boolean running;
    private LanReceiver receiver;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("receive", "设备后台接收", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ReceiveService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "receive").setSmallIcon(com.clipkeep.android.R.drawable.ic_clipkeep)
            .setContentTitle("clipkeep 后台接收已开启").setContentText("通过 Wi-Fi / Tailnet 接收文本和图片")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "停止接收", stop).build()).build();
        startForeground(1, notification);
        if (receiver == null) {
            ClipkeepApp app = (ClipkeepApp)getApplication();
            try {
                receiver = new LanReceiver(this, LanSettings.key(this), app.store, new LanReceiver.Listener() {
                    public void received(long id) {
                        main.post(() -> {
                            if (receiver == null) return;
                            if (id >= 0 && LanSettings.prefs(ReceiveService.this).getBoolean("copy_received", false)) {
                                try { ClipboardActions.copy(ReceiveService.this, app.store, id); }
                                catch (Exception ignored) { /* Stored history remains available for manual copy. */ }
                            }
                            app.notifyChanged(null);
                        });
                    }
                    public void failed(Exception error) { /* Invalid packets never become notifications. */ }
                });
                running = true;
            } catch (Exception error) {
                running = false;
                app.notifyChanged("接收启动失败：" + error.getMessage());
                stopSelf(); return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }
    @Override public void onDestroy() {
        running = false; if (receiver != null) receiver.close(); receiver = null;
        main.removeCallbacksAndMessages(null); stopForeground(STOP_FOREGROUND_REMOVE);
        ((ClipkeepApp)getApplication()).notifyChanged(null); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
