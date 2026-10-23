package dev.syncclip;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native controls for the app and its settings. */
final class NativeUi {
  static final int BG = Color.rgb(17, 16, 24),
      CARD = Color.rgb(27, 24, 38),
      TEXT = Color.rgb(243, 239, 250),
      MUTED = Color.rgb(177, 170, 189),
      ACCENT = Color.rgb(182, 156, 255);
  final Activity activity;

  NativeUi(Activity activity) {
    this.activity = activity;
  }

  int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  GradientDrawable background(int color, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(radius));
    return shape;
  }

  TextView text(String value, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(0, 0, 0, dp(8));
    return view;
  }

  void space(LinearLayout box, int height) {
    box.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(height)));
  }

  Button button(String title, boolean primary, Runnable action) {
    Button view = new Button(activity);
    view.setText(L.tr(activity, title));
    view.setAllCaps(false);
    view.setTextSize(15);
    view.setTextColor(primary ? BG : TEXT);
    view.setBackground(background(primary ? ACCENT : Color.rgb(48, 40, 63), 12));
    view.setMinHeight(dp(48));
    view.setPadding(dp(14), dp(8), dp(14), dp(8));
    view.setOnClickListener(v -> action.run());
    return view;
  }

  android.widget.ImageButton iconButton(int resource, String description, Runnable action) {
    android.widget.ImageButton view = new android.widget.ImageButton(activity);
    view.setImageResource(resource);
    view.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    android.util.TypedValue ripple = new android.util.TypedValue();
    activity
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
    view.setBackgroundResource(ripple.resourceId);
    view.setContentDescription(L.tr(activity, description));
    view.setTooltipText(L.tr(activity, description));
    view.setPadding(dp(10), dp(10), dp(10), dp(10));
    view.setOnClickListener(v -> action.run());
    return view;
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout box =
        new LinearLayout(activity) {
          @Override
          public void addView(View child, int index, android.view.ViewGroup.LayoutParams params) {
            LinearLayout.LayoutParams layout =
                params instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) params
                    : new LinearLayout.LayoutParams(params);
            if (!(child instanceof android.widget.ImageView)) {
              layout.leftMargin += dp(18);
              layout.rightMargin += dp(18);
            }
            super.addView(child, index, layout);
          }
        };
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(0, dp(14), 0, dp(14));
    box.setBackground(background(CARD, 0));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(14);
    parent.addView(box, params);
    return box;
  }
}
