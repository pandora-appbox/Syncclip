package dev.syncclip;

import android.net.Uri;
import android.os.Build;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** One credential per device. The shared content-encryption key cannot authorize sync. */
public final class DeviceClient {
  public static final class Revoked extends IOException {}

  private final ClipStore store;
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .callTimeout(12, TimeUnit.SECONDS)
          .build();

  public DeviceClient(ClipStore store) {
    this.store = store;
  }

  private JSONObject request(String path, String method, JSONObject data, String authorization)
      throws Exception {
    RequestBody body =
        (data != null || method.equals("POST"))
            ? RequestBody.create(
                data == null ? "{}" : data.toString(), MediaType.get("application/json"))
            : null;
    Request request =
        new Request.Builder()
            .url(store.url() + path)
            .header("Authorization", authorization)
            .method(method, body)
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (response.code() == 401) throw new Revoked();
      if (!response.isSuccessful())
        throw new IOException("Device request failed. Reconnect and retry.");
      if (response.code() == 204 || response.body() == null) return new JSONObject();
      byte[] bytes = ImageTransfer.boundedRead(response.body().byteStream(), 65536);
      return bytes.length == 0
          ? new JSONObject()
          : new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  public synchronized JSONObject ensure() throws Exception {
    if (!store.hasDeviceToken())
      throw new IllegalStateException("Pair again using a new invitation");
    String id = store.deviceId(), authorization = store.authorization();
    JSONObject result;
    try {
      result = request("/v1/devices", "GET", null, authorization);
    } catch (Revoked error) {
      if (store.enrolled()) throw error;
      String invite = store.invitation();
      if (invite.isEmpty()) throw error;
      JSONObject input =
          new JSONObject()
              .put("id", id)
              .put("name", Build.MODEL)
              .put("platform", "android")
              .put("token", authorization.substring(7));
      request("/v1/enroll", "POST", input, "Bearer " + invite);
      result = request("/v1/devices", "GET", null, authorization);
    }
    store.enrolled(id);
    return result;
  }

  public synchronized String invite() throws Exception {
    ensure();
    JSONObject result = request("/v1/invitations", "POST", null, store.authorization());
    return new Uri.Builder()
        .scheme("syncclip")
        .authority("pair")
        .appendQueryParameter("url", store.url())
        .appendQueryParameter("key", store.key())
        .appendQueryParameter("invite", result.getString("invite"))
        .build()
        .toString();
  }

  public synchronized void disconnect(String id) throws Exception {
    if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid device");
    request("/v1/devices/" + id, "DELETE", null, store.authorization());
  }
}
