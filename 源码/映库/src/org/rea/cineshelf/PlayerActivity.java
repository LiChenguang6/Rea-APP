package org.rea.cineshelf;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

public class PlayerActivity extends Activity implements TextureView.SurfaceTextureListener {
  Catalog catalog;
  Catalog.Video video;
  MediaPlayer player;
  TextureView texture;
  FrameLayout root, stage;
  LinearLayout overlay;
  TextView play, clock, speed;
  SeekBar seek;
  Handler handler = new Handler();
  Surface surface;
  boolean ready, dragging, seeking, initialSeek, wasPlaying = true, foreground;
  long resume;
  float rate = 1f;
  AudioManager audio;
  AudioFocusRequest focus;
  final Runnable tick =
      new Runnable() {
        public void run() {
          if (ready && player != null) {
            try {
              if (!dragging && !seeking) {
                int pos = player.getCurrentPosition();
                seek.setProgress(pos);
                clock.setText(Ui.time(pos) + " / " + Ui.time(player.getDuration()));
              }
              if (!initialSeek) save();
              play.setText(player.isPlaying() ? "Ⅱ" : "▶");
            } catch (Exception ignored) {
            }
          }
          handler.postDelayed(this, 1000);
        }
      };
  final AudioManager.OnAudioFocusChangeListener focusListener =
      change -> {
        if (change <= 0) pause();
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    catalog = Catalog.get(this);
    video =
        catalog.find(
            getIntent().getStringExtra("token") == null ? "" : getIntent().getStringExtra("token"));
    if (video == null && getIntent().getData() != null) {
      Uri uri = getIntent().getData();
      if (!"content".equals(uri.getScheme())) {
        finish();
        return;
      }
      video = new Catalog.Video();
      video.uri = uri.toString();
      video.id = uri.toString();
      video.name = "外部视频";
      try (android.database.Cursor c =
          getContentResolver().query(uri, new String[] {"_display_name"}, null, null, null)) {
        if (c != null && c.moveToFirst()) video.name = c.getString(0);
      } catch (Exception ignored) {
      }
    }
    if (video == null) {
      Toast.makeText(this, "视频不可用，请重新授权目录", 1).show();
      finish();
      return;
    }
    resume = b == null ? catalog.position(video) : b.getLong("position", catalog.position(video));
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
            .setOnAudioFocusChangeListener(focusListener)
            .build();
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    Ui.insets(this, root);
    stage = new FrameLayout(this);
    root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
    texture = new TextureView(this);
    texture.setSurfaceTextureListener(this);
    stage.addView(texture, new FrameLayout.LayoutParams(-1, -1));
    overlay = Ui.column(this);
    GradientDrawable gradient =
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xd9000000, 0x08000000, 0xd9000000});
    overlay.setBackground(gradient);
    Ui.pad(overlay, 18, 14, 18, 18);
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout top = Ui.row(this);
    top.addView(Ui.iconButton(this, "back", this::finish));
    TextView title = Ui.text(this, video.name, 15, Ui.INK);
    title.setMaxLines(2);
    Ui.pad(title, 12, 0, 8, 0);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    speed = Ui.text(this, "1.0×", 13, Ui.INK);
    Ui.pad(speed, 12, 10, 12, 10);
    Ui.round(speed, 0x553d3e54, 12);
    speed.setOnClickListener(v -> chooseSpeed());
    top.addView(speed);
    overlay.addView(top);
    FrameLayout center = new FrameLayout(this);
    overlay.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout controls = Ui.row(this);
    controls.setGravity(Gravity.CENTER);
    TextView back = Ui.text(this, "↶ 10", 18, Ui.INK);
    Ui.pad(back, 24, 24, 24, 24);
    back.setOnClickListener(v -> jump(-10000));
    controls.addView(back);
    play = Ui.text(this, "▶", 32, Ui.INK);
    play.setGravity(Gravity.CENTER);
    Ui.round(play, 0x77494a64, 40);
    controls.addView(play, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)));
    play.setOnClickListener(v -> toggle());
    TextView ahead = Ui.text(this, "10 ↷", 18, Ui.INK);
    Ui.pad(ahead, 24, 24, 24, 24);
    ahead.setOnClickListener(v -> jump(10000));
    controls.addView(ahead);
    center.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    clock = Ui.text(this, "正在打开本地视频…", 12, Ui.INK);
    overlay.addView(clock);
    seek = new SeekBar(this);
    seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PINK));
    seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.PINK));
    overlay.addView(seek, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {
            dragging = true;
          }

          public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (user && ready) clock.setText(Ui.time(p) + " / " + Ui.time(player.getDuration()));
          }

          public void onStopTrackingTouch(SeekBar s) {
            if (ready) seekTo(s.getProgress());
            dragging = false;
          }
        });
    LinearLayout foot = Ui.row(this);
    TextView label = Ui.text(this, "本地播放 · 自动保存进度", 11, Ui.MUTED);
    foot.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    TextView rotate = Ui.text(this, "横屏 ⛶", 13, Ui.INK);
    Ui.pad(rotate, 12, 8, 12, 8);
    rotate.setOnClickListener(
        v ->
            setRequestedOrientation(
                getResources().getConfiguration().orientation == 2
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
    foot.addView(rotate);
    overlay.addView(foot);
    stage.setOnClickListener(
        v ->
            overlay.setVisibility(
                overlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    center.setOnClickListener(v -> overlay.setVisibility(View.GONE));
    handler.post(tick);
  }

  public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture s, int w, int h) {
    surface = new Surface(s);
    open();
  }

  public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) {
    fit();
  }

  public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s) {
    save();
    release();
    return true;
  }

  public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s) {}

  void open() {
    try {
      player = new MediaPlayer();
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build());
      player.setSurface(surface);
      player.setDataSource(this, Uri.parse(video.uri));
      player.setOnVideoSizeChangedListener((p, w, h) -> fit());
      player.setOnPreparedListener(
          p -> {
            ready = true;
            seek.setMax(p.getDuration());
            fit();
            p.setOnSeekCompleteListener(
                mp -> {
                  seeking = false;
                  if (initialSeek) {
                    initialSeek = false;
                    if (wasPlaying) start();
                  }
                });
            if (resume > 0 && resume < p.getDuration() - 1500) {
              initialSeek = true;
              seekTo(resume);
            } else {
              initialSeek = false;
              if (wasPlaying) start();
            }
          });
      player.setOnCompletionListener(
          p -> {
            catalog.saveProgress(video, 0, p.getDuration());
            resume = 0;
            play.setText("▶");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          });
      player.setOnErrorListener(
          (p, a, b) -> {
            error("系统解码器无法播放此文件（" + a + " / " + b + "）。可从影库菜单尝试其它播放器。");
            return true;
          });
      player.prepareAsync();
    } catch (Exception e) {
      error("无法打开视频：请检查文件是否仍存在、目录权限及编码格式。");
    }
  }

  void fit() {
    if (player == null) return;
    int w = player.getVideoWidth(),
        h = player.getVideoHeight(),
        sw = stage.getWidth(),
        sh = stage.getHeight();
    if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return;
    float scale = Math.min(sw / (float) w, sh / (float) h);
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams((int) (w * scale), (int) (h * scale), Gravity.CENTER);
    texture.setLayoutParams(lp);
  }

  void start() {
    if (!ready || player == null || !foreground) return;
    try {
      if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        player.start();
        player.setPlaybackParams(new PlaybackParams().setSpeed(rate));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      }
    } catch (Exception e) {
      Toast.makeText(this, "此编码暂不支持该播放速度", 0).show();
    }
  }

  void pause() {
    if (ready && player != null)
      try {
        player.pause();
        save();
      } catch (Exception ignored) {
      }
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  void toggle() {
    if (!ready || initialSeek) return;
    if (player.isPlaying()) pause();
    else start();
  }

  void seekTo(long position) {
    if (!ready) return;
    seeking = true;
    resume = Math.max(0, Math.min(position, player.getDuration()));
    player.seekTo(resume, MediaPlayer.SEEK_CLOSEST);
  }

  void jump(int ms) {
    if (ready && !initialSeek) seekTo(player.getCurrentPosition() + ms);
  }

  void chooseSpeed() {
    String[] labels = {"0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×"};
    float[] rates = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
    new AlertDialog.Builder(this)
        .setTitle("播放速度")
        .setItems(
            labels,
            (d, i) -> {
              rate = rates[i];
              speed.setText(labels[i]);
              if (ready)
                try {
                  boolean playing = player.isPlaying();
                  player.setPlaybackParams(new PlaybackParams().setSpeed(rate));
                  if (!playing) player.pause();
                } catch (Exception e) {
                  Toast.makeText(this, "此编码不支持变速", 0).show();
                }
            })
        .show();
  }

  void save() {
    if (ready && !initialSeek && !seeking && player != null)
      try {
        resume = player.getCurrentPosition();
        catalog.saveProgress(video, resume, player.getDuration());
      } catch (Exception ignored) {
      }
  }

  void release() {
    if (player != null) {
      player.release();
      player = null;
    }
    ready = false;
    if (surface != null) {
      surface.release();
      surface = null;
    }
  }

  void error(String text) {
    ready = false;
    clock.setText("播放失败");
    new AlertDialog.Builder(this)
        .setTitle("暂时无法播放")
        .setMessage(text)
        .setPositiveButton("返回影库", (d, w) -> finish())
        .show();
  }

  @Override
  protected void onPause() {
    foreground = false;
    wasPlaying = ready && player != null && player.isPlaying();
    save();
    pause();
    audio.abandonAudioFocusRequest(focus);
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (ready && wasPlaying) start();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    save();
    b.putLong("position", resume);
    super.onSaveInstanceState(b);
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration c) {
    super.onConfigurationChanged(c);
    stage.post(this::fit);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    release();
    audio.abandonAudioFocusRequest(focus);
    super.onDestroy();
  }
}
