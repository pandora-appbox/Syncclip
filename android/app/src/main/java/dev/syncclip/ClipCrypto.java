package dev.syncclip;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public final class ClipCrypto {
  public static final int MAX_TEXT = 64 * 1024;
  public static final int MAX_IMAGE = 8 * 1024 * 1024;
  public static final int MAX_FILE = 32 * 1024 * 1024;
  private final byte[] encryptionKey;
  public final String authorization;

  public ClipCrypto(String encodedKey) throws Exception {
    byte[] root = Base64.decode(encodedKey, Base64.URL_SAFE | Base64.NO_WRAP);
    if (root.length != 32) throw new IllegalArgumentException("Invalid pairing key");
    encryptionKey = derive(root, "syncclip-encryption-v1");
    StringBuilder hex = new StringBuilder();
    for (byte b : derive(root, "syncclip-auth-v1")) hex.append(String.format("%02x", b & 255));
    authorization = "Bearer " + hex;
  }

  private static byte[] derive(byte[] key, String label) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
  }

  public JSONObject encrypt(String text, String device) throws Exception {
    if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT)
      throw new IllegalArgumentException("Send between 1 byte and 64 KB of text");
    return envelope(new JSONObject().put("text", text), device, UUID.randomUUID().toString());
  }

  private JSONObject envelope(JSONObject payload, String device, String id) throws Exception {
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    payload
        .put("device", device.substring(0, Math.min(80, device.length())))
        .put("sent", System.currentTimeMillis());
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("syncclip-v1:" + id).getBytes(StandardCharsets.UTF_8));
    return new JSONObject()
        .put("id", id)
        .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
        .put(
            "ciphertext",
            Base64.encodeToString(
                cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP));
  }

  public static final class ImageEnvelope {
    public final JSONObject clip;
    public final byte[] encrypted;

    ImageEnvelope(JSONObject clip, byte[] encrypted) {
      this.clip = clip;
      this.encrypted = encrypted;
    }
  }

  public static String imageType(byte[] data) {
    if (data.length >= 8
        && data[0] == (byte) 137
        && data[1] == 80
        && data[2] == 78
        && data[3] == 71
        && data[4] == 13
        && data[5] == 10
        && data[6] == 26
        && data[7] == 10) return "image/png";
    if (data.length >= 3 && data[0] == (byte) 255 && data[1] == (byte) 216 && data[2] == (byte) 255)
      return "image/jpeg";
    if (data.length >= 12
        && new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
    throw new IllegalArgumentException("Choose a PNG, JPEG, or WebP image");
  }

  public ImageEnvelope encryptImage(byte[] data, String device) throws Exception {
    if (data.length == 0 || data.length > MAX_IMAGE)
      throw new IllegalArgumentException("Images can be up to 8 MB");
    String mime = imageType(data), id = UUID.randomUUID().toString();
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("syncclip-image-v1:" + id).getBytes(StandardCharsets.UTF_8));
    JSONObject payload =
        new JSONObject()
            .put("kind", "image")
            .put("mime", mime)
            .put("bytes", data.length)
            .put("image_nonce", Base64.encodeToString(nonce, Base64.NO_WRAP));
    return new ImageEnvelope(envelope(payload, device, id).put("blob", id), cipher.doFinal(data));
  }

  public static String safeFilename(String value) {
    String name = value.replace((char) 92, '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    StringBuilder clean = new StringBuilder();
    name.codePoints()
        .filter(
            c ->
                !Character.isISOControl(c)
                    && Character.getType(c) != Character.FORMAT
                    && c != '/'
                    && c != 92)
        .limit(180)
        .forEach(clean::appendCodePoint);
    name = clean.toString().trim();
    return name.isEmpty() || name.equals(".") || name.equals("..") ? "Shared file" : name;
  }

  public ImageEnvelope encryptFile(byte[] data, String name, String mime, String device)
      throws Exception {
    if (data.length > MAX_FILE) throw new IllegalArgumentException("Files can be up to 32 MB");
    String id = UUID.randomUUID().toString();
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("syncclip-file-v1:" + id).getBytes(StandardCharsets.UTF_8));
    JSONObject payload =
        new JSONObject()
            .put("kind", "file")
            .put("name", safeFilename(name))
            .put("mime", mime)
            .put("bytes", data.length)
            .put("file_nonce", Base64.encodeToString(nonce, Base64.NO_WRAP));
    return new ImageEnvelope(envelope(payload, device, id).put("blob", id), cipher.doFinal(data));
  }

  public byte[] decryptAttachment(JSONObject clip, byte[] encrypted) throws Exception {
    JSONObject payload = decrypt(clip);
    if (!"file".equals(payload.optString("kind"))) return decryptImage(clip, encrypted);
    if (encrypted.length != payload.getInt("bytes") + 16)
      throw new IllegalArgumentException("Invalid attachment size");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, Base64.decode(payload.getString("file_nonce"), Base64.NO_WRAP)));
    cipher.updateAAD(("syncclip-file-v1:" + clip.getString("id")).getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(encrypted);
  }

  public byte[] decryptImage(JSONObject clip, byte[] encrypted) throws Exception {
    JSONObject payload = decrypt(clip);
    if (!"image".equals(payload.optString("kind"))
        || encrypted.length != payload.getInt("bytes") + 16)
      throw new IllegalArgumentException("Invalid image attachment");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, Base64.decode(payload.getString("image_nonce"), Base64.NO_WRAP)));
    cipher.updateAAD(
        ("syncclip-image-v1:" + clip.getString("id")).getBytes(StandardCharsets.UTF_8));
    byte[] data = cipher.doFinal(encrypted);
    if (!imageType(data).equals(payload.getString("mime")))
      throw new IllegalArgumentException("Image format mismatch");
    return data;
  }

  public JSONObject decrypt(JSONObject clip) throws Exception {
    byte[] nonce = Base64.decode(clip.getString("nonce"), Base64.NO_WRAP);
    if (nonce.length != 12) throw new IllegalArgumentException("Invalid nonce");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(encryptionKey, "AES"),
        new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("syncclip-v1:" + clip.getString("id")).getBytes(StandardCharsets.UTF_8));
    JSONObject payload =
        new JSONObject(
            new String(
                cipher.doFinal(Base64.decode(clip.getString("ciphertext"), Base64.NO_WRAP)),
                StandardCharsets.UTF_8));
    if ("file".equals(payload.optString("kind"))) {
      String name = payload.getString("name"), mime = payload.getString("mime");
      long bytes = payload.getLong("bytes");
      if (!(payload.get("device") instanceof String)
          || !safeFilename(name).equals(name)
          || !mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")
          || bytes < 0
          || bytes > MAX_FILE
          || !clip.getString("id").equals(clip.optString("blob"))
          || Base64.decode(payload.optString("file_nonce"), Base64.NO_WRAP).length != 12)
        throw new IllegalArgumentException("Invalid file description");
      return payload;
    }
    if ("image".equals(payload.optString("kind"))) {
      String mime = payload.optString("mime");
      int bytes = payload.optInt("bytes");
      if (!(payload.get("device") instanceof String)
          || !(mime.equals("image/png") || mime.equals("image/jpeg") || mime.equals("image/webp"))
          || bytes <= 0
          || bytes > MAX_IMAGE
          || !clip.getString("id").equals(clip.optString("blob"))
          || Base64.decode(payload.optString("image_nonce"), Base64.NO_WRAP).length != 12)
        throw new IllegalArgumentException("Invalid image description");
      return payload;
    }
    if (!(payload.get("text") instanceof String)
        || payload.getString("text").getBytes(StandardCharsets.UTF_8).length > MAX_TEXT
        || !(payload.get("device") instanceof String))
      throw new IllegalArgumentException("Invalid clipboard content");
    return payload;
  }
}
