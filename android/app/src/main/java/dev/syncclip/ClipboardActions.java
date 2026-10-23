package dev.syncclip;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Private entry point: Android enforces same-UID or a delegated PendingIntent at the alias. */
final class ClipboardActions {
  private static ComponentName component(Context context) {
    return new ComponentName(context, ClipboardActions.class);
  }

  static Intent intent(Context context, String action) {
    return new Intent(action).setComponent(component(context));
  }

  static boolean accepts(Context context, Intent intent) {
    // Check each delivered intent, including onNewIntent; the activity instance is public.
    return component(context).equals(intent.getComponent());
  }
}
