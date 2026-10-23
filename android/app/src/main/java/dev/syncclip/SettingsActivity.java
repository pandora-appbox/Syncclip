package dev.syncclip;

import static dev.syncclip.NativeUi.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings are reachable from the fixed header, independently of clip history. */
public final class SettingsActivity extends Activity implements SyncEngine.Observer {
  private SyncApp app;
  private NativeUi ui;
  private TextView connection;
  private LinearLayout devices;
  private final android.os.Handler updates =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private boolean visible = false, loading = false;
  private final Runnable refreshDevices =
      new Runnable() {
        public void run() {
          loadDevices();
          if (visible) updates.postDelayed(this, 5000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (SyncApp) getApplication();
    ui = new NativeUi(this);
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackgroundColor(BG);
    setContentView(shell);
    if (Build.VERSION.SDK_INT >= 30)
      shell.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });
    LinearLayout header = new LinearLayout(this);
    header.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
    header.setGravity(android.view.Gravity.CENTER_VERTICAL);
    header.addView(ui.button("Back", false, this::finish));
    TextView title = ui.text(L.tr(this, "Settings"), 25, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(ui.dp(18), 0, 0, 0);
    header.addView(title);
    shell.addView(header);
    ScrollView scroll = new ScrollView(this);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(0, ui.dp(8), 0, ui.dp(24));
    scroll.addView(body);
    LinearLayout language = ui.card(body);
    language.addView(ui.text(L.tr(this, "Language"), 20, TEXT));
    String selected = L.selected(this);
    int selection = 0;
    for (int i = 0; i < L.TAGS.length; i++) if (L.TAGS[i].equals(selected)) selection = i;
    final int current = selection;
    language.addView(
        ui.button(
            selection == 0 ? L.tr(this, "System default") : L.NAMES[selection],
            false,
            () -> {
              String[] names = L.NAMES.clone();
              names[0] = L.tr(this, names[0]);
              new AlertDialog.Builder(this)
                  .setTitle(L.tr(this, "App language"))
                  .setSingleChoiceItems(
                      names,
                      current,
                      (dialog, index) -> {
                        dialog.dismiss();
                        L.set(this, L.TAGS[index]);
                      })
                  .setNegativeButton(L.tr(this, "Cancel"), null)
                  .show();
            }));
    LinearLayout sync = ui.card(body);
    sync.addView(ui.text(L.tr(this, "Notifications"), 20, TEXT));
    Switch notify = new Switch(this);
    notify.setText(L.tr(this, "Notify when clips arrive"));
    notify.setTextColor(TEXT);
    notify.setTextSize(16);
    notify.setChecked(app.store.syncNotifications());
    notify.setOnCheckedChangeListener(
        (v, value) -> {
          app.store.syncNotifications(value);
          if (!value) getSystemService(NotificationManager.class).cancel(2);
          else if (Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 11);
        });
    sync.addView(notify);
    ui.space(sync, 8);
    sync.addView(
        ui.text(
            L.tr(
                this,
                "The quiet connection indicator stays while syncing. Clip alerts are optional."),
            13,
            MUTED));
    sync.addView(
        ui.button(
            "Android notification settings",
            false,
            () ->
                startActivity(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
    LinearLayout shortcuts = ui.card(body);
    shortcuts.addView(ui.text(L.tr(this, "Shortcuts"), 20, TEXT));
    shortcuts.addView(ui.button("Add quick settings shortcut", false, this::addTile));
    LinearLayout server = ui.card(body);
    server.addView(ui.text(L.tr(this, "Connection"), 20, TEXT));
    TextView url =
        ui.text(app.store.paired() ? app.store.url() : L.tr(this, "No server paired"), 14, MUTED);
    url.setTextIsSelectable(true);
    server.addView(url);
    connection = ui.text(app.engine.status, 14, ACCENT);
    server.addView(connection);
    if (app.store.paired()) {
      Switch enabled = new Switch(this);
      enabled.setText(L.tr(this, "Keep devices connected"));
      enabled.setTextColor(TEXT);
      enabled.setTextSize(16);
      enabled.setChecked(app.store.enabled());
      enabled.setOnCheckedChangeListener(
          (v, value) -> {
            app.store.enabled(value);
            if (value) startForegroundService(new Intent(this, SyncService.class));
            else {
              stopService(new Intent(this, SyncService.class));
              app.engine.stop();
            }
          });
      server.addView(enabled);
      ui.space(server, 10);
      server.addView(
          ui.button(
              "Disconnect this phone",
              false,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle(L.tr(this, "Disconnect this phone?"))
                      .setMessage(
                          L.tr(
                              this,
                              "Local pairing and clips will be removed. Your other devices stay"
                                  + " connected."))
                      .setPositiveButton(
                          L.tr(this, "Disconnect"),
                          (d, w) -> {
                            disconnectDevice(app.store.deviceId(), true);
                          })
                      .setNegativeButton(L.tr(this, "Cancel"), null)
                      .show()));
    }
    ui.space(server, 10);
    server.addView(
        ui.button(
            "Android app settings",
            false,
            () ->
                startActivity(
                    new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));
    server.addView(
        ui.text(
            L.tr(
                this,
                "If sync pauses while the phone sleeps, allow background activity in Android app"
                    + " settings."),
            13,
            MUTED));
    if (app.store.paired()) {
      LinearLayout section = ui.card(body);
      section.addView(ui.text(L.tr(this, "Connected devices"), 20, TEXT));
      section.addView(
          ui.text(
              L.tr(this, "Disconnecting blocks future sync. Saved copies stay on the device."),
              13,
              MUTED));
      devices = new LinearLayout(this);
      devices.setOrientation(LinearLayout.VERTICAL);
      section.addView(devices);
      section.addView(
          ui.button(
              "Pair another device",
              false,
              () -> {
                app.images.workers.execute(
                    () -> {
                      try {
                        String invitation = app.devices.invite();
                        runOnUiThread(
                            () ->
                                new AlertDialog.Builder(this)
                                    .setTitle(L.tr(this, "Pair another device"))
                                    .setMessage(
                                        L.tr(
                                            this,
                                            "Copy this one-use pairing link to your other device."
                                                + " It expires in 10 minutes."))
                                    .setPositiveButton(
                                        L.tr(this, "Copy pairing link"),
                                        (d, w) ->
                                            getSystemService(ClipboardManager.class)
                                                .setPrimaryClip(
                                                    ClipData.newPlainText(
                                                        "Syncclip pairing", invitation)))
                                    .setNegativeButton(L.tr(this, "Cancel"), null)
                                    .show());
                      } catch (Exception e) {
                        runOnUiThread(
                            () ->
                                Toast.makeText(
                                        this,
                                        L.tr(
                                            this,
                                            "Could not create an invitation. Reconnect and try"
                                                + " again."),
                                        Toast.LENGTH_LONG)
                                    .show());
                      }
                    });
              }));
    }
    LinearLayout data = ui.card(body);
    data.addView(ui.text(L.tr(this, "Clipboard & history"), 20, TEXT));
    data.addView(
        ui.text(L.tr(this, "Clips expire after 24 hours. You can remove them sooner."), 14, MUTED));
    if (app.store.paired())
      data.addView(ui.button("Clear shared history", false, () -> confirmClear(this, app, null)));
    ui.space(data, 10);
    data.addView(
        ui.button(
            "Clear this phone’s clipboard",
            false,
            () -> {
              ClipboardManager clipboard = getSystemService(ClipboardManager.class);
              if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
              else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
              Toast.makeText(this, L.tr(this, "Clipboard cleared"), Toast.LENGTH_SHORT).show();
            }));
    LinearLayout support = ui.card(body);
    support.addView(
        ui.button(
            "Support development",
            false,
            () -> startActivity(new Intent(this, SupportActivity.class))));
    body.addView(
        ui.text(
            L.tr(this, "Syncclip 0.5.0 · End-to-end encrypted\nText, images and files up to 32 MB"),
            13,
            MUTED));
  }

  private void addTile() {
    if (Build.VERSION.SDK_INT >= 33)
      getSystemService(StatusBarManager.class)
          .requestAddTileService(
              new ComponentName(this, ClipboardTile.class),
              L.tr(this, "Send clipboard"),
              Icon.createWithResource(this, R.drawable.ic_syncclip),
              getMainExecutor(),
              result -> {});
    else
      Toast.makeText(
              this, L.tr(this, "Edit Quick Settings and add Send clipboard"), Toast.LENGTH_LONG)
          .show();
  }

  private void loadDevices() {
    if (!visible || devices == null || loading || !app.store.hasDeviceToken()) return;
    loading = true;
    app.images.workers.execute(
        () -> {
          try {
            org.json.JSONObject result = app.devices.ensure();
            org.json.JSONArray list = result.getJSONArray("devices");
            runOnUiThread(
                () -> {
                  loading = false;
                  if (!visible) return;
                  devices.removeAllViews();
                  try {
                    for (int index = 0; index < list.length(); index++) {
                      org.json.JSONObject device = list.getJSONObject(index);
                      String id = device.getString("id"), name = device.getString("name");
                      boolean self = id.equals(result.getString("self"));
                      LinearLayout row = new LinearLayout(this);
                      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                      row.addView(
                          ui.text(
                              name
                                  + (self ? " · " + L.tr(this, "This device") : "")
                                  + "\n"
                                  + L.tr(this, device.getBoolean("online") ? "Online" : "Offline"),
                              15,
                              TEXT),
                          new LinearLayout.LayoutParams(0, -2, 1));
                      row.addView(
                          ui.button(
                              "Disconnect",
                              false,
                              () ->
                                  new AlertDialog.Builder(this)
                                      .setTitle(String.format(L.tr(this, "Disconnect %s?"), name))
                                      .setMessage(
                                          L.tr(
                                              this,
                                              "A new pairing invitation will be required to"
                                                  + " reconnect. Existing invitations will expire"
                                                  + " immediately."))
                                      .setPositiveButton(
                                          L.tr(this, "Disconnect"),
                                          (d, w) -> disconnectDevice(id, self))
                                      .setNegativeButton(L.tr(this, "Cancel"), null)
                                      .show()));
                      devices.addView(row);
                    }
                  } catch (Exception ignored) {
                  }
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  loading = false;
                  if (visible && devices.getChildCount() == 0)
                    devices.addView(
                        ui.text(
                            L.tr(this, "Could not load devices. Reconnect and retry."), 14, MUTED));
                });
          }
        });
  }

  private void disconnectDevice(String id, boolean self) {
    app.images.workers.execute(
        () -> {
          try {
            app.devices.disconnect(id);
            runOnUiThread(
                () -> {
                  if (self) {
                    stopService(new Intent(this, SyncService.class));
                    app.engine.stop();
                    app.store.unpair();
                    app.images.clear();
                    app.engine.stop();
                    finish();
                  } else loadDevices();
                });
          } catch (Exception error) {
            runOnUiThread(
                () ->
                    Toast.makeText(
                            this,
                            L.tr(this, "Could not disconnect device. Reconnect and retry."),
                            Toast.LENGTH_LONG)
                        .show());
          }
        });
  }

  static void confirmClear(Activity activity, SyncApp app, String id) {
    new AlertDialog.Builder(activity)
        .setTitle(L.tr(activity, id == null ? "Clear shared history?" : "Delete this clip?"))
        .setMessage(
            L.tr(
                activity,
                id == null
                    ? "Removes all shared clips and queued transfers from the server and paired"
                        + " devices. Saved files are kept."
                    : "Removes this clip from the server and paired devices. Saved files are"
                        + " kept."))
        .setPositiveButton(
            L.tr(activity, id == null ? "Clear history" : "Delete"),
            (d, w) -> app.engine.clearHistory(id))
        .setNegativeButton(L.tr(activity, "Cancel"), null)
        .show();
  }

  @Override
  protected void onStart() {
    super.onStart();
    app.engine.observe(this);
    changed();
    visible = true;
    updates.post(refreshDevices);
  }

  @Override
  protected void onStop() {
    visible = false;
    updates.removeCallbacks(refreshDevices);
    app.engine.remove(this);
    super.onStop();
  }

  @Override
  public void changed() {
    if (!app.store.paired() || !app.store.hasDeviceToken()) {
      finish();
      return;
    }
    if (connection != null) connection.setText(L.tr(this, app.engine.status));
  }
}
