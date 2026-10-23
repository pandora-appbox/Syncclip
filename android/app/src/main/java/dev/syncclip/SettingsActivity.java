package dev.syncclip;

import static dev.syncclip.NativeUi.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
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
                            stopService(new Intent(this, SyncService.class));
                            app.engine.stop();
                            app.store.unpair();
                            app.images.clear();
                            app.engine.stop();
                            finish();
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
    body.addView(
        ui.text(
            L.tr(this, "Syncclip 0.4.0 · End-to-end encrypted\nText, images and files up to 32 MB"),
            13,
            MUTED));
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
  }

  @Override
  protected void onStop() {
    app.engine.remove(this);
    super.onStop();
  }

  @Override
  public void changed() {
    if (connection != null) connection.setText(L.tr(this, app.engine.status));
  }
}
