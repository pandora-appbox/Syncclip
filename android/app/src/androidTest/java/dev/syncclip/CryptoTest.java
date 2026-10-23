package dev.syncclip;

import static org.junit.Assert.*;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CryptoTest {
  @Test
  public void pythonImageEnvelopeDecryptsOnAndroid() throws Exception {
    android.content.res.AssetManager assets =
        InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    JSONObject fixture =
        new JSONObject(
            new String(
                ImageTransfer.boundedRead(assets.open("image-fixture.json"), 1024 * 1024),
                StandardCharsets.UTF_8));
    byte[] original =
        ImageTransfer.boundedRead(assets.open("screenshot.png"), ClipCrypto.MAX_IMAGE);
    ClipCrypto crypto = new ClipCrypto(key());
    byte[] blob = Base64.decode(fixture.getString("blob"), Base64.NO_WRAP);
    assertArrayEquals(original, crypto.decryptImage(fixture.getJSONObject("clip"), blob));
    blob[blob.length - 1] ^= 1;
    try {
      crypto.decryptImage(fixture.getJSONObject("clip"), blob);
      fail("Tampered image accepted");
    } catch (javax.crypto.AEADBadTagException expected) {
    }
  }

  private String key() {
    byte[] data = new byte[32];
    for (int i = 0; i < 32; i++) data[i] = (byte) i;
    return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  @Test
  public void pythonEnvelopeDecryptsOnAndroid() throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.InputStream input =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("crypto-fixture.json")) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
    }
    JSONObject fixture = new JSONObject(bytes.toString("UTF-8"));
    ClipCrypto crypto = new ClipCrypto(key());
    assertEquals(fixture.getString("authorization"), crypto.authorization);
    assertEquals(
        "Hello Android ↔ Linux 🐧\nSecond line",
        crypto.decrypt(fixture.getJSONObject("clip")).getString("text"));
  }

  @Test
  public void authenticatesPayloadAndEnvelopeId() throws Exception {
    ClipCrypto crypto = new ClipCrypto(key());
    JSONObject clip = crypto.encrypt("Unicode 🐧", "Android test");
    assertEquals("Unicode 🐧", crypto.decrypt(clip).getString("text"));
    clip.put("id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    try {
      crypto.decrypt(clip);
      fail("Modified ID accepted");
    } catch (javax.crypto.AEADBadTagException expected) {
    }
  }

  @Test
  public void validatesPairingAndLength() throws Exception {
    for (String url :
        new String[] {
          "http://example.com", "https://user:pass@example.com", "https://example.com?bad=yes"
        }) {
      try {
        ClipStore.parsePairing(
            "syncclip://pair?url=" + android.net.Uri.encode(url) + "&key=" + key());
        fail("Unsafe URL accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
    assertEquals(
        "https://example.com/syncclip",
        ClipStore.parsePairing(
            "syncclip://pair?url=https%3A%2F%2Fexample.com%2Fsyncclip&key=" + key())[0]);
    try {
      new ClipCrypto(key()).encrypt("", "Test");
      fail("Empty clip accepted");
    } catch (IllegalArgumentException expected) {
    }
  }
}
