package org.rea.cineshelf.test;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.rea.cineshelf.*;

public class FlowTest extends Instrumentation {
  void check(boolean ok, String m) {
    if (!ok) throw new AssertionError(m);
    android.util.Log.i("CineShelfTest", "PASS " + m);
  }

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      Context c = getTargetContext();
      Catalog lib = Catalog.get(c);
      c.getSharedPreferences("library", 0).edit().clear().commit();
      c.getContentResolver()
          .call(Uri.parse("content://org.rea.cineshelf.fixtures"), "reset", null, null);
      lib.addRoot(Uri.parse("content://org.rea.cineshelf.fixtures/tree/root"));
      waitScan(lib);
      check(lib.videos.size() == 4, "four newly created physical videos");
      check(lib.groups.size() == 3, "one exact pair merged");
      Catalog.Group pair = null;
      for (Catalog.Group g : lib.groups) if (g.copies.size() == 2) pair = g;
      check(pair != null, "duplicate group exists");
      check(lib.similar.size() > 0, "transcoded synthetic video flagged as hint");
      Catalog.Video keeper = pair.copies.get(0), copy = pair.copies.get(1);
      if (keeper.name.contains("copy")) {
        Catalog.Video swap = keeper;
        keeper = copy;
        copy = swap;
      }
      String stable = keeper.token();
      String physicalAlias = "id:" + copy.id;
      long count = 0;
      try (java.io.InputStream in =
          c.getContentResolver().openInputStream(LibraryProvider.uri(stable))) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) count += n;
      }
      check(count == keeper.size, "virtual entry streams original bytes");
      Intent external =
          new Intent()
              .setClassName("org.rea.cineshelf.test", "org.rea.cineshelf.test.ReadActivity")
              .setDataAndType(LibraryProvider.uri(stable), "video/*")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      external.setClipData(ClipData.newRawUri("video", external.getData()));
      c.startActivity(external);
      SystemClock.sleep(1500);
      Intent pi =
          new Intent(c, PlayerActivity.class)
              .putExtra("token", stable)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      Activity player = startActivitySync(pi);
      Field pf = PlayerActivity.class.getDeclaredField("player");
      pf.setAccessible(true);
      Field rf = PlayerActivity.class.getDeclaredField("ready");
      rf.setAccessible(true);
      long until = SystemClock.elapsedRealtime() + 60000;
      while (!rf.getBoolean(player) && SystemClock.elapsedRealtime() < until)
        SystemClock.sleep(200);
      check(rf.getBoolean(player), "real MediaPlayer prepared synthetic MP4");
      runOnMainSync(
          () -> {
            try {
              android.media.MediaPlayer p = (android.media.MediaPlayer) pf.get(player);
              p.seekTo(6000, android.media.MediaPlayer.SEEK_CLOSEST);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      SystemClock.sleep(1800);
      runOnMainSync(player::finish);
      SystemClock.sleep(600);
      check(lib.position(keeper) >= 5000, "playback position saved after leaving player");
      Activity resumed = startActivitySync(pi);
      until = SystemClock.elapsedRealtime() + 60000;
      while ((!rf.getBoolean(resumed)
              || ((android.media.MediaPlayer) pf.get(resumed)).getCurrentPosition() < 5000)
          && SystemClock.elapsedRealtime() < until) SystemClock.sleep(200);
      check(
          ((android.media.MediaPlayer) pf.get(resumed)).getCurrentPosition() >= 5000,
          "playback resumes near saved position");
      runOnMainSync(resumed::finish);
      SystemClock.sleep(600);
      final Catalog.Video keep = keeper, remove = copy;
      c.getContentResolver()
          .call(Uri.parse("content://org.rea.cineshelf.fixtures"), "mutate", null, null);
      String rejected = clean(lib, keep, remove);
      waitScan(lib);
      check(
          lib.videos.size() == 4 && rejected.contains("变化"),
          "changed duplicate refused without deletion");
      c.getContentResolver()
          .call(Uri.parse("content://org.rea.cineshelf.fixtures"), "reset", null, null);
      lib.scan();
      waitScan(lib);
      pair = null;
      for (Catalog.Group g : lib.groups) if (g.copies.size() == 2) pair = g;
      keeper = pair.first();
      copy = pair.copies.get(1);
      if (keeper.name.contains("copy")) {
        Catalog.Video swap = keeper;
        keeper = copy;
        copy = swap;
      }
      check(clean(lib, keeper, keeper).contains("不能删除"), "keeper cannot be deleted");
      waitScan(lib);
      String message = clean(lib, keeper, copy);
      waitScan(lib);
      check(
          message.contains("已删除 1") && lib.videos.size() == 3,
          "only verified redundant physical file deleted");
      check(lib.find(stable) != null, "virtual hash entry survives cleanup and rescan");
      check(
          lib.find(physicalAlias) != null, "deleted-copy virtual alias resolves to retained video");
      startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      result.putString(
          "result",
          "PASS: folder scan, exact merge, transcode hint, virtual bytes, playback, resume,"
              + " mutation refusal, keeper protection, cleanup and stable aliases");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      android.util.Log.e("CineShelfTest", "FAIL", e);
      result.putString("failure", e.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  void waitScan(Catalog lib) throws Exception {
    SystemClock.sleep(300);
    long until = SystemClock.elapsedRealtime() + 180000;
    while (lib.busy && SystemClock.elapsedRealtime() < until) SystemClock.sleep(200);
    if (lib.busy) throw new AssertionError("scan timed out");
  }

  String clean(Catalog lib, Catalog.Video keeper, Catalog.Video remove) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    String[] out = {""};
    runOnMainSync(
        () ->
            lib.cleanup(
                keeper,
                Arrays.asList(remove),
                s -> {
                  out[0] = s;
                  latch.countDown();
                }));
    if (!latch.await(180, TimeUnit.SECONDS)) throw new AssertionError("cleanup timed out");
    return out[0];
  }
}
