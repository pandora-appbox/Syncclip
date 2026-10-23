package dev.syncclip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SyncEngine {
  public interface Observer {
    void changed();
  }

  private final Context context;
  private final ClipStore store;
  private final ImageTransfer images;
  private final Set<String> uploading = new HashSet<>(), uploaded = new HashSet<>();
  private int copyGeneration = 0;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<Observer> observers = new HashSet<>();
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .pingInterval(25, TimeUnit.SECONDS)
          .connectTimeout(10, TimeUnit.SECONDS)
          .build();
  private WebSocket socket;
  private ClipCrypto crypto;
  private boolean running = false, connected = false;
  private int generation = 0, retry = 1;
  public volatile String status = "Not paired";
  private final Runnable resend =
      new Runnable() {
        @Override
        public void run() {
          if (running) {
            sendPending();
            try {
              store.prune();
              images.workers.execute(images::prune);
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 15_000);
          }
        }
      };

  public SyncEngine(Context context, ClipStore store) {
    this.context = context;
    this.store = store;
    this.images = ((SyncApp) context.getApplicationContext()).images;
  }

  public void observe(Observer observer) {
    observers.add(observer);
  }

  public void remove(Observer observer) {
    observers.remove(observer);
  }

  private void changed() {
    for (Observer o : new ArrayList<>(observers)) o.changed();
  }

  private void status(String value) {
    status = value;
    changed();
  }

  public void start() {
    if (running || !store.paired() || !store.enabled()) return;
    try {
      crypto = new ClipCrypto(store.key());
    } catch (Exception e) {
      status("Pairing could not be opened. Please pair again.");
      return;
    }
    running = true;
    retry = 1;
    connect();
    main.post(resend);
  }

  public void stop() {
    running = false;
    connected = false;
    generation++;
    copyGeneration++;
    uploading.clear();
    uploaded.clear();
    main.removeCallbacks(resend);
    if (socket != null) {
      socket.cancel();
      socket = null;
    }
    crypto = null;
    status(store.paired() ? "Paused" : "Not paired");
  }

  private void connect() {
    if (!running) return;
    final int current = ++generation;
    uploading.clear();
    uploaded.clear();
    status("Connecting…");
    Request request =
        new Request.Builder()
            .url(store.url().replaceFirst("^https://", "wss://") + "/v1/ws")
            .header("Authorization", crypto.authorization)
            .build();
    socket =
        client.newWebSocket(
            request,
            new WebSocketListener() {
              @Override
              public void onMessage(WebSocket ws, String text) {
                main.post(
                    () -> {
                      if (current == generation) receive(text);
                    });
              }

              @Override
              public void onFailure(WebSocket ws, Throwable t, Response response) {
                main.post(() -> disconnected(current, response != null && response.code() == 401));
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                main.post(() -> disconnected(current, false));
              }
            });
  }

  private void disconnected(int current, boolean denied) {
    if (current != generation || !running) return;
    connected = false;
    socket = null;
    status(
        denied
            ? "Pairing rejected. Please pair again."
            : "Offline · clips will send when reconnected");
    int delay = retry;
    retry = Math.min(30, retry * 2);
    main.postDelayed(
        () -> {
          if (running && current == generation) connect();
        },
        delay * 1000L + (long) (Math.random() * 500));
  }

  public void send(String text) throws Exception {
    if (!store.paired()) throw new IllegalStateException("Connect your devices first");
    if (text.trim().startsWith("syncclip://pair?"))
      throw new IllegalArgumentException("Pairing links are private and are never synced");
    if (crypto == null) crypto = new ClipCrypto(store.key());
    copyGeneration++;
    store.queue(crypto.encrypt(text, Build.MODEL));
    sendPending();
    changed();
  }

  public void sendImage(Uri uri) {
    if (!store.paired()) {
      status("Connect your devices first");
      return;
    }
    final String key, url = store.url();
    try {
      key = store.key();
    } catch (Exception e) {
      status("Please pair again");
      return;
    }
    copyGeneration++;
    status("Preparing file…");
    images.workers.execute(
        () -> {
          try {
            ImageTransfer.Attachment attachment = images.readAttachment(uri);
            ClipCrypto fileCrypto = new ClipCrypto(key);
            ClipCrypto.ImageEnvelope result;
            String imageMime = null;
            try {
              imageMime = ClipCrypto.imageType(attachment.data);
            } catch (IllegalArgumentException ignored) {
            }
            if (imageMime != null && attachment.data.length <= ClipCrypto.MAX_IMAGE) {
              // The decoder enforces the image pixel limit before any preview is made.
              images.read(uri);
              result = fileCrypto.encryptImage(attachment.data, Build.MODEL);
            } else
              result =
                  fileCrypto.encryptFile(
                      attachment.data, attachment.name, attachment.mime, Build.MODEL);
            images.save(result.clip.getString("id"), result.encrypted);
            main.post(
                () -> {
                  if (!samePairing(url, key)) return;
                  try {
                    store.queue(result.clip);
                    status(connected ? "Connected" : "Offline · file queued");
                    sendPending();
                    changed();
                  } catch (Exception e) {
                    status(e.getMessage());
                  }
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (samePairing(url, key))
                    status(
                        error.getMessage() == null
                            ? "Could not open this file"
                            : error.getMessage());
                });
          }
        });
  }

  public void clearHistory(String id) {
    if (!connected || socket == null) {
      status("Reconnect to clear shared history");
      return;
    }
    try {
      JSONObject request =
          new JSONObject()
              .put("type", id == null ? "clear" : "delete")
              .put("id", id == null ? UUID.randomUUID().toString() : id)
              .put("epoch", store.epoch());
      if (!socket.send(request.toString())) {
        status("Could not clear history. Reconnect and retry.");
        return;
      }
      status("Clearing shared history…");
    } catch (Exception e) {
      status("Could not clear history. Please retry.");
    }
  }

  private void reconcile(JSONObject message) throws Exception {
    synchronized (store) {
      long epoch = message.optLong("epoch", 0);
      boolean cleared = epoch != store.epoch();
      JSONArray deleted = message.optJSONArray("deleted");
      copyGeneration++;
      if (cleared) {
        for (JSONObject clip : store.list("history"))
          if (clip.has("blob")) images.remove(clip.getString("id"));
      }
      store.reconcile(epoch, deleted);
      if (cleared) {
        images.clear();
        uploading.clear();
        uploaded.clear();
      } else if (deleted != null)
        for (int i = 0; i < deleted.length(); i++) images.remove(deleted.getString(i));
    }
  }

  private boolean samePairing(String url, String key) {
    try {
      return store.paired() && url.equals(store.url()) && key.equals(store.key());
    } catch (Exception e) {
      return false;
    }
  }

  private void sendPending() {
    if (!connected || socket == null) return;
    try {
      for (JSONObject clip : store.list("pending")) {
        String id = clip.getString("id");
        if (clip.has("blob") && !uploaded.contains(id)) {
          if (uploading.add(id)) {
            final int current = generation;
            images.workers.execute(
                () -> {
                  try {
                    images.upload(clip);
                    main.post(
                        () -> {
                          if (current == generation) {
                            uploading.remove(id);
                            uploaded.add(id);
                            sendPending();
                          }
                        });
                  } catch (Exception error) {
                    main.post(
                        () -> {
                          if (current == generation) {
                            uploading.remove(id);
                            status("File waiting · retrying upload");
                          }
                        });
                  }
                });
          }
          continue;
        }
        if (!socket.send(
            new JSONObject()
                .put("type", "publish")
                .put("clip", clip)
                .put("epoch", store.epoch())
                .toString())) break;
      }
    } catch (Exception e) {
      status("Could not send. Your clips are queued.");
    }
  }

  private void receive(String raw) {
    try {
      JSONObject message = new JSONObject(raw);
      String type = message.optString("type");
      if (type.equals("hello")) reconcile(message);
      if (type.equals("cleared")) {
        reconcile(message);
        context.getSystemService(NotificationManager.class).cancel(2);
        status("Shared history cleared");
        return;
      }
      if (type.equals("deleted")) {
        String id = message.getString("id");
        copyGeneration++;
        store.remove(id);
        images.remove(id);
        context.getSystemService(NotificationManager.class).cancel(2);
        status("Clip deleted");
        return;
      }
      if (type.equals("ack")) {
        store.acknowledge(message.getString("id"));
        uploaded.remove(message.getString("id"));
        changed();
        return;
      }
      if (type.equals("error")) {
        status(message.optString("error", "Sync unavailable"));
        return;
      }
      boolean hello = type.equals("hello");
      List<JSONObject> incoming = new ArrayList<>();
      if (hello) {
        JSONArray clips = message.optJSONArray("clips");
        if (clips != null)
          for (int i = 0; i < clips.length(); i++) incoming.add(clips.getJSONObject(i));
      } else if (type.equals("clip")) incoming.add(message.getJSONObject("clip"));
      else return;
      List<JSONObject> valid = new ArrayList<>();
      for (JSONObject clip : incoming) {
        try {
          crypto.decrypt(clip);
          if (!store.isDeleted(clip.getString("id"))) valid.add(clip);
        } catch (Exception e) {
          status("A clip could not be decrypted. Check the pairing key.");
        }
      }
      List<JSONObject> pending = store.list("pending");
      Set<String> own = new HashSet<>();
      for (JSONObject c : pending) own.add(c.getString("id"));
      store.remember(valid);
      if (!valid.isEmpty()) {
        JSONObject last = valid.get(valid.size() - 1);
        String id = last.getString("id");
        boolean awaitingImage = false;
        if (!id.equals(store.lastId()) && !own.contains(id) && (!hello || pending.isEmpty())) {
          JSONObject content = crypto.decrypt(last);
          if (last.has("blob")) {
            awaitingImage = true;
            copyClip(last, () -> notifyClip(content.optString("device"), id), true);
          } else {
            copy(content.getString("text"));
            notifyClip(content.getString("device"), id);
          }
        }
        if (!awaitingImage) store.lastId(id);
      }
      if (hello) {
        connected = true;
        retry = 1;
        status("Connected");
        sendPending();
      }
      changed();
    } catch (Exception e) {
      status("Could not read a shared clip");
    }
  }

  public void copy(String text) {
    copyGeneration++;
    ClipData clip = ClipData.newPlainText("Syncclip", text);
    PersistableBundle extras = new PersistableBundle();
    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
    clip.getDescription().setExtras(extras);
    ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
  }

  public void copyClip(JSONObject envelope, Runnable done) {
    copyClip(envelope, done, false);
  }

  private void copyClip(JSONObject envelope, Runnable done, boolean markReceived) {
    try {
      JSONObject content = new ClipCrypto(store.key()).decrypt(envelope);
      if (!envelope.has("blob")) {
        copy(content.getString("text"));
        if (done != null) done.run();
        return;
      }
      final int current = ++copyGeneration;
      status("image".equals(content.optString("kind")) ? "Receiving image…" : "Receiving file…");
      images.workers.execute(
          () -> {
            try {
              images.load(envelope);
              main.post(
                  () -> {
                    if (current != copyGeneration || !store.contains(envelope.optString("id")))
                      return;
                    try {
                      ClipData clip =
                          ClipData.newUri(
                              context.getContentResolver(),
                              "Syncclip file",
                              images.uri(envelope.getString("id")));
                      PersistableBundle extras = new PersistableBundle();
                      extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                      clip.getDescription().setExtras(extras);
                      context.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                      if (markReceived) store.lastId(envelope.getString("id"));
                      status(connected ? "Connected" : "Offline · image copied");
                      if (done != null) done.run();
                      changed();
                    } catch (Exception error) {
                      status("Could not copy this file");
                    }
                  });
            } catch (Exception error) {
              main.post(
                  () -> {
                    if (current == copyGeneration)
                      status("Could not receive file. Use Copy to retry.");
                  });
            }
          });
    } catch (Exception error) {
      status("Could not read this clip");
    }
  }

  private void notifyClip(String device, String id) {
    if (!store.syncNotifications()) return;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            "clips", L.tr(context, "Received clips"), NotificationManager.IMPORTANCE_DEFAULT));
    Intent intent = ClipboardActions.intent(context, "dev.syncclip.COPY").putExtra("clip_id", id);
    PendingIntent tap =
        PendingIntent.getActivity(
            context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new Notification.Builder(context, "clips")
            .setSmallIcon(R.drawable.ic_stat_syncclip)
            .setContentTitle(L.fmt(context, "Clipboard from %s", device))
            .setContentText(L.tr(context, "Ready to paste · tap to copy again"))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    try {
      manager.notify(2, notification);
    } catch (SecurityException ignored) {
    }
  }
}
