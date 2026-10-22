package org.rea.cineshelf;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements Catalog.Listener {
  Catalog catalog;
  LinearLayout shell, body, bottom;
  TextView scanStatus;
  ProgressBar scanning;
  EditText search;
  int tab, shownCount = 48;
  String query = "";
  String filter = "全部";
  boolean active;
  Handler handler = new Handler();
  final Runnable periodic =
      new Runnable() {
        public void run() {
          if (active) {
            if (catalog.prefs.getBoolean("auto", true)
                && !catalog.busy
                && !catalog.roots().isEmpty()) catalog.scan(false);
            handler.postDelayed(this, 60000);
          }
        }
      };
  final Runnable delayedScan =
      () -> {
        if (active && catalog.prefs.getBoolean("auto", true)) catalog.scan(false);
      };
  final ContentObserver observer =
      new ContentObserver(handler) {
        public void onChange(boolean self) {
          handler.removeCallbacks(delayedScan);
          handler.postDelayed(delayedScan, 2500);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    catalog = Catalog.get(this);
    if (saved != null) {
      tab = saved.getInt("tab");
      query = saved.getString("query", "");
      filter = saved.getString("filter", "全部");
      shownCount = saved.getInt("shown", 48);
    } else {
      tab = catalog.prefs.getInt("ui.tab", 0);
      query = catalog.prefs.getString("ui.query", "");
      filter = catalog.prefs.getString("ui.filter", "全部");
      shownCount = catalog.prefs.getInt("ui.shown", 48);
    }
    build();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putInt("tab", tab);
    b.putString("query", query);
    b.putString("filter", filter);
    b.putInt("shown", shownCount);
    super.onSaveInstanceState(b);
  }

  @Override
  protected void onStart() {
    super.onStart();
    active = true;
    catalog.listen(this);
    for (String root : catalog.roots())
      try {
        getContentResolver().registerContentObserver(Uri.parse(root), true, observer);
      } catch (Exception ignored) {
      }
    handler.postDelayed(periodic, 60000);
    if (catalog.prefs.getBoolean("auto", true) && !catalog.roots().isEmpty()) catalog.scan(false);
    render();
  }

  @Override
  protected void onStop() {
    active = false;
    catalog
        .prefs
        .edit()
        .putInt("ui.tab", tab)
        .putString("ui.query", query)
        .putString("ui.filter", filter)
        .putInt("ui.shown", shownCount)
        .apply();
    catalog.unlisten(this);
    handler.removeCallbacksAndMessages(null);
    getContentResolver().unregisterContentObserver(observer);
    super.onStop();
  }

  public void changed() {
    if (active) {
      scanStatus.setText(catalog.status);
      scanning.setVisibility(catalog.busy ? View.VISIBLE : View.GONE);
      if (!catalog.busy) render();
    }
  }

  void build() {
    shell = Ui.column(this);
    shell.setBackgroundColor(Ui.BG);
    setContentView(shell);
    Ui.insets(this, shell);
    LinearLayout header = Ui.row(this);
    Ui.pad(header, 20, 12, 20, 8);
    LinearLayout branding = Ui.column(this);
    TextView brand = Ui.text(this, "映库", 28, Ui.INK);
    brand.setTypeface(null, 1);
    branding.addView(brand);
    branding.addView(Ui.text(this, "CINESHELF   /   你的本地影院", 10, Ui.MUTED));
    header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(Ui.iconButton(this, "folder", this::chooseFolder));
    View spacer = new View(this);
    header.addView(spacer, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
    header.addView(
        Ui.iconButton(
            this,
            "refresh",
            () -> {
              if (catalog.busy) {
                toast("正在处理：" + catalog.status);
              } else catalog.scan();
            }));
    shell.addView(header);
    LinearLayout searchRow = Ui.row(this);
    Ui.pad(searchRow, 14, 0, 12, 0);
    Ui.round(searchRow, Ui.CARD, 18);
    Ui.Icon glass = new Ui.Icon(this, "search", Ui.MUTED);
    searchRow.addView(glass, new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21)));
    search = new EditText(this);
    search.setSingleLine();
    search.setTextSize(14);
    search.setTextColor(Ui.INK);
    search.setHintTextColor(Ui.MUTED);
    search.setHint("搜索你的本地视频");
    search.setBackgroundColor(Color.TRANSPARENT);
    search.setText(query);
    searchRow.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
    slp.setMargins(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 10));
    shell.addView(searchRow, slp);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            query = s.toString();
            shownCount = 48;
            render();
          }

          public void afterTextChanged(Editable e) {}
        });
    scanStatus = Ui.text(this, catalog.status, 11, Ui.MUTED);
    Ui.pad(scanStatus, 22, 2, 22, 6);
    scanStatus.setMaxLines(1);
    scanStatus.setEllipsize(TextUtils.TruncateAt.END);
    shell.addView(scanStatus);
    scanning = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    scanning.setIndeterminate(true);
    scanning.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PINK));
    shell.addView(scanning, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));
    scanning.setVisibility(catalog.busy ? View.VISIBLE : View.GONE);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = Ui.column(this);
    Ui.pad(body, 20, 12, 20, 24);
    scroll.addView(body);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    bottom = Ui.row(this);
    Ui.pad(bottom, 8, 8, 8, 10);
    bottom.setBackgroundColor(0xff151927);
    shell.addView(bottom);
    render();
  }

  void render() {
    if (body == null) return;
    body.removeAllViews();
    bottom.removeAllViews();
    String[] titles = {"影库", "最近", "清理", "设置"};
    String[] icons = {"library", "history", "clean", "settings"};
    for (int i = 0; i < 4; i++) {
      final int index = i;
      LinearLayout cell = Ui.column(this);
      cell.setGravity(Gravity.CENTER);
      Ui.Icon icon = new Ui.Icon(this, icons[i], tab == i ? Ui.PINK : Ui.MUTED);
      cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 23), Ui.dp(this, 23)));
      TextView label = Ui.text(this, titles[i], 10, tab == i ? Ui.PINK : Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      Ui.pad(label, 0, 4, 0, 0);
      cell.addView(label);
      bottom.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
      cell.setOnClickListener(
          v -> {
            tab = index;
            filter = "全部";
            shownCount = 48;
            render();
          });
    }
    switch (tab) {
      case 0:
        library(false);
        break;
      case 1:
        library(true);
        break;
      case 2:
        cleanup();
        break;
      default:
        settings();
    }
  }

  void heading(String title, String detail) {
    TextView h = Ui.text(this, title, 23, Ui.INK);
    h.setTypeface(null, 1);
    body.addView(h);
    TextView sub = Ui.text(this, detail, 12, Ui.MUTED);
    Ui.pad(sub, 0, 6, 0, 16);
    sub.setLineSpacing(Ui.dp(this, 3), 1);
    body.addView(sub);
  }

  void library(boolean recent) {
    List<Catalog.Group> list = new ArrayList<>(catalog.groups);
    int copies = 0;
    long waste = 0;
    for (Catalog.Group g : list) {
      copies += g.copies.size() - 1;
      waste += g.waste();
    }
    heading(recent ? "接着看" : "每一部，都值得收藏", recent ? "播放进度留在这台设备，随时继续" : "本地保存 · 无需登录 · 没有广告");
    if (!recent) {
      LinearLayout stats = Ui.row(this);
      stat(stats, "视频", String.valueOf(list.size()), Ui.PURPLE);
      stat(stats, "隐藏副本", String.valueOf(copies), Ui.PINK);
      stat(stats, "可清理", Ui.bytes(waste), 0xff5bd5bd);
      body.addView(stats);
      gap(18);
      LinearLayout chips = Ui.row(this);
      for (String name : new String[] {"全部", "最近添加", "多副本"}) {
        TextView chip = Ui.text(this, name, 12, filter.equals(name) ? Ui.INK : Ui.MUTED);
        Ui.pad(chip, 14, 8, 14, 8);
        Ui.round(chip, filter.equals(name) ? 0xff41304d : Ui.CARD, 14);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.rightMargin = Ui.dp(this, 8);
        chips.addView(chip, cp);
        chip.setOnClickListener(
            v -> {
              filter = name;
              render();
            });
      }
      body.addView(chips);
      gap(18);
    }
    List<Catalog.Group> filtered = new ArrayList<>();
    for (Catalog.Group g : list) {
      Catalog.Video v = g.first();
      if (!v.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
      if (recent && catalog.lastPlayed(v) == 0) continue;
      if (!recent && filter.equals("多副本") && g.copies.size() < 2) continue;
      filtered.add(g);
    }
    if (recent)
      Collections.sort(
          filtered,
          (a, b) -> Long.compare(catalog.lastPlayed(b.first()), catalog.lastPlayed(a.first())));
    if (filtered.isEmpty()) {
      empty(
          recent ? "下一次，从这里继续" : "把视频带进你的影库",
          recent ? "播放过的视频会出现在这里，进度自动保存。" : "添加下载目录后，封面、重复副本和续播进度都会自动整理。",
          !recent);
      return;
    }
    int columns = getResources().getConfiguration().screenWidthDp >= 650 ? 3 : 2;
    for (int at = 0; at < Math.min(shownCount, filtered.size()); at += columns) {
      LinearLayout row = Ui.row(this);
      row.setGravity(Gravity.TOP);
      for (int col = 0; col < columns; col++) {
        int index = at + col;
        View card = index < filtered.size() ? videoCard(filtered.get(index)) : new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (col < columns - 1) lp.rightMargin = Ui.dp(this, 12);
        row.addView(card, lp);
      }
      body.addView(row);
      gap(18);
    }
    if (filtered.size() > shownCount)
      body.addView(
          Ui.button(
              this,
              "显示更多 · " + shownCount + " / " + filtered.size(),
              () -> {
                shownCount += 48;
                render();
              }));
  }

  void stat(LinearLayout row, String label, String value, int color) {
    LinearLayout box = Ui.column(this);
    Ui.pad(box, 12, 14, 8, 14);
    Ui.round(box, Ui.CARD, 17);
    TextView n = Ui.text(this, value, 19, color);
    n.setTypeface(null, 1);
    box.addView(n);
    TextView l = Ui.text(this, label, 10, Ui.MUTED);
    Ui.pad(l, 0, 6, 0, 0);
    box.addView(l);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.rightMargin = Ui.dp(this, 7);
    row.addView(box, lp);
  }

  View videoCard(Catalog.Group g) {
    Catalog.Video v = g.first();
    LinearLayout card = Ui.column(this);
    FrameLayout cover = cover(v);
    card.addView(
        cover,
        new LinearLayout.LayoutParams(
            -1, Ui.dp(this, getResources().getConfiguration().screenWidthDp >= 650 ? 125 : 98)));
    cover.setOnClickListener(w -> play(v));
    if (v.duration > 0 && catalog.position(v) > 0) {
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setMax(1000);
      progress.setProgress((int) Math.min(1000, catalog.position(v) * 1000 / v.duration));
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PINK));
      card.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
    }
    LinearLayout titleRow = Ui.row(this);
    titleRow.setGravity(Gravity.TOP);
    TextView title = Ui.text(this, v.name, 13, Ui.INK);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    Ui.pad(title, 0, 9, 0, 2);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.Icon more = new Ui.Icon(this, "more", Ui.MUTED);
    titleRow.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 38)));
    more.setOnClickListener(w -> details(g));
    card.addView(titleRow);
    TextView sub =
        Ui.text(
            this,
            (v.available ? Ui.bytes(v.size) : "目录不可访问")
                + (g.copies.size() > 1 ? " · " + g.copies.size() + " 份" : ""),
            10,
            Ui.MUTED);
    card.addView(sub);
    title.setOnClickListener(w -> play(v));
    return card;
  }

  FrameLayout cover(Catalog.Video v) {
    FrameLayout frame = new FrameLayout(this);
    Ui.round(frame, 0xff292d44, 14);
    GradientDrawable gradient =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff353054, 0xff182d40});
    gradient.setCornerRadius(Ui.dp(this, 14));
    frame.setBackground(gradient);
    frame.setClipToOutline(true);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
    Ui.Icon play = new Ui.Icon(this, "play", 0xccffffff);
    FrameLayout.LayoutParams pp =
        new FrameLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 25), Gravity.CENTER);
    frame.addView(play, pp);
    TextView duration = Ui.text(this, v.duration > 0 ? Ui.time(v.duration) : "本地视频", 10, Ui.INK);
    Ui.pad(duration, 5, 2, 5, 2);
    Ui.round(duration, 0xbb11131d, 4);
    FrameLayout.LayoutParams dp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
    dp.setMargins(0, 0, Ui.dp(this, 7), Ui.dp(this, 7));
    frame.addView(duration, dp);
    catalog.thumbnail(
        v,
        b -> {
          if (b != null && !isDestroyed()) image.setImageBitmap(b);
        });
    return frame;
  }

  void play(Catalog.Video v) {
    if (!v.available) {
      toast("请在设置中重新授权视频目录");
      return;
    }
    startActivity(new Intent(this, PlayerActivity.class).putExtra("token", v.token()));
  }

  void details(Catalog.Group g) {
    Catalog.Video v = g.first();
    String[] options = {"播放 / 继续观看", "查看文件与副本", "分享虚拟视频入口", "使用其它播放器"};
    new AlertDialog.Builder(this)
        .setTitle(v.name)
        .setItems(
            options,
            (d, which) -> {
              if (which == 0) play(v);
              else if (which == 1) {
                StringBuilder s = new StringBuilder("内容完全相同的副本仅显示一次。\n\n");
                for (Catalog.Video f : g.copies)
                  s.append(f.name).append("\n").append(Uri.decode(f.uri)).append("\n\n");
                new AlertDialog.Builder(this)
                    .setTitle("文件详情")
                    .setMessage(s.toString())
                    .setPositiveButton("知道了", null)
                    .show();
              } else {
                Uri uri = LibraryProvider.uri(v.token());
                Intent intent =
                    which == 2
                        ? new Intent(Intent.ACTION_SEND)
                            .setType(LibraryProvider.mime(v))
                            .putExtra(Intent.EXTRA_STREAM, uri)
                        : new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, LibraryProvider.mime(v));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("视频", uri));
                try {
                  startActivity(Intent.createChooser(intent, which == 2 ? "分享视频" : "选择播放器"));
                } catch (Exception e) {
                  toast("没有可用的应用");
                }
              }
            })
        .show();
  }

  void cleanup() {
    long waste = 0;
    int count = 0;
    for (Catalog.Group g : catalog.groups) {
      waste += g.waste();
      if (g.copies.size() > 1) count++;
    }
    heading("给存储，留一点空白", count + " 组完整重复 · 最多可释放 " + Ui.bytes(waste));
    notice(
        "安全规则", "仅完整 SHA-256 相同的文件可清理。默认只合并显示，不改动任何视频。删除副本会使原下载 App 对应的旧路径失效，请保留你仍需要的路径。", Ui.PINK);
    for (Catalog.Group g : catalog.groups)
      if (g.copies.size() > 1) {
        LinearLayout box = panel();
        LinearLayout row = Ui.row(this);
        FrameLayout image = cover(g.first());
        row.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 60)));
        LinearLayout text = Ui.column(this);
        Ui.pad(text, 12, 0, 0, 0);
        TextView title = Ui.text(this, g.first().name, 14, Ui.INK);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        text.addView(
            Ui.text(this, g.copies.size() + " 份 · 可释放 " + Ui.bytes(g.waste()), 11, Ui.PINK));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
        gapIn(box, 12);
        box.addView(Ui.button(this, "选择保留文件", () -> selectKeeper(g)));
        body.addView(box);
        gap(12);
      }
    gap(10);
    TextView hint = Ui.text(this, "疑似相同画面", 19, Ui.INK);
    hint.setTypeface(null, 1);
    body.addView(hint);
    TextView desc = Ui.text(this, "比较 3 个时间点的画面与时长，仅供人工核对。可能漏报或误报，绝不用于自动删除。", 12, Ui.MUTED);
    Ui.pad(desc, 0, 8, 0, 12);
    body.addView(desc);
    if (catalog.similar.isEmpty())
      notice("暂未发现相似视频", "继续添加目录后会重新分析。转码、裁切、水印或采样差异可能影响结果。", Ui.PURPLE);
    for (Catalog.Group[] pair : catalog.similar) {
      LinearLayout box = panel();
      box.addView(Ui.text(this, "画面相似 · 需人工确认", 12, Ui.PURPLE));
      for (Catalog.Group g : pair) {
        TextView t =
            Ui.text(
                this, "▶  " + g.first().name + "  ·  " + Ui.time(g.first().duration), 13, Ui.INK);
        Ui.pad(t, 0, 12, 0, 12);
        t.setOnClickListener(v -> play(g.first()));
        box.addView(t);
      }
      body.addView(box);
      gap(12);
    }
  }

  void selectKeeper(Catalog.Group g) {
    if (catalog.busy) {
      toast("扫描完成后再清理");
      return;
    }
    String[] names = new String[g.copies.size()];
    for (int i = 0; i < names.length; i++) {
      Catalog.Video v = g.copies.get(i);
      names[i] = v.name + "\n" + Uri.decode(v.uri);
    }
    final int[] keep = {0};
    new AlertDialog.Builder(this)
        .setTitle("保留哪一份？其余副本将删除")
        .setSingleChoiceItems(names, 0, (d, i) -> keep[0] = i)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "下一步",
            (d, w) -> {
              Catalog.Video keeper = g.copies.get(keep[0]);
              List<Catalog.Video> removal = new ArrayList<>(g.copies);
              removal.remove(keeper);
              new AlertDialog.Builder(this)
                  .setTitle("确认删除 " + removal.size() + " 个真实副本？")
                  .setMessage(
                      "保留："
                          + keeper.name
                          + "\n"
                          + Uri.decode(keeper.uri)
                          + "\n\n"
                          + "请暂停其它 App 的下载、移动或编辑。将重新完整核验后删除，无法撤销。其它 App 的被删路径将不能继续播放。虚拟入口仅对支持内容 URI"
                          + " 的 App 有效。")
                  .setNegativeButton("返回", null)
                  .setPositiveButton(
                      "核验并删除",
                      (dd, ww) ->
                          catalog.cleanup(
                              keeper,
                              removal,
                              msg -> {
                                toast(msg);
                                render();
                              }))
                  .show();
            })
        .show();
  }

  void settings() {
    heading("你的影库，你来管理", "目录授权、自动更新与兼容性说明");
    notice("完全本地", "映库不申请联网权限。视频、封面、哈希与播放进度只存放在你的设备。", 0xff5bd5bd);
    notice(
        "可读取哪些文件？",
        "请选择实际保存 MP4 等视频的子目录。Android 可能禁止选择存储根目录、整个下载目录或 Android/data。其它 App 的私有、加密缓存需先由原 App"
            + " 正常导出为视频。",
        Ui.PURPLE);
    LinearLayout dirs = panel();
    dirs.addView(Ui.text(this, "视频目录", 18, Ui.INK));
    for (String root : catalog.roots()) {
      TextView t = Ui.text(this, Uri.decode(root), 12, Ui.MUTED);
      Ui.pad(t, 0, 12, 0, 6);
      dirs.addView(t);
      TextView remove = Ui.text(this, "移除此目录", 12, Ui.PINK);
      Ui.pad(remove, 0, 8, 0, 12);
      remove.setOnClickListener(
          v ->
              new AlertDialog.Builder(this)
                  .setTitle("从影库移除目录？")
                  .setMessage("只移除索引，不删除目录或视频。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "移除",
                      (d, w) -> {
                        catalog.removeRoot(root);
                        render();
                      })
                  .show());
      dirs.addView(remove);
    }
    gapIn(dirs, 10);
    dirs.addView(Ui.button(this, "＋ 添加 / 重新授权目录", this::chooseFolder));
    body.addView(dirs);
    gap(14);
    LinearLayout auto = panel();
    Switch toggle = new Switch(this);
    toggle.setText("打开应用时自动扫描");
    toggle.setTextColor(Ui.INK);
    toggle.setChecked(catalog.prefs.getBoolean("auto", true));
    toggle.setOnCheckedChangeListener(
        (v, on) -> catalog.prefs.edit().putBoolean("auto", on).apply());
    auto.addView(toggle);
    TextView info = Ui.text(this, "前台每分钟检查一次。大量文件核验会占用存储读取时间；不在后台持续扫描。", 12, Ui.MUTED);
    Ui.pad(info, 0, 8, 0, 0);
    auto.addView(info);
    body.addView(auto);
    gap(14);
    notice(
        "一份文件，多处使用",
        "在系统文件选择器中打开「映库」，或从视频菜单分享虚拟入口，即可让支持的 App 读取同一份真实视频，不再复制内容。\n\n"
            + "Android 普通应用无法把共享目录中的两个旧文件路径透明变成硬链接；映库不会生成损坏的空壳视频。旧下载 App 可能仍会再次下载，映库在扫描后识别副本。",
        Ui.PURPLE);
    notice(
        "格式与版本",
        "使用 Android 系统解码器，实际支持取决于手机、封装和编码。加密私有视频与 DRM 内容不能直接播放。\n\n"
            + "映库 0.1.0 · 独立测试版\n"
            + "与旧 App 并列安装，不迁移或修改旧 App 私有数据。",
        Ui.MUTED);
  }

  void chooseFolder() {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    try {
      startActivityForResult(i, 41);
    } catch (Exception e) {
      toast("系统文件选择器不可用");
    }
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req == 41 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      if (Catalog.AUTH.equals(uri.getAuthority())) {
        toast("请选择真实视频目录，不能索引映库自身");
        return;
      }
      try {
        getContentResolver()
            .takePersistableUriPermission(
                uri,
                data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        catalog.addRoot(uri);
        render();
      } catch (Exception e) {
        toast("目录授权失败，请重新选择");
      }
    }
  }

  LinearLayout panel() {
    LinearLayout box = Ui.column(this);
    Ui.pad(box, 16, 16, 16, 16);
    Ui.round(box, Ui.CARD, 20);
    return box;
  }

  void notice(String title, String detail, int color) {
    LinearLayout p = panel();
    TextView h = Ui.text(this, title, 15, color);
    h.setTypeface(null, 1);
    p.addView(h);
    TextView d = Ui.text(this, detail, 12, Ui.MUTED);
    d.setLineSpacing(Ui.dp(this, 4), 1);
    Ui.pad(d, 0, 8, 0, 0);
    p.addView(d);
    body.addView(p);
    gap(14);
  }

  void empty(String title, String detail, boolean add) {
    LinearLayout p = panel();
    p.setGravity(Gravity.CENTER);
    Ui.pad(p, 24, 34, 24, 34);
    Ui.Icon image = new Ui.Icon(this, "library", Ui.PURPLE);
    p.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
    TextView t = Ui.text(this, title, 20, Ui.INK);
    t.setGravity(Gravity.CENTER);
    Ui.pad(t, 0, 24, 0, 10);
    p.addView(t);
    TextView d = Ui.text(this, detail, 13, Ui.MUTED);
    d.setGravity(Gravity.CENTER);
    d.setLineSpacing(Ui.dp(this, 4), 1);
    p.addView(d);
    if (add) {
      gapIn(p, 24);
      p.addView(Ui.button(this, "添加视频目录", this::chooseFolder));
    }
    body.addView(p);
  }

  void gap(int n) {
    gapIn(body, n);
  }

  void gapIn(LinearLayout l, int n) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, Ui.dp(this, n)));
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }
}
