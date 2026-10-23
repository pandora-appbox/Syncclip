package dev.syncclip;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SecurityBoundaryTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  private Activity focused() throws Exception {
    AtomicReference<Activity> result = new AtomicReference<>();
    long deadline = System.currentTimeMillis() + 10000;
    while (result.get() == null && System.currentTimeMillis() < deadline) {
      instrumentation.runOnMainSync(
          () -> {
            for (Activity a :
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
              if (a instanceof MainActivity && a.hasWindowFocus()) result.set(a);
          });
      Thread.sleep(50);
    }
    assertNotNull("Native activity did not acquire focus", result.get());
    return result.get();
  }

  private void launch(Intent intent) throws Exception {
    instrumentation.runOnMainSync(
        () -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    focused();
    instrumentation.waitForIdleSync();
    Thread.sleep(250);
  }

  private Intent publicAction(String action) {
    return new Intent(context, MainActivity.class).setAction(action);
  }

  private String clipboardText() {
    AtomicReference<String> result = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          ClipData clip = context.getSystemService(ClipboardManager.class).getPrimaryClip();
          result.set(clip == null ? "" : String.valueOf(clip.getItemAt(0).getText()));
        });
    return result.get();
  }

  @Test
  public void onlyPrivateAliasCanDispatchClipboardCommands() throws Exception {
    // Never replace a real user's pairing or history. Build this class into an isolated app ID.
    Assume.assumeTrue(context.getPackageName().equals("dev.syncclip.securitycheck"));
    SyncApp app = (SyncApp) context.getApplicationContext();
    byte[] bytes = new byte[32];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
    String key = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    app.store.pair("syncclip://pair?url=https%3A%2F%2F127.0.0.1%3A1&key=" + key);
    app.store.enabled(false);
    ActivityInfo alias =
        context
            .getPackageManager()
            .getActivityInfo(new ComponentName(context, ClipboardActions.class), 0);
    assertFalse("Private alias must be OS restricted", alias.exported);
    assertEquals(MainActivity.class.getName(), alias.targetActivity);
    assertTrue(
        context
            .getPackageManager()
            .getActivityInfo(new ComponentName(context, MainActivity.class), 0)
            .exported);
    launch(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
    AtomicReference<ClipData> original = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
          original.set(clipboard.getPrimaryClip());
          clipboard.setPrimaryClip(
              ClipData.newPlainText("security test", "synthetic private clipboard"));
        });
    try {
      launch(publicAction("dev.syncclip.SEND").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
      assertFalse("External cold SEND resumed paused sync", app.store.enabled());
      assertTrue("External cold SEND queued clipboard", app.store.list("pending").isEmpty());
      launch(publicAction("dev.syncclip.SEND"));
      assertFalse("External warm SEND resumed sync", app.store.enabled());
      assertTrue(app.store.list("pending").isEmpty());
      ClipCrypto crypto = new ClipCrypto(key);
      JSONObject historical = crypto.encrypt("synthetic history item", "Security test");
      historical.put("created", System.currentTimeMillis()).put("seq", 1);
      app.store.remember(List.of(historical));
      String id = historical.getString("id");
      launch(publicAction("dev.syncclip.COPY").putExtra("clip_id", id));
      assertEquals("synthetic private clipboard", clipboardText());
      launch(ClipboardActions.intent(context, "dev.syncclip.COPY").putExtra("clip_id", id));
      assertEquals("synthetic history item", clipboardText());
      // A previously trusted delivery must never authorize a later public delivery.
      launch(publicAction("dev.syncclip.SEND"));
      assertFalse(app.store.enabled());
      assertTrue(app.store.list("pending").isEmpty());
      // Exercise the same immutable capability used by notification actions and Android 14 tile.
      PendingIntent trustedSend =
          PendingIntent.getActivity(
              context,
              81,
              ClipboardActions.intent(context, "dev.syncclip.SEND"),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      trustedSend.send();
      focused();
      instrumentation.waitForIdleSync();
      Thread.sleep(500);
      assertTrue("Private notification/tile SEND failed", app.store.enabled());
      assertEquals(1, app.store.list("pending").size());
      assertEquals(
          "synthetic history item",
          crypto.decrypt(app.store.list("pending").get(0)).getString("text"));
      launch(publicAction("dev.syncclip.SEND"));
      assertEquals(
          "Public delivery reused private authorization", 1, app.store.list("pending").size());
      launch(
          publicAction(Intent.ACTION_SEND)
              .setType("text/plain")
              .putExtra(Intent.EXTRA_TEXT, "public shared text"));
      assertEquals(2, app.store.list("pending").size());
      launch(
          publicAction(Intent.ACTION_PROCESS_TEXT)
              .setType("text/plain")
              .putExtra(Intent.EXTRA_PROCESS_TEXT, "selected public text"));
      assertEquals(3, app.store.list("pending").size());
    } finally {
      instrumentation.runOnMainSync(
          () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (original.get() != null) clipboard.setPrimaryClip(original.get());
            else clipboard.clearPrimaryClip();
            app.store.enabled(false);
            context.stopService(new Intent(context, SyncService.class));
            app.engine.stop();
          });
    }
  }
}
