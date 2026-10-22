package org.rea.cineshelf;

import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.LruCache;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class Catalog {
  public static final String AUTH = "org.rea.cineshelf.documents";
  private static Catalog instance;

  public static synchronized Catalog get(Context c) {
    if (instance == null) instance = new Catalog(c.getApplicationContext());
    return instance;
  }

  public static class Video {
    public String id, uri, name, folder, hash = "", sample = "";
    public long size, modified, duration;
    public long[] frames;
    public boolean available = true;
    public int flags;

    public String token() {
      return hash.isEmpty() ? "id:" + id : "sha:" + hash;
    }
  }

  public static class Group {
    public final ArrayList<Video> copies = new ArrayList<>();

    public Video first() {
      return copies.get(0);
    }

    public long waste() {
      return first().size * (copies.size() - 1);
    }
  }

  public interface Listener {
    void changed();
  }

  final Context context;
  final SharedPreferences prefs;
  final Handler main = new Handler(Looper.getMainLooper());
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final ExecutorService thumbs = Executors.newFixedThreadPool(2);
  final LruCache<String, Bitmap> images =
      new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  private final ArrayList<Listener> listeners = new ArrayList<>();
  public volatile List<Video> videos = new ArrayList<>();
  public volatile List<Group> groups = new ArrayList<>();
  public volatile List<Group[]> similar = new ArrayList<>();
  public volatile boolean busy;
  public volatile String status = "选择目录，建立你的私人影库";
  public volatile int warnings;
  public volatile boolean cancelled;
  private volatile boolean rescanRequested, rescanForce;

  private Catalog(Context c) {
    context = c;
    prefs = c.getSharedPreferences("library", 0);
    load();
    regroup();
  }

  public void listen(Listener l) {
    if (!listeners.contains(l)) listeners.add(l);
  }

  public void unlisten(Listener l) {
    listeners.remove(l);
  }

  boolean pendingNotify;

  synchronized void notifyChange() {
    if (pendingNotify) return;
    pendingNotify = true;
    main.postDelayed(
        () -> {
          synchronized (Catalog.this) {
            pendingNotify = false;
          }
          for (Listener l : new ArrayList<>(listeners)) l.changed();
        },
        100);
  }

  public List<String> roots() {
    try {
      JSONArray a = new JSONArray(prefs.getString("roots", "[]"));
      List<String> r = new ArrayList<>();
      for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
      return r;
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  public void addRoot(Uri uri) {
    List<String> r = roots();
    if (!r.contains(uri.toString())) r.add(uri.toString());
    prefs.edit().putString("roots", new JSONArray(r).toString()).apply();
    scan();
  }

  public void removeRoot(String uri) {
    List<String> r = roots();
    r.remove(uri);
    prefs.edit().putString("roots", new JSONArray(r).toString()).apply();
    scan();
  }

  public static String identity(Uri uri) {
    return uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri);
  }

  public Video find(String token) {
    for (Video v : videos)
      if (v.available && (v.token().equals(token) || ("id:" + v.id).equals(token))) return v;
    String alias = prefs.getString("alias:" + token, "");
    if (!alias.isEmpty()) for (Video v : videos) if (v.available && v.hash.equals(alias)) return v;
    return null;
  }

  public long position(Video v) {
    long pos = prefs.getLong("position:" + v.token(), 0),
        stamp = prefs.getLong("time:" + v.token(), 0);
    for (Video other : videos)
      if (other == v || (!v.hash.isEmpty() && v.hash.equals(other.hash))) {
        long t = prefs.getLong("time:id:" + other.id, 0);
        if (t > stamp) {
          stamp = t;
          pos = prefs.getLong("position:id:" + other.id, 0);
        }
      }
    return pos;
  }

  public long lastPlayed(Video v) {
    long time = prefs.getLong("time:" + v.token(), 0);
    for (Video other : videos)
      if (other == v || (!v.hash.isEmpty() && v.hash.equals(other.hash)))
        time = Math.max(time, prefs.getLong("time:id:" + other.id, 0));
    return time;
  }

  public void saveProgress(Video v, long p, long duration) {
    long now = System.currentTimeMillis();
    long pos = duration > 0 && duration - p < 1800 ? 0 : p;
    prefs
        .edit()
        .putLong("position:" + v.token(), pos)
        .putLong("time:" + v.token(), now)
        .putLong("position:id:" + v.id, pos)
        .putLong("time:id:" + v.id, now)
        .apply();
  }

  public void scan() {
    scan(true);
  }

  public synchronized void scan(boolean force) {
    if (busy) {
      rescanRequested = true;
      rescanForce |= force;
      return;
    }
    busy = true;
    cancelled = false;
    warnings = 0;
    status = "正在发现视频…";
    notifyChange();
    worker.execute(
        () -> {
          List<Video> old = videos;
          LinkedHashMap<String, Video> found = new LinkedHashMap<>();
          Set<String> seen = new HashSet<>();
          List<String> successful = new ArrayList<>();
          try {
            for (String root : roots()) {
              try {
                Uri tree = Uri.parse(root);
                walk(tree, DocumentsContract.getTreeDocumentId(tree), root, found, seen);
                successful.add(root);
              } catch (Exception e) {
                warnings++;
              }
            }
            // Preserve records from inaccessible roots, but never offer stale entries for cleanup.
            for (Video v : old)
              if (!successful.contains(v.folder)
                  && roots().contains(v.folder)
                  && !found.containsKey(v.id)) {
                v.available = false;
                found.put(v.id, v);
              }
            boolean unchanged = found.size() == old.size();
            for (Video v : found.values()) {
              boolean same = false;
              for (Video prev : old)
                if (v.id.equals(prev.id)
                    && v.modified > 0
                    && v.modified == prev.modified
                    && v.size == prev.size
                    && v.available == prev.available) {
                  same = true;
                  break;
                }
              if (!same) {
                unchanged = false;
                break;
              }
            }
            if (!force && unchanged) {
              status = groups.size() + " 部视频 · 目录无变化";
              return;
            }
            Map<Long, List<Video>> sizes = new LinkedHashMap<>();
            for (Video v : found.values())
              if (v.available && v.size > 0)
                sizes.computeIfAbsent(v.size, k -> new ArrayList<>()).add(v);
            int count = 0;
            for (List<Video> bucket : sizes.values()) {
              if (bucket.size() == 1 && !bucket.get(0).hash.isEmpty()) {
                Video v = bucket.get(0);
                try {
                  v.hash = Dedupe.hash(source(v));
                  recordHash(v);
                } catch (Exception e) {
                  v.hash = "";
                  warnings++;
                }
              }
              if (cancelled) throw new InterruptedIOException();
              if (bucket.size() > 1) {
                for (Video reset : bucket) reset.hash = "";
                Map<String, List<Video>> samples = new HashMap<>();
                for (Video v : bucket) {
                  try {
                    status = "快速比对 · " + (++count) + " / " + found.size();
                    notifyChange();
                    v.sample = Dedupe.sample(source(v));
                    samples.computeIfAbsent(v.sample, k -> new ArrayList<>()).add(v);
                  } catch (Exception e) {
                    warnings++;
                  }
                }
                for (List<Video> match : samples.values())
                  if (match.size() > 1)
                    for (Video v : match) {
                      try {
                        status = "完整核验 · " + v.name;
                        notifyChange();
                        v.hash = Dedupe.hash(source(v));
                        recordHash(v);
                      } catch (Exception e) {
                        v.hash = "";
                        warnings++;
                      }
                    }
              }
            }
            for (Video v : found.values()) {
              if (cancelled) throw new InterruptedIOException();
              if (v.available) {
                status = "提取封面与相似画面 · " + v.name;
                notifyChange();
                metadata(v);
              }
            }
            videos = new ArrayList<>(found.values());
            regroup();
            persist();
            status =
                groups.size() + " 部视频 · " + (warnings == 0 ? "已更新" : warnings + " 项读取问题，请检查目录权限");
          } catch (InterruptedIOException e) {
            status = "已停止扫描，保留上次影库";
          } catch (Exception e) {
            status = "扫描未完成：" + e.getClass().getSimpleName();
          } finally {
            busy = false;
            notifyChange();
            if (rescanRequested) {
              boolean againForce = rescanForce;
              rescanRequested = false;
              rescanForce = false;
              main.post(() -> scan(againForce));
            }
            try {
              context
                  .getContentResolver()
                  .notifyChange(DocumentsContract.buildRootsUri(AUTH), null);
            } catch (Exception ignored) {
            }
          }
        });
  }

  private void walk(
      Uri tree, String parent, String folder, Map<String, Video> out, Set<String> seen)
      throws Exception {
    if (cancelled) throw new InterruptedIOException();
    String visit = tree.getAuthority() + ":" + parent;
    if (!seen.add(visit)) return;
    if (out.size() >= 10000) throw new IOException("目录超过一万项");
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                children,
                new String[] {
                  "document_id", "_display_name", "mime_type", "_size", "last_modified", "flags"
                },
                null,
                null,
                null)) {
      if (c == null) throw new IOException("目录不可访问");
      while (c.moveToNext()) {
        if (cancelled) throw new InterruptedIOException();
        String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
          walk(tree, id, folder, out, seen);
          continue;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ((mime != null && mime.startsWith("video/"))
            || lower.matches(".*\\.(mp4|m4v|mkv|webm|mov|avi|3gp|ts|m2ts|flv)$")) {
          Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
          String identity = identity(uri);
          if (out.containsKey(identity)) continue;
          Video v = new Video();
          v.id = identity;
          v.uri = uri.toString();
          v.name = name == null ? "未命名视频" : name;
          v.folder = folder;
          v.size = c.getLong(3);
          v.modified = c.getLong(4);
          v.flags = c.getInt(5);
          // Only metadata/visual frames may be cached. Exact hashes are always read afresh for
          // matching.
          for (Video prev : videos)
            if (prev.id.equals(v.id)) {
              v.hash = prev.hash;
              if (v.modified > 0 && prev.modified == v.modified && prev.size == v.size) {
                v.duration = prev.duration;
                v.frames = prev.frames;
              }
              break;
            }
          out.put(identity, v);
        }
      }
    }
  }

  public void recordHash(Video v) {
    SharedPreferences.Editor edit = prefs.edit().putString("alias:id:" + v.id, v.hash);
    long physicalTime = prefs.getLong("time:id:" + v.id, 0);
    if (physicalTime > prefs.getLong("time:sha:" + v.hash, 0))
      edit.putLong("time:sha:" + v.hash, physicalTime)
          .putLong("position:sha:" + v.hash, prefs.getLong("position:id:" + v.id, 0));
    edit.apply();
  }

  public Dedupe.Source source(Video v) {
    return new Dedupe.Source() {
      public InputStream open() throws IOException {
        InputStream in = context.getContentResolver().openInputStream(Uri.parse(v.uri));
        if (in == null) throw new IOException("无法读取");
        return in;
      }

      public long size() throws IOException {
        return statSize(v);
      }
    };
  }

  public long statSize(Video v) throws IOException {
    try (Cursor c =
        context
            .getContentResolver()
            .query(Uri.parse(v.uri), new String[] {"_size"}, null, null, null)) {
      if (c == null || !c.moveToFirst() || c.isNull(0)) throw new IOException("无法读取文件长度");
      return c.getLong(0);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  void metadata(Video v) {
    if (v.frames != null && v.duration > 0) return;
    MediaMetadataRetriever r = new MediaMetadataRetriever();
    try {
      r.setDataSource(context, Uri.parse(v.uri));
      v.duration = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      long[] frames = new long[3];
      for (int i = 0; i < 3; i++) {
        Bitmap b =
            Build.VERSION.SDK_INT >= 27
                ? r.getScaledFrameAtTime(
                    v.duration * 1000L * (i + 1) / 4, MediaMetadataRetriever.OPTION_CLOSEST, 9, 8)
                : r.getFrameAtTime(v.duration * 1000L * (i + 1) / 4);
        if (b == null) throw new IOException();
        Bitmap scaled = Bitmap.createScaledBitmap(b, 9, 8, true);
        long h = 0;
        for (int y = 0; y < 8; y++)
          for (int x = 0; x < 8; x++) {
            int p = scaled.getPixel(x, y), q = scaled.getPixel(x + 1, y);
            int a = ((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114;
            int z = ((q >> 16) & 255) * 299 + ((q >> 8) & 255) * 587 + (q & 255) * 114;
            if (a > z) h |= 1L << (y * 8 + x);
          }
        frames[i] = h;
        if (scaled != b) scaled.recycle();
        b.recycle();
      }
      v.frames = frames;
    } catch (Exception ignored) {
    } finally {
      try {
        r.release();
      } catch (Exception ignored) {
      }
    }
  }

  public void thumbnail(Video v, java.util.function.Consumer<Bitmap> callback) {
    String key = v.id + ":" + v.modified + ":" + v.size;
    Bitmap cached = images.get(key);
    if (cached != null) {
      callback.accept(cached);
      return;
    }
    thumbs.execute(
        () -> {
          Bitmap b = null;
          MediaMetadataRetriever r = new MediaMetadataRetriever();
          try {
            r.setDataSource(context, Uri.parse(v.uri));
            b =
                Build.VERSION.SDK_INT >= 27
                    ? r.getScaledFrameAtTime(
                        1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    : r.getFrameAtTime(1000000);
            if (b != null) images.put(key, b);
          } catch (Exception ignored) {
          } finally {
            try {
              r.release();
            } catch (Exception ignored) {
            }
          }
          final Bitmap result = b;
          main.post(() -> callback.accept(result));
        });
  }

  synchronized void regroup() {
    Map<String, Group> map = new LinkedHashMap<>();
    for (Video v : videos) {
      String key = v.available && !v.hash.isEmpty() ? "sha:" + v.hash : "id:" + v.id;
      Group g = map.get(key);
      if (g == null) {
        g = new Group();
        map.put(key, g);
      }
      g.copies.add(v);
    }
    List<Group> result = new ArrayList<>(map.values());
    Collections.sort(result, (a, b) -> Long.compare(b.first().modified, a.first().modified));
    groups = result;
    ArrayList<Group[]> hints = new ArrayList<>();
    int comparisons = 0;
    for (int i = 0; i < result.size() && hints.size() < 100; i++)
      for (int j = i + 1; j < result.size() && hints.size() < 100; j++) {
        if (++comparisons > 1000000) break;
        Video a = result.get(i).first(), b = result.get(j).first();
        if (a.available
            && b.available
            && Dedupe.visuallySimilar(a.frames, b.frames, a.duration, b.duration))
          hints.add(new Group[] {result.get(i), result.get(j)});
      }
    similar = hints;
  }

  public void cleanup(Video keeper, List<Video> remove, java.util.function.Consumer<String> done) {
    if (busy) {
      done.accept("正在扫描，请稍后操作");
      return;
    }
    busy = true;
    status = "正在重新完整核验副本…";
    notifyChange();
    worker.execute(
        () -> {
          int deleted = 0;
          String error = "";
          try {
            if (remove.isEmpty()) throw new IOException("没有选中的副本");
            String keeperId = identity(Uri.parse(keeper.uri));
            String fresh = Dedupe.hash(source(keeper));
            for (Video v : remove) {
              if (identity(Uri.parse(v.uri)).equals(keeperId)) throw new IOException("不能删除保留文件");
              if (!Dedupe.hash(source(v)).equals(fresh)) throw new IOException("文件发生变化，已停止清理");
            }
            for (Video v : remove) {
              if (!Dedupe.verifiedEqual(source(keeper), source(v)))
                throw new IOException("核验失败，已停止");
              if (!DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(v.uri)))
                throw new IOException("存储提供方拒绝删除");
              deleted++;
            }
          } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          }
          final String message = "已删除 " + deleted + " 个副本" + (error.isEmpty() ? "" : "；" + error);
          busy = false;
          main.post(
              () -> {
                done.accept(message);
                scan();
              });
        });
  }

  private void load() {
    try {
      AtomicFile f = new AtomicFile(new File(context.getFilesDir(), "catalog.json"));
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      try (InputStream in = f.openRead()) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
      }
      JSONArray a = new JSONArray(b.toString("UTF-8"));
      List<Video> loaded = new ArrayList<>();
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        Video v = new Video();
        v.id = o.getString("id");
        v.uri = o.getString("uri");
        v.name = o.getString("name");
        v.folder = o.getString("folder");
        v.size = o.getLong("size");
        v.modified = o.optLong("modified");
        v.duration = o.optLong("duration");
        v.hash = o.optString("hash");
        v.available = o.optBoolean("available", true);
        v.flags = o.optInt("flags");
        JSONArray fp = o.optJSONArray("frames");
        if (fp != null && fp.length() == 3) {
          v.frames = new long[3];
          for (int n = 0; n < 3; n++) v.frames[n] = fp.getLong(n);
        }
        loaded.add(v);
      }
      videos = loaded;
    } catch (Exception ignored) {
    }
  }

  private void persist() {
    AtomicFile f = new AtomicFile(new File(context.getFilesDir(), "catalog.json"));
    FileOutputStream stream = null;
    try {
      JSONArray a = new JSONArray();
      for (Video v : videos) {
        JSONObject o = new JSONObject();
        o.put("id", v.id)
            .put("uri", v.uri)
            .put("name", v.name)
            .put("folder", v.folder)
            .put("size", v.size)
            .put("modified", v.modified)
            .put("duration", v.duration)
            .put("hash", v.hash)
            .put("available", v.available)
            .put("flags", v.flags);
        if (v.frames != null) {
          JSONArray fp = new JSONArray();
          for (long h : v.frames) fp.put(h);
          o.put("frames", fp);
        }
        a.put(o);
      }
      stream = f.startWrite();
      stream.write(a.toString().getBytes("UTF-8"));
      f.finishWrite(stream);
    } catch (Exception e) {
      if (stream != null) f.failWrite(stream);
    }
  }
}
