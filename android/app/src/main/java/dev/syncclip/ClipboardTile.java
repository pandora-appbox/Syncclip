package dev.syncclip;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class ClipboardTile extends TileService {
  @Override
  public void onStartListening() {
    getQsTile().setState(Tile.STATE_ACTIVE);
    getQsTile().updateTile();
  }

  @android.annotation.SuppressLint(
      "StartActivityAndCollapseDeprecated") // Legacy call is guarded to Android 13 and earlier.
  @Override
  public void onClick() {
    Intent intent =
        ClipboardActions.intent(this, "dev.syncclip.SEND").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Runnable action =
        () -> {
          if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    4,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
          else startActivityAndCollapse(intent);
        };
    if (isLocked()) unlockAndRun(action);
    else action.run();
  }
}
