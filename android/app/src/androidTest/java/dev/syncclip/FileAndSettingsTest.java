package dev.syncclip;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FileAndSettingsTest {
  private String key() {
    byte[] bytes = new byte[32];
    for (int i = 0; i < 32; i++) bytes[i] = (byte) i;
    return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  private static final class IsolatedContext extends ContextWrapper {
    final String prefix = "syncclip_test_" + UUID.randomUUID();
    final File folder;

    IsolatedContext(Context base) {
      super(base);
      folder = new File(base.getCacheDir(), prefix);
      folder.mkdirs();
    }

    @Override
    public File getFilesDir() {
      return folder;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      return super.getSharedPreferences(prefix + name, mode);
    }

    void cleanup() {
      for (File f : folder.listFiles()) f.delete();
      folder.delete();
      getSharedPreferences("syncclip", 0).edit().clear().commit();
    }
  }

  @Test
  public void pythonZipDecryptsAndTamperIsRejected() throws Exception {
    android.content.res.AssetManager assets =
        InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    JSONObject fixture =
        new JSONObject(
            new String(
                ImageTransfer.boundedRead(assets.open("file-fixture.json"), 1024 * 1024),
                StandardCharsets.UTF_8));
    ClipCrypto crypto = new ClipCrypto(key());
    JSONObject clip = fixture.getJSONObject("clip");
    byte[] blob = Base64.decode(fixture.getString("blob"), Base64.NO_WRAP);
    assertEquals("sample.zip", crypto.decrypt(clip).getString("name"));
    assertArrayEquals(
        ImageTransfer.boundedRead(assets.open("sample.zip"), ClipCrypto.MAX_FILE),
        crypto.decryptAttachment(clip, blob));
    blob[blob.length - 1] ^= 1;
    try {
      crypto.decryptAttachment(clip, blob);
      fail("tampered file accepted");
    } catch (javax.crypto.AEADBadTagException expected) {
    }
    ClipCrypto.ImageEnvelope empty =
        crypto.encryptFile(new byte[0], "empty.txt", "text/plain", "Android");
    assertArrayEquals(new byte[0], crypto.decryptAttachment(empty.clip, empty.encrypted));
    assertEquals("notes.txt", ClipCrypto.safeFilename("../../notes.txt"));
    assertEquals("Shared file", ClipCrypto.safeFilename(".."));
  }

  @Test
  public void quietPreferencePersists() {
    IsolatedContext context =
        new IsolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    try {
      ClipStore store = new ClipStore(context);
      assertFalse(store.syncNotifications());
      store.syncNotifications(true);
      assertTrue(new ClipStore(context).syncNotifications());
      store.syncNotifications(false);
      assertFalse(new ClipStore(context).syncNotifications());
    } finally {
      context.cleanup();
    }
  }

  @Test
  public void launcherIconUsesDarkBackgroundAndPurple() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    android.graphics.drawable.Drawable icon =
        context.getPackageManager().getApplicationIcon(context.getPackageName());
    android.graphics.Bitmap image =
        android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
    icon.setBounds(0, 0, 256, 256);
    icon.draw(new android.graphics.Canvas(image));
    int purple = 0, dark = 0;
    for (int y = 0; y < 256; y++)
      for (int x = 0; x < 256; x++) {
        int color = image.getPixel(x, y) & 0xffffff;
        if (color == 0xb69cff) purple++;
        if (color == 0x111018) dark++;
      }
    assertTrue("Purple icon glyph missing", purple > 100);
    assertTrue("Dark icon background missing", dark > 1000);
    try (java.io.OutputStream output =
        new java.io.FileOutputStream(new File(context.getCacheDir(), "syncclip-icon-check.png"))) {
      image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
    }
  }

  @Test
  public void preferencesSurviveDisconnect() {
    IsolatedContext context =
        new IsolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    try {
      ClipStore store = new ClipStore(context);
      store.syncNotifications(true);
      context.getSharedPreferences("syncclip", 0).edit().putString("language", "ru").commit();
      store.unpair();
      assertTrue(new ClipStore(context).syncNotifications());
      assertEquals("ru", context.getSharedPreferences("syncclip", 0).getString("language", ""));
    } finally {
      context.cleanup();
    }
  }

  @Test
  public void deletionAndClearDiscardOldOutboxesAcrossRestart() throws Exception {
    IsolatedContext context =
        new IsolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    try {
      ClipStore store = new ClipStore(context);
      ClipCrypto crypto = new ClipCrypto(key());
      JSONObject clip = crypto.encrypt("Queued clip", "Test");
      store.queue(clip);
      store.remove(clip.getString("id"));
      store = new ClipStore(context);
      assertTrue(store.list("pending").isEmpty());
      assertTrue(store.isDeleted(clip.getString("id")));
      try {
        store.queue(clip);
        fail("deleted clip requeued");
      } catch (IllegalStateException expected) {
      }
      JSONObject next = crypto.encrypt("New copy", "Test");
      store.queue(next);
      store.reconcile(1, new JSONArray());
      assertTrue(store.list("pending").isEmpty());
      assertEquals(1, new ClipStore(context).epoch());
      store.queue(crypto.encrypt("After clear", "Test"));
      store.reconcile(1, new JSONArray());
      assertEquals(1, store.list("pending").size());
    } finally {
      context.cleanup();
    }
  }
}
