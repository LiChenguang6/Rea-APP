package org.rea.cineshelf;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;

final class Ui {
  static final int BG = 0xff10121c,
      CARD = 0xff1b2031,
      INK = 0xfff4f5fc,
      MUTED = 0xff9da7c2,
      PINK = 0xffff5e88,
      PURPLE = 0xff9b8cff;

  static int dp(Context c, float n) {
    return (int) (n * c.getResources().getDisplayMetrics().density + .5f);
  }

  static TextView text(Context c, String s, int sp, int color) {
    TextView t = new TextView(c);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setFontFeatureSettings("kern");
    return t;
  }

  static GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  static void round(View v, int color, int radius) {
    v.setBackground(
        new RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33ffffff),
            bg(color, dp(v.getContext(), radius)),
            null));
    v.setClipToOutline(true);
  }

  static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(1);
    return l;
  }

  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  static void pad(View v, int a, int b, int c, int d) {
    Context x = v.getContext();
    v.setPadding(dp(x, a), dp(x, b), dp(x, c), dp(x, d));
  }

  static TextView button(Context c, String s, Runnable r) {
    TextView t = text(c, s, 14, INK);
    t.setGravity(Gravity.CENTER);
    t.setTypeface(null, 1);
    pad(t, 18, 13, 18, 13);
    round(t, PINK, 16);
    t.setOnClickListener(v -> r.run());
    return t;
  }

  static String bytes(long b) {
    if (b >= 1073741824L) return String.format(Locale.ROOT, "%.2f GB", b / 1073741824d);
    if (b >= 1048576) return String.format(Locale.ROOT, "%.1f MB", b / 1048576d);
    return (b / 1024) + " KB";
  }

  static String time(long ms) {
    long s = Math.max(0, ms / 1000);
    return s >= 3600
        ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
  }

  static void insets(Activity a, View root) {
    a.getWindow().setStatusBarColor(BG);
    a.getWindow().setNavigationBarColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              i.getSystemWindowInsetLeft(),
              i.getSystemWindowInsetTop(),
              i.getSystemWindowInsetRight(),
              i.getSystemWindowInsetBottom());
          return i.consumeSystemWindowInsets();
        });
    root.requestApplyInsets();
  }

  static class Icon extends View {
    final String kind;
    int color;
    Paint p = new Paint(3);

    Icon(Context c, String k, int col) {
      super(c);
      kind = k;
      color = col;
      setContentDescription(k);
    }

    protected void onDraw(Canvas original) {
      super.onDraw(original);
      Canvas c = original;
      c.save();
      float scale = Math.min(getWidth(), getHeight()) / 24f;
      c.translate((getWidth() - 24 * scale) / 2, (getHeight() - 24 * scale) / 2);
      c.scale(scale, scale);
      p.setColor(color);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      p.setStyle(Paint.Style.STROKE);
      switch (kind) {
        case "home":
          c.drawRoundRect(3, 3, 10, 10, 2, 2, p);
          c.drawRoundRect(14, 3, 21, 10, 2, 2, p);
          c.drawRoundRect(3, 14, 10, 21, 2, 2, p);
          c.drawRoundRect(14, 14, 21, 21, 2, 2, p);
          break;
        case "library":
          c.drawRoundRect(3, 5, 21, 20, 3, 3, p);
          c.drawLine(7, 2, 17, 2, p);
          p.setStyle(Paint.Style.FILL);
          Path t = new Path();
          t.moveTo(10, 9);
          t.lineTo(16, 12.5f);
          t.lineTo(10, 16);
          t.close();
          c.drawPath(t, p);
          break;
        case "history":
          c.drawCircle(12, 12, 9, p);
          c.drawLine(12, 6, 12, 12, p);
          c.drawLine(12, 12, 16, 14, p);
          break;
        case "clean":
          c.drawRoundRect(6, 7, 18, 21, 2, 2, p);
          c.drawLine(4, 5, 20, 5, p);
          c.drawLine(9, 2, 15, 2, p);
          c.drawLine(10, 10, 10, 17, p);
          c.drawLine(14, 10, 14, 17, p);
          break;
        case "settings":
          c.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 12, 12);
            c.drawLine(12, 2, 12, 5, p);
            c.restore();
          }
          c.drawCircle(12, 12, 8, p);
          break;
        case "folder":
          Path f = new Path();
          f.moveTo(3, 6);
          f.lineTo(9, 6);
          f.lineTo(11, 8);
          f.lineTo(21, 8);
          f.lineTo(21, 20);
          f.lineTo(3, 20);
          f.close();
          c.drawPath(f, p);
          break;
        case "search":
          c.drawCircle(10, 10, 6, p);
          c.drawLine(15, 15, 21, 21, p);
          break;
        case "refresh":
          c.drawArc(4, 4, 20, 20, 40, 285, false, p);
          c.drawLine(20, 3, 20, 9, p);
          c.drawLine(14, 9, 20, 9, p);
          break;
        case "play":
          p.setStyle(Paint.Style.FILL);
          Path tri = new Path();
          tri.moveTo(7, 4);
          tri.lineTo(21, 12);
          tri.lineTo(7, 20);
          tri.close();
          c.drawPath(tri, p);
          break;
        case "back":
          c.drawLine(16, 4, 8, 12, p);
          c.drawLine(8, 12, 16, 20, p);
          break;
        case "more":
          p.setStyle(Paint.Style.FILL);
          for (int y = 5; y <= 19; y += 7) c.drawCircle(12, y, 1.6f, p);
          break;
        default:
          c.drawLine(5, 12, 19, 12, p);
          c.drawLine(12, 5, 12, 19, p);
      }
      c.restore();
    }
  }

  static View iconButton(Context c, String name, Runnable action) {
    FrameLayout box = new FrameLayout(c);
    Icon icon = new Icon(c, name, INK);
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(c, 23), dp(c, 23), Gravity.CENTER);
    box.addView(icon, p);
    box.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
    round(box, CARD, 15);
    box.setOnClickListener(v -> action.run());
    box.setContentDescription(
        name.equals("folder") ? "添加视频目录" : name.equals("refresh") ? "重新扫描" : name);
    return box;
  }
}
