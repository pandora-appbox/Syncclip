package dev.syncclip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class SyncService extends Service implements SyncEngine.Observer {
  private SyncApp app;
  private boolean foreground;

  @Override
  public void onCreate() {
    super.onCreate();
    app = (SyncApp) getApplication();
    NotificationChannel channel =
        new NotificationChannel(
            "connection_quiet",
            L.tr(this, "Device connection"),
            NotificationManager.IMPORTANCE_MIN);
    channel.setSound(null, null);
    channel.enableVibration(false);
    channel.setShowBadge(false);
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
    app.engine.observe(this);
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent send =
        PendingIntent.getActivity(
            this,
            1,
            ClipboardActions.intent(this, "dev.syncclip.SEND"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            3,
            new Intent(this, SyncService.class).setAction("dev.syncclip.PAUSE"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "connection_quiet")
        .setSmallIcon(R.drawable.ic_stat_syncclip)
        .setContentTitle("Syncclip")
        .setContentText(L.tr(this, app.engine.status))
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setShowWhen(false)
        .addAction(
            new Notification.Action.Builder(null, L.tr(this, "Send clipboard"), send).build())
        .addAction(new Notification.Action.Builder(null, L.tr(this, "Pause"), pause).build())
        .build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "dev.syncclip.PAUSE".equals(intent.getAction())) {
      app.store.enabled(false);
      app.engine.stop();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!app.store.paired() || !app.store.enabled()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    else startForeground(1, notification());
    foreground = true;
    app.engine.start();
    return START_STICKY;
  }

  @Override
  public void changed() {
    if (foreground)
      try {
        getSystemService(NotificationManager.class).notify(1, notification());
      } catch (SecurityException ignored) {
      }
  }

  @Override
  public void onDestroy() {
    foreground = false;
    app.engine.remove(this);
    app.engine.stop();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
