package dev.syncclip;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** UI translations shared with Linux. Clipboard content never goes through this class. */
final class L {
  static final String[] TAGS = {"", "en", "fr", "es", "ru"};
  static final String[] NAMES = {"System default", "English", "Français", "Español", "Русский"};
  private static final Map<String, JSONObject> catalogs = new HashMap<>();

  static String selected(Context context) {
    if (Build.VERSION.SDK_INT >= 33) {
      LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
      return locales.isEmpty() ? "" : locales.get(0).getLanguage();
    }
    return context.getSharedPreferences("syncclip", 0).getString("language", "");
  }

  static Locale locale(Context context) {
    String selected = selected(context);
    return selected.isEmpty()
        ? context.getResources().getConfiguration().getLocales().get(0)
        : Locale.forLanguageTag(selected);
  }

  static String language(Context context) {
    return locale(context).getLanguage();
  }

  static synchronized String tr(Context context, String source) {
    String language = language(context);
    JSONObject catalog = catalogs.get(language);
    if (catalog == null) {
      try (java.io.InputStream input = context.getAssets().open(language + ".json")) {
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) data.write(buffer, 0, count);
        catalog = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
      } catch (Exception ignored) {
        catalog = new JSONObject();
      }
      catalogs.put(language, catalog);
    }
    return catalog.optString(source, source);
  }

  static String fmt(Context context, String source, Object... args) {
    return String.format(locale(context), tr(context, source), args);
  }

  static String size(Context context, long value) {
    if (value < 1024) return fmt(context, "%s B", value);
    if (value < 1024 * 1024)
      return fmt(context, "%s KB", String.format(locale(context), "%.0f", value / 1024.0));
    return fmt(context, "%s MB", String.format(locale(context), "%.1f", value / (1024.0 * 1024)));
  }

  static void set(Activity activity, String tag) {
    activity.getSharedPreferences("syncclip", 0).edit().putString("language", tag).apply();
    if (Build.VERSION.SDK_INT >= 33)
      activity
          .getSystemService(LocaleManager.class)
          .setApplicationLocales(LocaleList.forLanguageTags(tag));
    activity.recreate();
  }
}
