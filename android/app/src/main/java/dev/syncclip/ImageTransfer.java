package dev.syncclip;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** Encrypted attachments use HTTPS separately from the small live clipboard messages. */
public final class ImageTransfer {
  public final ExecutorService workers = Executors.newFixedThreadPool(2);
  public final ExecutorService streams = Executors.newFixedThreadPool(2);
  private final Context context;
  private final ClipStore store;
  private final OkHttpClient http =
      new OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build();
  private final File folder;

  public ImageTransfer(Context context, ClipStore store) {
    this.context = context;
    this.store = store;
    folder = new File(context.getFilesDir(), "images");
    folder.mkdirs();
  }

  public File path(String id) {
    if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid image ID");
    return new File(folder, id);
  }

  public void save(String id, byte[] encrypted) throws Exception {
    if (encrypted.length > ClipCrypto.MAX_FILE + 16)
      throw new IllegalArgumentException("Image too large");
    synchronized (store) {
      if (store.isDeleted(id)) throw new IllegalStateException("This clip was deleted");
      AtomicFile file = new AtomicFile(path(id));
      FileOutputStream output = file.startWrite();
      try {
        output.write(encrypted);
        file.finishWrite(output);
      } catch (Exception error) {
        file.failWrite(output);
        throw error;
      }
    }
  }

  public static byte[] boundedRead(InputStream input, int limit) throws Exception {
    if (input == null) throw new IllegalArgumentException("Could not open this image");
    try (input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int size = 0, count;
      while ((count = input.read(buffer)) != -1) {
        size += count;
        if (size > limit) throw new IllegalArgumentException("File is too large");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  public byte[] read(Uri uri) throws Exception {
    if (!"content".equals(uri.getScheme()))
      throw new IllegalArgumentException("Share an image from your screenshot or gallery app");
    byte[] data =
        boundedRead(context.getContentResolver().openInputStream(uri), ClipCrypto.MAX_IMAGE);
    bounds(data);
    return data;
  }

  public static final class Attachment {
    public final byte[] data;
    public final String name, mime;

    Attachment(byte[] data, String name, String mime) {
      this.data = data;
      this.name = name;
      this.mime = mime;
    }
  }

  public Attachment readAttachment(Uri uri) throws Exception {
    if (!"content".equals(uri.getScheme()))
      throw new IllegalArgumentException("Choose a file from a document or gallery app");
    String name = "Shared file";
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
        int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (sizeColumn >= 0
            && !cursor.isNull(sizeColumn)
            && cursor.getLong(sizeColumn) > ClipCrypto.MAX_FILE)
          throw new IllegalArgumentException("Files can be up to 32 MB");
        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
      }
    }
    byte[] data =
        boundedRead(context.getContentResolver().openInputStream(uri), ClipCrypto.MAX_FILE);
    String mime = context.getContentResolver().getType(uri);
    if (mime == null || !mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))
      mime = "application/octet-stream";
    return new Attachment(data, ClipCrypto.safeFilename(name), mime);
  }

  public void remove(String id) {
    new AtomicFile(path(id)).delete();
    context.revokeUriPermission(uri(id), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  private static BitmapFactory.Options bounds(byte[] data) {
    ClipCrypto.imageType(data);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (options.outWidth <= 0
        || options.outHeight <= 0
        || (long) options.outWidth * options.outHeight > 24_000_000L)
      throw new IllegalArgumentException("Images can be up to 24 megapixels");
    return options;
  }

  public static Bitmap thumbnail(byte[] data) {
    BitmapFactory.Options options = bounds(data);
    options.inJustDecodeBounds = false;
    options.inSampleSize = 1;
    while (options.outWidth / options.inSampleSize > 800
        || options.outHeight / options.inSampleSize > 400) options.inSampleSize *= 2;
    return BitmapFactory.decodeByteArray(data, 0, data.length, options);
  }

  public void upload(JSONObject clip) throws Exception {
    ClipCrypto crypto = new ClipCrypto(store.key());
    byte[] encrypted = Files.readAllBytes(path(clip.getString("id")).toPath());
    Request request =
        new Request.Builder()
            .url(store.url() + "/v1/blobs/" + clip.getString("id"))
            .header("Authorization", crypto.authorization)
            .header("X-Syncclip-Epoch", Long.toString(store.epoch()))
            .put(RequestBody.create(encrypted, MediaType.get("application/octet-stream")))
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IllegalStateException("Image upload failed");
    }
  }

  public byte[] load(JSONObject clip) throws Exception {
    long epoch = store.epoch();
    ClipCrypto crypto = new ClipCrypto(store.key());
    File file = path(clip.getString("id"));
    byte[] encrypted;
    if (file.exists()) encrypted = Files.readAllBytes(file.toPath());
    else {
      Request request =
          new Request.Builder()
              .url(store.url() + "/v1/blobs/" + clip.getString("id"))
              .header("Authorization", crypto.authorization)
              .build();
      try (Response response = http.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null)
          throw new IllegalStateException("Image is unavailable. Try again when connected.");
        encrypted = boundedRead(response.body().byteStream(), ClipCrypto.MAX_FILE + 16);
      }
      crypto.decryptAttachment(clip, encrypted);
      synchronized (store) {
        if (epoch != store.epoch()) throw new IllegalStateException("History was cleared");
        save(clip.getString("id"), encrypted);
      }
    }
    byte[] data = crypto.decryptAttachment(clip, encrypted);
    if ("image".equals(crypto.decrypt(clip).optString("kind"))) bounds(data);
    return data;
  }

  public JSONObject find(String id) {
    for (String key : new String[] {"history", "pending"})
      for (JSONObject clip : store.list(key)) if (id.equals(clip.optString("blob"))) return clip;
    return null;
  }

  public Uri uri(String id) {
    path(id);
    return new Uri.Builder()
        .scheme("content")
        .authority(context.getPackageName() + ".images")
        .appendPath(id)
        .build();
  }

  public void prune() {
    Set<String> ids = new HashSet<>();
    for (String key : new String[] {"history", "pending"})
      for (JSONObject clip : store.list(key)) ids.add(clip.optString("blob"));
    File[] files = folder.listFiles();
    if (files != null)
      for (File file : files)
        if (!ids.contains(file.getName())
            && System.currentTimeMillis() - file.lastModified() > 3_600_000L) file.delete();
  }

  public void clear() {
    File[] files = folder.listFiles();
    if (files != null) for (File file : files) file.delete();
  }
}
