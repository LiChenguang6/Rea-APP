package org.x2.export;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.net.Uri;
import android.widget.*;
import android.view.ViewGroup;
import java.io.IOException;
/** Uses stable job identity; progress is owned by X2 and committed across process restarts. */
public final class LocalPlaybackActivity extends Activity {
 private VideoView video;private String id;private boolean prepared,completed,positionReady,foreground;private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable checkpoint=new Runnable(){public void run(){save();handler.postDelayed(this,2000);}};
 public static void open(Context c,String id){c.startActivity(new Intent(c,LocalPlaybackActivity.class).putExtra("jobId",id));}
 public void onCreate(Bundle state){super.onCreate(state);id=getIntent().getStringExtra("jobId");
  try{
   DownloadStore.Job job=DownloadStore.get(this).find(id);
   if(job==null||!"success".equals(job.state)||job.document.isEmpty())throw new IOException("视频记录不可用");
   setTitle(job.title);video=new VideoView(this);video.setContentDescription("本地视频播放器");
   setContentView(video,new ViewGroup.LayoutParams(-1,-1));MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);
   video.setOnPreparedListener(mp->{prepared=true;int pos=getSharedPreferences("x2-local-playback",0).getInt(id,0);
    if(pos>0&&pos<video.getDuration()-1000){mp.setOnSeekCompleteListener(player->{positionReady=true;if(foreground)video.start();handler.postDelayed(checkpoint,2000);});if(android.os.Build.VERSION.SDK_INT>=26)mp.seekTo((long)pos,android.media.MediaPlayer.SEEK_CLOSEST);else video.seekTo(pos);}
    else{positionReady=true;if(foreground)video.start();handler.postDelayed(checkpoint,2000);}
   });
   video.setOnCompletionListener(mp->{completed=true;getSharedPreferences("x2-local-playback",0).edit().remove(id).commit();});
   video.setOnErrorListener((mp,w,e)->{Toast.makeText(this,"无法播放本地视频，请确认文件仍可访问",Toast.LENGTH_LONG).show();return true;});
   video.setVideoURI(Uri.parse(job.document));
  }catch(Exception e){Toast.makeText(this,"无法打开本地视频",Toast.LENGTH_LONG).show();finish();}
 }
 private void save(){if(prepared&&positionReady&&!completed&&video!=null)getSharedPreferences("x2-local-playback",0).edit().putInt(id,Math.max(0,video.getCurrentPosition())).commit();}
 protected void onPause(){foreground=false;save();if(video!=null)video.pause();super.onPause();}
 protected void onResume(){super.onResume();foreground=true;if(prepared&&positionReady&&!completed)video.start();}
 protected void onDestroy(){handler.removeCallbacks(checkpoint);save();if(video!=null)video.stopPlayback();super.onDestroy();}
}
