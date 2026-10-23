package dev.syncclip;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DeviceCredentialsTest {
  @Test
  public void credentialsAreProtectedAndRePairPreservesSameGroupHistory() throws Exception {
    Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String prefix = "device_credential_test_" + UUID.randomUUID();
    File folder = new File(base.getCacheDir(), prefix);
    assertTrue(folder.mkdirs());
    Context context =
        new ContextWrapper(base) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return base.getSharedPreferences(prefix + name, mode);
          }

          @Override
          public File getFilesDir() {
            return folder;
          }
        };
    ClipStore store = new ClipStore(context);
    String key = Base64.encodeToString(new byte[32], Base64.URL_SAFE | Base64.NO_WRAP);
    String link =
        "syncclip://pair?url=https%3A%2F%2Fexample.invalid%2Fsyncclip&key="
            + key
            + "&invite="
            + "a".repeat(64);
    try {
      store.pair(link);
      String token = store.authorization(), id = store.deviceId();
      assertTrue(store.hasDeviceToken());
      assertFalse(store.enrolled());
      assertNotEquals(
          token.substring(7),
          context.getSharedPreferences("syncclip", 0).getString("device_token", ""));
      store.enrolled(id);
      assertTrue(store.enrolled());
      assertEquals("", store.invitation());
      JSONObject clip =
          new ClipCrypto(key)
              .encrypt("Synthetic preserved clip", "Test")
              .put("seq", 1)
              .put("created", System.currentTimeMillis());
      store.remember(Arrays.asList(clip));
      store.pair(link);
      assertEquals(id, store.deviceId());
      assertEquals(1, store.list("history").size());
      store.revoked();
      assertFalse(store.hasDeviceToken());
      assertFalse(store.enabled());
      store.pair(link);
      assertTrue(store.hasDeviceToken());
      assertNotEquals(id, store.deviceId());
      assertNotEquals(token, store.authorization());
      assertEquals(1, store.list("history").size());
      store.unpair();
      assertFalse(store.paired());
      assertFalse(store.hasDeviceToken());
      assertTrue(store.list("history").isEmpty());
    } finally {
      store.unpair();
      base.deleteSharedPreferences(prefix + "syncclip");
      folder.delete();
    }
  }

  @Test
  public void linuxGeneratedQrDecodesToOneUsePairingInvitation() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getContext();
    Bitmap image = BitmapFactory.decodeStream(context.getAssets().open("pairing-qr.png"));
    assertNotNull(image);
    int[] pixels = new int[image.getWidth() * image.getHeight()];
    image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
    String decoded =
        new QRCodeReader()
            .decode(
                new BinaryBitmap(
                    new HybridBinarizer(
                        new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels))))
            .getText();
    String[] fields = ClipStore.parsePairing(decoded);
    assertEquals("https://example.invalid/syncclip", fields[0]);
    assertEquals("a".repeat(64), fields[2]);
  }
}
