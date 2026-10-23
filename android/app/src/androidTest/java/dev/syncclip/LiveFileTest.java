package dev.syncclip;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LiveFileTest {
  @Test
  public void copiedZipAndNativeShareReturnExactBytes() throws Exception {
    Assume.assumeTrue(
        "true".equals(InstrumentationRegistry.getArguments().getString("live_files")));
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    SyncApp app = (SyncApp) context.getApplicationContext();
    assertTrue(app.store.paired());
    instrumentation.runOnMainSync(
        () ->
            context.startActivity(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    long deadline = System.currentTimeMillis() + 30000;
    while (!"Connected".equals(app.engine.status) && System.currentTimeMillis() < deadline)
      Thread.sleep(100);
    assertEquals("Connected", app.engine.status);
    long baseline = 0;
    for (JSONObject c : app.store.list("history")) baseline = Math.max(baseline, c.optLong("seq"));
    AtomicReference<ClipData> original = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          original.set(context.getSystemService(ClipboardManager.class).getPrimaryClip());
          app.store.syncNotifications(false);
          context.getSystemService(android.app.NotificationManager.class).cancel(2);
        });
    try {
      Bundle ready = new Bundle();
      ready.putString("stream", "FILE_TEST_READY\n");
      instrumentation.sendStatus(0, ready);
      JSONObject incoming = null;
      deadline = System.currentTimeMillis() + 75000;
      while (incoming == null && System.currentTimeMillis() < deadline) {
        for (JSONObject clip : app.store.list("history"))
          if (clip.optLong("seq") > baseline
              && clip.has("blob")
              && "file".equals(new ClipCrypto(app.store.key()).decrypt(clip).optString("kind")))
            incoming = clip;
        Thread.sleep(100);
      }
      assertNotNull("Linux copied file was not received", incoming);
      final String id = incoming.getString("id");
      AtomicReference<Uri> uri = new AtomicReference<>();
      deadline = System.currentTimeMillis() + 75000;
      while (uri.get() == null && System.currentTimeMillis() < deadline) {
        instrumentation.runOnMainSync(
            () -> {
              ClipData clip = context.getSystemService(ClipboardManager.class).getPrimaryClip();
              if (clip != null
                  && clip.getItemCount() > 0
                  && clip.getItemAt(0).getUri() != null
                  && id.equals(clip.getItemAt(0).getUri().getLastPathSegment()))
                uri.set(clip.getItemAt(0).getUri());
            });
        Thread.sleep(100);
      }
      assertNotNull("Native file clipboard URI missing", uri.get());
      byte[] expected =
          ImageTransfer.boundedRead(
              instrumentation.getContext().getAssets().open("sample.zip"), ClipCrypto.MAX_FILE);
      assertArrayEquals(
          expected,
          ImageTransfer.boundedRead(
              context.getContentResolver().openInputStream(uri.get()), ClipCrypto.MAX_FILE));
      assertEquals("application/zip", context.getContentResolver().getType(uri.get()));
      for (android.service.notification.StatusBarNotification n :
          context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
        assertNotEquals("Clip alert posted while disabled", 2, n.getId());
      Bundle verified = new Bundle();
      verified.putString("stream", "ANDROID_FILE_BYTES_AND_QUIET_MODE_VERIFIED\n");
      instrumentation.sendStatus(0, verified);
      instrumentation.runOnMainSync(
          () -> {
            Intent share =
                new Intent(context, MainActivity.class)
                    .setAction(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri.get())
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(
                ClipData.newUri(context.getContentResolver(), "Test archive", uri.get()));
            context.startActivity(share);
          });
      boolean sent = false;
      deadline = System.currentTimeMillis() + 75000;
      while (!sent && System.currentTimeMillis() < deadline) {
        for (JSONObject clip : app.store.list("history"))
          if (clip.has("blob")
              && !id.equals(clip.optString("id"))
              && clip.optLong("seq") > incoming.optLong("seq")
              && "file".equals(new ClipCrypto(app.store.key()).decrypt(clip).optString("kind")))
            sent = true;
        Thread.sleep(100);
      }
      assertTrue("Native ZIP share did not reach VPS", sent);
      deadline = System.currentTimeMillis() + 30000;
      while (!app.store.list("pending").isEmpty() && System.currentTimeMillis() < deadline)
        Thread.sleep(100);
      assertTrue("File queue not acknowledged", app.store.list("pending").isEmpty());
      final long incomingSeq = incoming.optLong("seq");
      deadline = System.currentTimeMillis() + 45000;
      while ((app.store.contains(id)
              || app.store.list("history").stream()
                  .anyMatch(c -> c.optLong("seq") > incomingSeq && c.has("blob")))
          && System.currentTimeMillis() < deadline) Thread.sleep(100);
      assertFalse("Deleted file remains in Android history", app.store.contains(id));
      assertFalse("Deleted ciphertext remains cached", app.images.path(id).exists());
      Bundle deleted = new Bundle();
      deleted.putString("stream", "ANDROID_FILE_DELETION_VERIFIED\n");
      instrumentation.sendStatus(0, deleted);
    } finally {
      instrumentation.runOnMainSync(
          () -> {
            if (original.get() != null)
              context.getSystemService(ClipboardManager.class).setPrimaryClip(original.get());
          });
    }
  }
}
