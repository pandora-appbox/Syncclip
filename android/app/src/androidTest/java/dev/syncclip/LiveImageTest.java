package dev.syncclip;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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
public final class LiveImageTest {
  private android.widget.ImageView preview(android.view.View view) {
    if (view instanceof android.widget.ImageView
        && "Shared image"
            .contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
        && ((android.widget.ImageView) view).getDrawable()
            instanceof android.graphics.drawable.BitmapDrawable
        && ((android.graphics.drawable.BitmapDrawable)
                    ((android.widget.ImageView) view).getDrawable())
                .getBitmap()
                .getWidth()
            > 100) return (android.widget.ImageView) view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        android.widget.ImageView result = preview(group.getChildAt(i));
        if (result != null) return result;
      }
    }
    return null;
  }

  @Test
  public void screenshotClipboardAndNativeShare() throws Exception {
    Assume.assumeTrue(
        "true".equals(InstrumentationRegistry.getArguments().getString("live_images")));
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    SyncApp app = (SyncApp) context.getApplicationContext();
    File pairing = new File(context.getFilesDir(), "test-pairing.txt");
    if (pairing.exists()) {
      if (!app.store.paired())
        app.store.pair(
            new String(Files.readAllBytes(pairing.toPath()), StandardCharsets.UTF_8).trim());
      pairing.delete();
    }
    assertTrue(app.store.paired());
    instrumentation.runOnMainSync(
        () ->
            context.startActivity(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    long deadline = System.currentTimeMillis() + 25000;
    while (!"Connected".equals(app.engine.status) && System.currentTimeMillis() < deadline)
      Thread.sleep(100);
    assertEquals("Connected", app.engine.status);
    long baseline = 0;
    for (JSONObject clip : app.store.list("history"))
      baseline = Math.max(baseline, clip.optLong("seq"));
    AtomicReference<ClipData> original = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> original.set(context.getSystemService(ClipboardManager.class).getPrimaryClip()));
    Bundle ready = new Bundle();
    ready.putString("stream", "IMAGE_TEST_READY\n");
    instrumentation.sendStatus(0, ready);
    JSONObject incoming = null;
    deadline = System.currentTimeMillis() + 60000;
    while (incoming == null && System.currentTimeMillis() < deadline) {
      for (JSONObject clip : app.store.list("history"))
        if (clip.optLong("seq") > baseline && clip.has("blob")) incoming = clip;
      Thread.sleep(100);
    }
    assertNotNull("Linux screenshot was not received", incoming);
    AtomicReference<Uri> uri = new AtomicReference<>();
    deadline = System.currentTimeMillis() + 60000;
    final String incomingId = incoming.getString("id");
    while (uri.get() == null && System.currentTimeMillis() < deadline) {
      instrumentation.runOnMainSync(
          () -> {
            ClipData clip = context.getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null
                && clip.getItemCount() > 0
                && clip.getItemAt(0).getUri() != null
                && incomingId.equals(clip.getItemAt(0).getUri().getLastPathSegment()))
              uri.set(clip.getItemAt(0).getUri());
          });
      Thread.sleep(100);
    }
    assertNotNull("Image was not placed on Android clipboard", uri.get());
    byte[] received =
        ImageTransfer.boundedRead(
            context.getContentResolver().openInputStream(uri.get()), ClipCrypto.MAX_IMAGE);
    byte[] fixture =
        ImageTransfer.boundedRead(
            instrumentation.getContext().getAssets().open("screenshot.png"), ClipCrypto.MAX_IMAGE);
    Bitmap actual = BitmapFactory.decodeByteArray(received, 0, received.length),
        expected = BitmapFactory.decodeByteArray(fixture, 0, fixture.length);
    assertNotNull(actual);
    assertTrue("Screenshot pixels changed", expected.sameAs(actual));
    AtomicReference<android.graphics.Rect> previewBounds = new AtomicReference<>();
    deadline = System.currentTimeMillis() + 15000;
    while (previewBounds.get() == null && System.currentTimeMillis() < deadline) {
      instrumentation.runOnMainSync(
          () -> {
            for (android.app.Activity activity :
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
              if (!(activity instanceof MainActivity)) continue;
              android.widget.ImageView image = preview(activity.getWindow().getDecorView());
              if (image == null || image.getWidth() == 0 || image.getHeight() == 0) continue;
              int[] point = new int[2];
              image.getLocationOnScreen(point);
              int screen = activity.getResources().getDisplayMetrics().widthPixels;
              if (image.getWidth() == screen
                  && Math.abs(
                          image.getHeight()
                              - image.getWidth()
                                  * expected.getHeight()
                                  / (double) expected.getWidth())
                      < 3)
                previewBounds.set(
                    new android.graphics.Rect(0, 0, screen, point[1] + image.getHeight()));
            }
          });
      Thread.sleep(100);
    }
    assertNotNull(
        "Image preview is not full width or its aspect ratio changed", previewBounds.get());
    AtomicReference<Bitmap> rendered = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          for (android.app.Activity activity :
              androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                  .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
            if (!(activity instanceof MainActivity)) continue;
            android.view.View decor = activity.getWindow().getDecorView();
            Bitmap image =
                Bitmap.createBitmap(
                    decor.getWidth(),
                    Math.min(decor.getHeight(), previewBounds.get().bottom),
                    Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(image));
            rendered.set(image);
          }
        });
    if (rendered.get() != null)
      try (java.io.OutputStream output =
          new java.io.FileOutputStream(
              new File(context.getCacheDir(), "syncclip-preview-check.png"))) {
        rendered.get().compress(Bitmap.CompressFormat.PNG, 100, output);
      }
    Bundle confirmed = new Bundle();
    confirmed.putString("stream", "ANDROID_IMAGE_PIXELS_VERIFIED\n");
    instrumentation.sendStatus(0, confirmed);
    instrumentation.runOnMainSync(
        () -> {
          Intent share =
              new Intent(context, MainActivity.class)
                  .setAction(Intent.ACTION_SEND)
                  .setType("image/png")
                  .putExtra(Intent.EXTRA_STREAM, uri.get())
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
          share.setClipData(
              ClipData.newUri(context.getContentResolver(), "Test screenshot", uri.get()));
          context.startActivity(share);
        });
    boolean sent = false;
    deadline = System.currentTimeMillis() + 60000;
    while (!sent && System.currentTimeMillis() < deadline) {
      for (JSONObject clip : app.store.list("history"))
        if (clip.has("blob")
            && !clip.getString("id").equals(incomingId)
            && clip.optLong("seq") > incoming.optLong("seq")) sent = true;
      Thread.sleep(100);
    }
    assertTrue("Native image Share did not reach the relay", sent);
    deadline = System.currentTimeMillis() + 30000;
    while (!app.store.list("pending").isEmpty() && System.currentTimeMillis() < deadline)
      Thread.sleep(100);
    assertTrue("Image outbox not acknowledged", app.store.list("pending").isEmpty());
    instrumentation.runOnMainSync(
        () -> {
          if (original.get() != null)
            context.getSystemService(ClipboardManager.class).setPrimaryClip(original.get());
        });
  }
}
