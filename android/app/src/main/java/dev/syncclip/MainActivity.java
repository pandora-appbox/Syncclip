package dev.syncclip;

import static dev.syncclip.NativeUi.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONObject;

public final class MainActivity extends Activity implements SyncEngine.Observer {
  private SyncApp app;
  private NativeUi ui;
  private boolean builtPaired;
  private String exportId;
  private String builtLanguage;
  private LinearLayout root, history;
  private TextView status, pending;
  private EditText pairing;
  private Switch enabled;
  private String deferredAction, sharedText, copyId;
  private boolean updating = false;
  private final List<Uri> sharedFiles = new ArrayList<>();
  private int historyGeneration = 0;
  private final LinkedHashMap<String, Bitmap> thumbnails = new LinkedHashMap<>();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (SyncApp) getApplication();
    ui = new NativeUi(this);
    if (state != null) exportId = state.getString("export_id");
    build();
    handleIntent(getIntent());
    if (app.store.paired() && app.store.enabled()) startSync();
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (builtPaired != (app.store.paired() && app.store.hasDeviceToken())
        || !L.language(this).equals(builtLanguage)) build();
    app.engine.observe(this);
    changed();
  }

  @Override
  protected void onStop() {
    app.engine.remove(this);
    super.onStop();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
    if (hasWindowFocus()) runDeferred();
  }

  private int dp(int value) {
    return ui.dp(value);
  }

  private GradientDrawable background(int color, int radius) {
    return ui.background(color, radius);
  }

  private TextView text(String value, int size, int color) {
    return ui.text(value, size, color);
  }

  private void space(LinearLayout box, int height) {
    ui.space(box, height);
  }

  private Button button(String title, boolean primary, Runnable action) {
    return ui.button(title, primary, action);
  }

  private LinearLayout card(LinearLayout parent) {
    return ui.card(parent);
  }

  private void build() {
    builtPaired = app.store.paired() && app.store.hasDeviceToken();
    builtLanguage = L.language(this);
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackgroundColor(BG);
    setContentView(shell);
    LinearLayout heading = new LinearLayout(this);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.setPadding(dp(18), dp(12), dp(18), dp(8));
    TextView title = text(L.tr(this, "Syncclip"), 28, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(
        button("Settings", false, () -> startActivity(new Intent(this, SettingsActivity.class))));
    shell.addView(heading);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(0, dp(8), 0, dp(24));
    scroll.addView(root);
    if (Build.VERSION.SDK_INT >= 30)
      shell.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });
    space(root, 16);
    status = text(L.tr(this, app.engine.status), 14, ACCENT);
    status.setPadding(dp(18), 0, dp(18), dp(8));
    root.addView(status);
    space(root, 12);
    if (!app.store.paired() || !app.store.hasDeviceToken()) {
      buildPairing();
      return;
    }
    LinearLayout actions = card(root);
    enabled = new Switch(this);
    enabled.setText(L.tr(this, "Keep devices connected"));
    enabled.setTextColor(TEXT);
    enabled.setTextSize(16);
    enabled.setChecked(app.store.enabled());
    enabled.setPadding(0, 0, 0, dp(12));
    enabled.setOnCheckedChangeListener(
        (b, value) -> {
          if (updating) return;
          app.store.enabled(value);
          if (value) startSync();
          else {
            stopService(new Intent(this, SyncService.class));
            app.engine.stop();
          }
          changed();
        });
    actions.addView(enabled);
    actions.addView(
        text(
            L.tr(
                this,
                "Copy text, screenshots or files on your computer. Send from this phone using Share"
                    + " or tap below."),
            14,
            MUTED));
    space(actions, 6);
    actions.addView(button("Send clipboard", true, this::sendClipboard));
    space(actions, 10);
    actions.addView(
        button(
            "Choose files",
            false,
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                    21)));
    space(actions, 10);
    pending = text(L.tr(this, ""), 13, MUTED);
    actions.addView(pending);
    LinearLayout recent = new LinearLayout(this);
    recent.setGravity(Gravity.CENTER_VERTICAL);
    recent.setPadding(dp(18), 0, dp(18), 0);
    recent.addView(
        text(L.tr(this, "RECENT CLIPS"), 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    recent.addView(
        button("Clear history", false, () -> SettingsActivity.confirmClear(this, app, null)));
    root.addView(recent);
    space(root, 4);
    history = new LinearLayout(this);
    history.setOrientation(LinearLayout.VERTICAL);
    root.addView(history);
    space(root, 8);
    root.addView(
        text(
            L.tr(
                this,
                "Text, images and files · end-to-end encrypted\n"
                    + "Files up to 32 MB · history clears after 24 hours"),
            13,
            MUTED));

    changed();
  }

  private void buildPairing() {
    LinearLayout box = card(root);
    box.addView(text(L.tr(this, "Connect your devices"), 21, TEXT));
    box.addView(
        text(
            L.tr(
                this,
                "On your computer, open Syncclip → Settings → Pair another device. Scan the code or"
                    + " paste the pairing link."),
            15,
            MUTED));
    space(box, 12);
    box.addView(
        button(
            "Scan pairing code",
            true,
            () ->
                new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt(L.tr(this, "Scan the code in Syncclip on your computer"))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .initiateScan()));
    space(box, 14);
    pairing = new EditText(this);
    pairing.setHint(L.tr(this, "syncclip://pair?…"));
    pairing.setTextSize(14);
    pairing.setTextColor(TEXT);
    pairing.setHintTextColor(MUTED);
    pairing.setSingleLine(true);
    pairing.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    box.addView(pairing);
    space(box, 10);
    box.addView(button("Connect", false, () -> pair(pairing.getText().toString())));
    space(root, 8);
    root.addView(
        text(
            L.tr(
                this,
                "Your server relays encrypted clips. Your pairing key stays on your devices."),
            14,
            MUTED));
  }

  private void pair(String link) {
    try {
      ClipStore.parsePairing(link);
    } catch (Exception e) {
      toast(e.getMessage());
      return;
    }
    Runnable connect =
        () -> {
          try {
            app.engine.stop();
            app.store.pair(link);
            build();
            startSync();
            toast("Pairing saved");
          } catch (Exception e) {
            toast("Could not save pairing. Please try again.");
          }
        };
    if (app.store.paired())
      new AlertDialog.Builder(this)
          .setTitle(L.tr(this, "Replace this connection?"))
          .setMessage(
              L.tr(
                  this,
                  "Pairing with a different group clears local history. Rejoining this group keeps"
                      + " it."))
          .setPositiveButton(L.tr(this, "Connect"), (d, w) -> connect.run())
          .setNegativeButton(L.tr(this, "Cancel"), null)
          .show();
    else connect.run();
  }

  private void startSync() {
    if (!app.store.hasDeviceToken()) return;
    if (app.store.syncNotifications()
        && Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
    startForegroundService(new Intent(this, SyncService.class));
  }

  private void sendClipboard() {
    ClipboardManager cb = getSystemService(ClipboardManager.class);
    if (!hasWindowFocus()) {
      deferredAction = "dev.syncclip.SEND";
      return;
    }
    if (!cb.hasPrimaryClip()
        || cb.getPrimaryClip() == null
        || cb.getPrimaryClip().getItemCount() == 0) {
      toast("Your clipboard is empty");
      return;
    }
    ClipData.Item item = cb.getPrimaryClip().getItemAt(0);
    if (item.getUri() != null) {
      sendImage(item.getUri());
      return;
    }
    CharSequence value = item.getText();
    if (value == null) {
      toast("Copy text, an image or a file first");
      return;
    }
    sendText(value.toString());
  }

  private void sendImage(Uri uri) {
    if (!app.store.enabled()) {
      app.store.enabled(true);
      startSync();
    }
    app.engine.sendImage(uri);
  }

  private void shareImage(JSONObject clip) {
    try {
      String mime = new ClipCrypto(app.store.key()).decrypt(clip).getString("mime");
      Uri uri = app.images.uri(clip.getString("id"));
      Intent send =
          new Intent(Intent.ACTION_SEND)
              .setType(mime)
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      send.setClipData(ClipData.newUri(getContentResolver(), "Syncclip file", uri));
      startActivity(Intent.createChooser(send, L.tr(this, "Share file")));
    } catch (Exception e) {
      toast("Could not share this file");
    }
  }

  private void sendText(String text) {
    if (!app.store.hasDeviceToken()) {
      toast("Pair again using a new invitation");
      return;
    }
    try {
      if (!app.store.enabled()) {
        app.store.enabled(true);
        startSync();
      }
      app.engine.send(text);
      toast(
          app.engine.status.equals("Connected") ? "Sending…" : "Saved · will send when connected");
      changed();
    } catch (Exception e) {
      toast(e.getMessage());
    }
  }

  private void handleIntent(Intent intent) {
    String action = intent.getAction();
    if (("dev.syncclip.SEND".equals(action) || "dev.syncclip.COPY".equals(action))
        && !ClipboardActions.accepts(this, intent)) {
      intent.setAction(null);
      return;
    }
    if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
      String link = intent.getData().toString();
      new AlertDialog.Builder(this)
          .setTitle(L.tr(this, "Pair with this server?"))
          .setMessage(
              L.tr(this, "Only continue if this pairing link came from your own Syncclip setup."))
          .setPositiveButton(L.tr(this, "Connect"), (d, w) -> pair(link))
          .setNegativeButton(L.tr(this, "Cancel"), null)
          .show();
    } else if (Intent.ACTION_SEND.equals(action)
        || Intent.ACTION_SEND_MULTIPLE.equals(action)
        || Intent.ACTION_PROCESS_TEXT.equals(action)) {
      sharedFiles.clear();
      if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
        ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (streams != null) sharedFiles.addAll(streams);
      } else if (Intent.ACTION_SEND.equals(action)) {
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) sharedFiles.add(stream);
      }
      if (sharedFiles.isEmpty()
          && !Intent.ACTION_PROCESS_TEXT.equals(action)
          && intent.getClipData() != null) {
        for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
          Uri uri = intent.getClipData().getItemAt(i).getUri();
          if (uri != null) sharedFiles.add(uri);
        }
      }
      if (!sharedFiles.isEmpty()) {
        deferredAction = "share-files";
        intent.setAction(null);
        return;
      }
      CharSequence value =
          intent.getCharSequenceExtra(
              Intent.ACTION_SEND.equals(action) ? Intent.EXTRA_TEXT : Intent.EXTRA_PROCESS_TEXT);
      if (value != null) {
        sharedText = value.toString();
        deferredAction = "share";
      }
    } else if ("dev.syncclip.SEND".equals(action)) deferredAction = action;
    else if ("dev.syncclip.COPY".equals(action)) {
      copyId = intent.getStringExtra("clip_id");
      deferredAction = action;
    }
    intent.setAction(null);
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) runDeferred();
  }

  private void runDeferred() {
    if (deferredAction == null || !app.store.paired() || !app.store.hasDeviceToken()) return;
    String action = deferredAction;
    deferredAction = null;
    if (action.equals("share-files") && !sharedFiles.isEmpty()) {
      if (sharedFiles.size() > 20) toast("Choose up to 20 files at a time");
      else for (Uri uri : new ArrayList<>(sharedFiles)) sendImage(uri);
      sharedFiles.clear();
    } else if (action.equals("share") && sharedText != null) {
      sendText(sharedText);
      sharedText = null;
    } else if (action.equals("dev.syncclip.SEND")) sendClipboard();
    else if (action.equals("dev.syncclip.COPY")) {
      for (JSONObject clip : app.store.list("history"))
        if (clip.optString("id").equals(copyId))
          try {
            app.engine.copyClip(clip, () -> toast("Copied"));
          } catch (Exception ignored) {
            toast("Could not copy this clip");
          }
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    if (request == 21) {
      if (result == RESULT_OK && data != null) {
        if (data.getClipData() != null) {
          if (data.getClipData().getItemCount() > 20) toast("Choose up to 20 files at a time");
          else
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
              sendImage(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) sendImage(data.getData());
      }
      return;
    }
    if (request == 22) {
      if (result == RESULT_OK && data != null && data.getData() != null && exportId != null) {
        JSONObject clip = app.images.find(exportId);
        Uri target = data.getData();
        if (clip == null) {
          toast("This file was removed");
          return;
        }
        app.images.workers.execute(
            () -> {
              try {
                byte[] bytes = app.images.load(clip);
                try (OutputStream output = getContentResolver().openOutputStream(target, "wt")) {
                  if (output == null) throw new IllegalStateException();
                  output.write(bytes);
                }
                runOnUiThread(() -> toast("File saved"));
              } catch (Exception e) {
                runOnUiThread(() -> toast("Could not save this file"));
              }
            });
      }
      exportId = null;
      return;
    }
    IntentResult scan = IntentIntegrator.parseActivityResult(request, result, data);
    if (scan != null) {
      if (scan.getContents() != null) pair(scan.getContents());
      return;
    }
    super.onActivityResult(request, result, data);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("export_id", exportId);
  }

  private void saveAttachment(JSONObject clip, JSONObject content) {
    exportId = clip.optString("id");
    String name =
        content.optString(
            "name",
            "Screenshot."
                + ("image/jpeg".equals(content.optString("mime"))
                    ? "jpg"
                    : "image/webp".equals(content.optString("mime")) ? "webp" : "png"));
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(content.optString("mime", "application/octet-stream"))
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, name),
        22);
  }

  @Override
  public void changed() {
    if (builtPaired != (app.store.paired() && app.store.hasDeviceToken())) {
      build();
      return;
    }
    status.setText(L.tr(this, app.engine.status));
    if (history == null || !app.store.paired() || !app.store.hasDeviceToken()) return;
    updating = true;
    enabled.setChecked(app.store.enabled());
    updating = false;
    int waiting = app.store.list("pending").size();
    pending.setText(
        waiting == 0 ? L.tr(this, "Everything sent") : L.fmt(this, "%s waiting to send", waiting));
    pending.setPadding(0, dp(12), 0, 0);
    thumbnails.keySet().removeIf(id -> !app.store.contains(id));
    history.removeAllViews();
    final int rendering = ++historyGeneration;
    List<JSONObject> clips = app.store.list("history");
    Collections.reverse(clips);
    if (clips.isEmpty()) {
      LinearLayout empty = card(history);
      empty.addView(text(L.tr(this, "Your shared clips will appear here."), 16, MUTED));
    }
    try {
      ClipCrypto crypto = new ClipCrypto(app.store.key());
      for (JSONObject clip : clips) {
        JSONObject value;
        try {
          value = crypto.decrypt(clip);
        } catch (Exception e) {
          continue;
        }
        LinearLayout box = card(history);
        LinearLayout clipHeader = new LinearLayout(this);
        clipHeader.setGravity(Gravity.CENTER_VERTICAL);
        clipHeader.addView(
            text(
                value.getString("device")
                    + "  ·  "
                    + DateFormat.getTimeInstance(DateFormat.SHORT, L.locale(this))
                        .format(new Date(clip.optLong("created"))),
                12,
                MUTED),
            new LinearLayout.LayoutParams(0, -2, 1));
        clipHeader.addView(
            ui.iconButton(
                R.drawable.ic_delete,
                "Delete clip",
                () -> SettingsActivity.confirmClear(this, app, clip.optString("id"))),
            new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(clipHeader);
        if ("file".equals(value.optString("kind"))) {
          final JSONObject content = value;
          TextView filename = text(value.getString("name"), 17, TEXT);
          filename.setMaxLines(3);
          box.addView(filename);
          box.addView(
              text(L.fmt(this, "File · %s", L.size(this, value.getLong("bytes"))), 13, MUTED));
          LinearLayout buttons = new LinearLayout(this);
          buttons.addView(
              button("Copy", false, () -> app.engine.copyClip(clip, () -> toast("Copied"))),
              new LinearLayout.LayoutParams(0, dp(48), 1));
          LinearLayout.LayoutParams shareSize = new LinearLayout.LayoutParams(0, dp(48), 1);
          shareSize.leftMargin = dp(10);
          buttons.addView(button("Share", false, () -> shareImage(clip)), shareSize);
          box.addView(buttons);
          space(box, 10);
          box.addView(button("Save as…", false, () -> saveAttachment(clip, content)));
          continue;
        }
        if ("image".equals(value.optString("kind"))) {
          ImageView image = new ImageView(this);
          image.setContentDescription(L.tr(this, "Shared image"));
          image.setScaleType(ImageView.ScaleType.FIT_CENTER);
          image.setAdjustViewBounds(true);
          image.setMinimumHeight(dp(100));
          box.addView(image, new LinearLayout.LayoutParams(-1, -2));
          String id = clip.getString("id");
          Bitmap thumbnail = thumbnails.get(id);
          if (thumbnail != null) image.setImageBitmap(thumbnail);
          else if (app.images.path(id).exists())
            app.images.workers.execute(
                () -> {
                  try {
                    Bitmap preview = ImageTransfer.thumbnail(app.images.load(clip));
                    runOnUiThread(
                        () -> {
                          thumbnails.put(id, preview);
                          if (thumbnails.size() > 32)
                            thumbnails.remove(thumbnails.keySet().iterator().next());
                          if (rendering == historyGeneration) image.setImageBitmap(preview);
                        });
                  } catch (Exception ignored) {
                  }
                });
          else image.setImageResource(android.R.drawable.ic_menu_gallery);
          box.addView(
              text(L.fmt(this, "Image · %s", L.size(this, value.getLong("bytes"))), 13, MUTED));
          LinearLayout buttons = new LinearLayout(this);
          buttons.setOrientation(LinearLayout.HORIZONTAL);
          buttons.addView(
              button("Copy", false, () -> app.engine.copyClip(clip, () -> toast("Copied"))),
              new LinearLayout.LayoutParams(0, dp(48), 1));
          LinearLayout.LayoutParams shareSize = new LinearLayout.LayoutParams(0, dp(48), 1);
          shareSize.leftMargin = dp(10);
          buttons.addView(button("Share", false, () -> shareImage(clip)), shareSize);
          box.addView(buttons);
          continue;
        }
        String full = value.getString("text");
        TextView preview =
            text(full.length() > 240 ? full.substring(0, 240) + "…" : full, 16, TEXT);
        preview.setMaxLines(7);
        box.addView(preview);
        space(box, 6);
        Button copy =
            button(
                "Copy",
                false,
                () -> {
                  app.engine.copy(full);
                  toast("Copied");
                });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(46));
        size.gravity = Gravity.START;
        box.addView(copy, size);
      }
    } catch (Exception e) {
      status.setText(L.tr(this, "Pairing could not be opened. Please pair again."));
    }
  }

  private void toast(String message) {
    Toast.makeText(
            this, L.tr(this, message == null ? "Please try again" : message), Toast.LENGTH_SHORT)
        .show();
  }
}
