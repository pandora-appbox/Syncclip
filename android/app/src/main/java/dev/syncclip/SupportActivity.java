package dev.syncclip;

import static dev.syncclip.NativeUi.*;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Voluntary support. No payment SDK, requests, or wallet access. */
public final class SupportActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    NativeUi ui = new NativeUi(this);
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackgroundColor(BG);
    setContentView(shell);
    if (Build.VERSION.SDK_INT >= 30)
      shell.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });
    LinearLayout header = new LinearLayout(this);
    header.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(ui.button("Back", false, this::finish));
    TextView title = ui.text(L.tr(this, "Support development"), 22, TEXT);
    title.setPadding(ui.dp(14), 0, 0, 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    shell.addView(header);
    ScrollView scroll = new ScrollView(this);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(0, ui.dp(8), 0, ui.dp(24));
    scroll.addView(body);
    LinearLayout intro = ui.card(body);
    TextView heading = ui.text(L.tr(this, "Help keep Syncclip improving"), 24, TEXT);
    heading.setTypeface(null, Typeface.BOLD);
    intro.addView(heading);
    intro.addView(
        ui.text(
            L.tr(this, "Donations are optional. Thank you for supporting development."),
            14,
            MUTED));
    try (java.io.InputStream input = getAssets().open("support.json")) {
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int count;
      while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
      JSONObject data = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
      intro.addView(
          ui.button(
              "PayPal ↗",
              false,
              () -> {
                try {
                  startActivity(
                      new Intent(Intent.ACTION_VIEW, Uri.parse(data.getString("paypal"))));
                } catch (Exception e) {
                  Toast.makeText(this, L.tr(this, "Could not open link"), Toast.LENGTH_LONG).show();
                }
              }));
      intro.addView(
          ui.text(
              L.tr(
                  this,
                  "Use only the asset and network shown. For Lightning, use a compatible wallet."),
              13,
              MUTED));
      JSONArray destinations = data.getJSONArray("destinations");
      for (int i = 0; i < destinations.length(); i++) {
        JSONObject destination = destinations.getJSONObject(i);
        String address = destination.getString("address");
        LinearLayout section = ui.card(body);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(destination.getString("asset"), 19, ACCENT));
        labels.addView(ui.text(destination.getString("network"), 13, MUTED));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageButton copy =
            ui.iconButton(
                R.drawable.ic_copy,
                "Copy address",
                () -> {
                  getSystemService(ClipboardManager.class)
                      .setPrimaryClip(ClipData.newPlainText("Donation address", address));
                  Toast.makeText(this, L.tr(this, "Address copied"), Toast.LENGTH_SHORT).show();
                });
        copy.setContentDescription(L.tr(this, "Copy address"));
        copy.setTooltipText(L.tr(this, "Copy address"));
        row.addView(copy, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(48)));
        section.addView(row);
        TextView value = ui.text(address, 13, MUTED);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextDirection(View.TEXT_DIRECTION_LTR);
        value.setTextIsSelectable(true);
        section.addView(value);
      }
    } catch (Exception e) {
      intro.addView(ui.text(L.tr(this, "Please try again"), 14, MUTED));
    }
  }
}
