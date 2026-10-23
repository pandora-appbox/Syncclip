package dev.syncclip;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import org.json.JSONObject;

/** Grants access to a single decrypted image stream; never exports a key or plaintext file. */
public final class ImageProvider extends ContentProvider {
  private SyncApp app() {
    return (SyncApp) getContext().getApplicationContext();
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  private JSONObject clip(Uri uri) throws FileNotFoundException {
    if (uri.getPathSegments().size() != 1) throw new FileNotFoundException("Unknown image");
    JSONObject clip = app().images.find(uri.getLastPathSegment());
    if (clip == null) throw new FileNotFoundException("Image expired");
    return clip;
  }

  @Override
  public String getType(Uri uri) {
    try {
      return new ClipCrypto(app().store.key()).decrypt(clip(uri)).getString("mime");
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    JSONObject clip = clip(uri);
    try {
      ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
      app()
          .images
          .streams
          .execute(
              () -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                  output.write(app().images.load(clip));
                } catch (Exception error) {
                  try {
                    pipe[1].closeWithError("Image unavailable");
                  } catch (Exception ignored) {
                  }
                }
              });
      return pipe[0];
    } catch (Exception error) {
      throw new FileNotFoundException("Image unavailable");
    }
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      JSONObject content = new ClipCrypto(app().store.key()).decrypt(clip(uri));
      String[] columns =
          projection != null
              ? projection
              : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i]))
          values[i] =
              content.has("name")
                  ? content.getString("name")
                  : "Screenshot."
                      + (content.getString("mime").equals("image/jpeg")
                          ? "jpg"
                          : content.getString("mime").equals("image/webp") ? "webp" : "png");
        else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = content.getInt("bytes");
      }
      cursor.addRow(values);
      return cursor;
    } catch (Exception error) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }
}
