package dev.syncclip;

import static org.junit.Assert.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LiveSyncTest {
  @Test
  public void phoneToVpsToLinuxAndBack() throws Exception {
    Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("live")));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SyncApp app = (SyncApp) context.getApplicationContext();
    File bootstrap = new File(context.getFilesDir(), "test-pairing.txt");
    if (bootstrap.exists()) {
      app.store.pair(
          new String(Files.readAllBytes(bootstrap.toPath()), StandardCharsets.UTF_8).trim());
      bootstrap.delete();
    }
    assertTrue("Missing private test pairing", app.store.paired());
    AtomicReference<ClipData> original = new AtomicReference<>();
    android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    System.out.println("Live test: launching native activity");
    instrumentation.runOnMainSync(
        () ->
            context.startActivity(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    {
      long deadline = System.currentTimeMillis() + 20000;
      while (!"Connected".equals(app.engine.status) && System.currentTimeMillis() < deadline)
        Thread.sleep(100);
      assertEquals("Connected", app.engine.status);
      System.out.println("Live test: connected");
      long previousSequence = 0;
      for (JSONObject clip : app.store.list("history"))
        previousSequence = Math.max(previousSequence, clip.optLong("seq"));
      Thread.sleep(700);
      instrumentation.runOnMainSync(
          () -> {
            ClipboardManager cb = context.getSystemService(ClipboardManager.class);
            original.set(cb.getPrimaryClip());
          });
      instrumentation.runOnMainSync(
          () ->
              context.startActivity(
                  new Intent(context, MainActivity.class)
                      .setAction(Intent.ACTION_SEND)
                      .setType("text/plain")
                      .putExtra(Intent.EXTRA_TEXT, "Syncclip Android live test 🐧")
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
      System.out.println("Live test: shared Android text");
      String expected = "Syncclip Linux reply ✓";
      deadline = System.currentTimeMillis() + 20000;
      boolean received = false;
      while (System.currentTimeMillis() < deadline) {
        for (JSONObject clip : app.store.list("history"))
          if (clip.optLong("seq") > previousSequence
              && new ClipCrypto(app.store.key()).decrypt(clip).optString("text").equals(expected))
            received = true;
        if (received && app.store.list("pending").isEmpty()) break;
        Thread.sleep(100);
      }
      assertTrue("Linux reply not received", received);
      assertTrue("Android outbox was not acknowledged", app.store.list("pending").isEmpty());
      instrumentation.runOnMainSync(
          () -> {
            ClipboardManager cb = context.getSystemService(ClipboardManager.class);
            assertNotNull(cb.getPrimaryClip());
            assertEquals(expected, cb.getPrimaryClip().getItemAt(0).getText().toString());
          });
      System.out.println("Live test: Linux reply verified in Android clipboard");
      instrumentation.runOnMainSync(
          () -> {
            if (original.get() != null)
              context.getSystemService(ClipboardManager.class).setPrimaryClip(original.get());
          });
    }
  }
}
