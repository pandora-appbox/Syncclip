package dev.syncclip;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ClipStore {
  private static final long TTL = 86_400_000L;
  private final SharedPreferences prefs;
  private final AtomicFile file;
  private JSONObject state;

  public ClipStore(Context context) {
    prefs = context.getSharedPreferences("syncclip", Context.MODE_PRIVATE);
    file = new AtomicFile(new File(context.getFilesDir(), "clips.json"));
    try {
      state = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
    } catch (Exception e) {
      state = new JSONObject();
    }
  }

  public boolean paired() {
    return prefs.contains("wrapped_key");
  }

  public String url() {
    return prefs.getString("url", "");
  }

  public boolean enabled() {
    return prefs.getBoolean("enabled", true);
  }

  public void enabled(boolean value) {
    prefs.edit().putBoolean("enabled", value).apply();
  }

  public boolean syncNotifications() {
    return prefs.getBoolean("sync_notifications", false);
  }

  public void syncNotifications(boolean enabled) {
    prefs.edit().putBoolean("sync_notifications", enabled).apply();
  }

  public synchronized long epoch() {
    return state.optLong("epoch", 0);
  }

  public synchronized boolean isDeleted(String id) {
    JSONArray deleted = state.optJSONArray("deleted");
    if (deleted != null)
      for (int i = 0; i < deleted.length(); i++) if (id.equals(deleted.optString(i))) return true;
    return false;
  }

  public synchronized boolean contains(String id) {
    for (String kind : new String[] {"history", "pending"})
      for (JSONObject clip : list(kind)) if (id.equals(clip.optString("id"))) return true;
    return false;
  }

  public synchronized void remove(String id) throws Exception {
    for (String kind : new String[] {"history", "pending"}) {
      List<JSONObject> clips = list(kind);
      clips.removeIf(c -> id.equals(c.optString("id")));
      state.put(kind, new JSONArray(clips));
    }
    JSONArray deleted = state.optJSONArray("deleted");
    if (deleted == null) deleted = new JSONArray();
    if (!isDeleted(id)) deleted.put(id);
    state.put("deleted", deleted);
    if (id.equals(lastId())) state.put("last_id", "");
    save();
  }

  public synchronized void reconcile(long epoch, JSONArray deleted) throws Exception {
    if (epoch != epoch()) {
      state
          .put("history", new JSONArray())
          .put("pending", new JSONArray())
          .put("last_id", "")
          .put("epoch", epoch);
    }
    state.put("deleted", deleted == null ? new JSONArray() : deleted);
    for (String kind : new String[] {"history", "pending"}) {
      List<JSONObject> clips = list(kind);
      clips.removeIf(c -> isDeleted(c.optString("id")));
      state.put(kind, new JSONArray(clips));
    }
    if (isDeleted(lastId())) state.put("last_id", "");
    save();
  }

  private SecretKey wrappingKey() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("syncclip.pairing")) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  "syncclip.pairing", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build());
      generator.generateKey();
    }
    return (SecretKey) ks.getKey("syncclip.pairing", null);
  }

  public String key() throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        wrappingKey(),
        new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(prefs.getString("wrapped_key", ""), Base64.NO_WRAP)),
        StandardCharsets.UTF_8);
  }

  public static String[] parsePairing(String value) throws Exception {
    Uri uri = Uri.parse(value.trim());
    if (!"syncclip".equals(uri.getScheme()) || !"pair".equals(uri.getHost()))
      throw new IllegalArgumentException("Paste a Syncclip pairing link");
    String url = uri.getQueryParameter("url"), key = uri.getQueryParameter("key");
    if (url == null || key == null) throw new IllegalArgumentException("Incomplete pairing link");
    java.net.URI server = new java.net.URI(url);
    if (!"https".equals(server.getScheme())
        || server.getHost() == null
        || server.getUserInfo() != null
        || server.getQuery() != null
        || server.getFragment() != null)
      throw new IllegalArgumentException("The server must be an HTTPS address");
    new ClipCrypto(key);
    return new String[] {url.replaceAll("/+$", ""), key};
  }

  public synchronized void pair(String link) throws Exception {
    String[] fields = parsePairing(link);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
    String wrapped =
        Base64.encodeToString(
            cipher.doFinal(fields[1].getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    if (!prefs
        .edit()
        .putString("url", fields[0])
        .putString("wrapped_key", wrapped)
        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putBoolean("enabled", true)
        .commit()) throw new IllegalStateException("Could not save pairing");
    state = new JSONObject();
    save();
  }

  public synchronized void unpair() {
    prefs.edit().remove("url").remove("wrapped_key").remove("iv").remove("enabled").commit();
    state = new JSONObject();
    file.delete();
  }

  private void save() throws Exception {
    FileOutputStream stream = file.startWrite();
    try {
      stream.write(state.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (Exception e) {
      file.failWrite(stream);
      throw e;
    }
  }

  public synchronized List<JSONObject> list(String kind) {
    List<JSONObject> clips = new ArrayList<>();
    JSONArray array = state.optJSONArray(kind);
    if (array != null)
      for (int i = 0; i < array.length(); i++) {
        JSONObject clip = array.optJSONObject(i);
        if (clip != null && clip.optLong("created") > System.currentTimeMillis() - TTL)
          clips.add(clip);
      }
    return clips;
  }

  public synchronized void prune() throws Exception {
    state.put("history", new JSONArray(list("history")));
    state.put("pending", new JSONArray(list("pending")));
    save();
  }

  public synchronized void queue(JSONObject clip) throws Exception {
    List<JSONObject> clips = list("pending");
    if (isDeleted(clip.getString("id"))) throw new IllegalStateException("This clip was deleted");
    if (clips.size() >= 20)
      throw new IllegalArgumentException("20 clips are waiting. Reconnect before sending more.");
    clip.put("created", System.currentTimeMillis());
    clips.add(clip);
    state.put("pending", new JSONArray(clips));
    save();
  }

  public synchronized void acknowledge(String id) throws Exception {
    List<JSONObject> clips = list("pending");
    clips.removeIf(c -> id.equals(c.optString("id")));
    state.put("pending", new JSONArray(clips));
    save();
  }

  public synchronized void remember(List<JSONObject> incoming) throws Exception {
    LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
    for (JSONObject c : list("history")) merged.put(c.optString("id"), c);
    for (JSONObject c : incoming)
      if (!isDeleted(c.optString("id"))) merged.put(c.optString("id"), c);
    List<JSONObject> clips = new ArrayList<>(merged.values());
    clips.sort(Comparator.comparingLong(c -> c.optLong("seq")));
    if (clips.size() > 50) clips = new ArrayList<>(clips.subList(clips.size() - 50, clips.size()));
    state.put("history", new JSONArray(clips));
    save();
  }

  public synchronized String lastId() {
    return state.optString("last_id");
  }

  public synchronized void lastId(String id) throws Exception {
    state.put("last_id", id);
    save();
  }
}
