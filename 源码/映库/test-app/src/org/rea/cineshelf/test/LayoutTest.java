package org.rea.cineshelf.test;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import org.rea.cineshelf.*;

public class LayoutTest extends Instrumentation {
  Activity activity;
  Class<?> cls = MainActivity.class;

  Object field(String name) throws Exception {
    Field f = cls.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  void set(String name, Object value) throws Exception {
    Field f = cls.getDeclaredField(name);
    f.setAccessible(true);
    f.set(activity, value);
  }

  void render() throws Exception {
    Method m = cls.getDeclaredMethod("render");
    m.setAccessible(true);
    m.invoke(activity);
  }

  void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    android.util.Log.i("CineShelfLayout", "PASS " + name);
  }

  boolean has(View v, String text) {
    if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return true;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
        if (has(((ViewGroup) v).getChildAt(i), text)) return true;
    return false;
  }

  View text(View v, String part) {
    if (v instanceof TextView && ((TextView) v).getText().toString().contains(part)) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View found = text(((ViewGroup) v).getChildAt(i), part);
        if (found != null) return found;
      }
    return null;
  }

  void shot(String name) throws Exception {
    waitForIdleSync();
    SystemClock.sleep(800);
    runOnMainSync(
        () -> {
          try {
            View v = activity.findViewById(android.R.id.content);
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            try (OutputStream out =
                new FileOutputStream(new File(activity.getFilesDir(), name + ".png"))) {
              b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            b.recycle();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      activity =
          startActivitySync(
              new android.content.Intent(getTargetContext(), MainActivity.class)
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      check(((Integer) field("tab")) == 1, "fresh launch defaults to library");
      View shell = (View) field("shell");
      check(
          has(shell, "主页") && has(shell, "影库") && has(shell, "最近") && has(shell, "设置"),
          "four requested navigation labels");
      check(
          ((View) field("homeHeader")).getVisibility() == View.GONE
              && ((View) field("searchBar")).getVisibility() == View.GONE,
          "library hides brand and search");
      float density = activity.getResources().getDisplayMetrics().density;
      check(((View) field("bottom")).getHeight() / density <= 56, "compact bottom navigation");
      shot("v2-library");
      runOnMainSync(
          () -> {
            try {
              set("tab", 0);
              render();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          ((View) field("homeHeader")).getVisibility() == View.VISIBLE
              && ((View) field("searchBar")).getVisibility() == View.VISIBLE,
          "home shows brand and search");
      shot("v2-home");
      runOnMainSync(
          () -> {
            try {
              set("query", "NO_MATCH_FOR_LAYOUT_TEST");
              set("tab", 1);
              render();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          text((View) field("body"), "Neon-motion") != null,
          "hidden home search does not filter library");
      runOnMainSync(
          () -> {
            try {
              set("query", "");
              set("tab", 2);
              render();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(((View) field("searchBar")).getVisibility() == View.GONE, "recent hides search");
      runOnMainSync(
          () -> {
            try {
              set("tab", 3);
              render();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          text((View) field("body"), "界面与按钮自定义") != null
              && text((View) field("body"), "重复视频清理") != null,
          "settings contains customization and cleanup");
      shot("v2-settings");
      runOnMainSync(
          () -> {
            try {
              text((View) field("body"), "重复视频清理").getParent();
              View v = text((View) field("body"), "重复视频清理");
              ((View) v.getParent()).performClick();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check((Boolean) field("cleanupPage"), "cleanup opens within settings");
      runOnMainSync(() -> activity.onBackPressed());
      check(
          !(Boolean) field("cleanupPage") && ((Integer) field("tab")) == 3,
          "cleanup back returns to settings");
      result.putString(
          "result",
          "PASS: default library, compact navigation, home-only brand/search, independent"
              + " filtering, settings customization and cleanup routing");
      runOnMainSync(activity::finish);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      android.util.Log.e("CineShelfLayout", "FAIL", e);
      result.putString("failure", e.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
