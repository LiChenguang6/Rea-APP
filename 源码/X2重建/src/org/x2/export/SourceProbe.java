package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Explicit phone-side manifest check; credentials remain inside the captured network session. */
final class SourceProbe {
    private static final AtomicBoolean running=new AtomicBoolean();
    static void start(Activity activity,Button button){
        if(!running.compareAndSet(false,true)){android.widget.Toast.makeText(activity,"已有播放源检查正在运行",0).show();return;}
        final AtomicBoolean cancel=new AtomicBoolean();final Handler main=new Handler(Looper.getMainLooper());button.setEnabled(false);
        AlertDialog waiting=new AlertDialog.Builder(activity).setTitle("检查当前播放源").setMessage("正在使用最近播放连接读取媒体清单，通常需要几秒；可取消。请保持手机原来的 VPN 连接。")
                .setNegativeButton("取消",(d,w)->cancel.set(true)).setOnCancelListener(d->cancel.set(true)).create();waiting.show();
        final android.content.Context app=activity.getApplicationContext();
        Thread worker=new Thread(()->{
            String report;
            try{
                PlayerCapture.Source source;String detailsAtCapture=null;long sourceAt=android.os.SystemClock.elapsedRealtime();boolean cached=false;try{source=PlayerCapture.snapshot();}catch(java.io.IOException unavailable){PlaybackDiagnostics.SourceSample sample=PlaybackDiagnostics.recentSource();if(sample==null)throw unavailable;source=sample.source;detailsAtCapture=sample.details;sourceAt=sample.at;cached=true;}if(source==null)throw new java.io.IOException("source unavailable");
                StringBuilder id=new StringBuilder();byte[] digest=MessageDigest.getInstance("SHA-256").digest(source.uri.getBytes(StandardCharsets.UTF_8));for(int i=0;i<6;i++)id.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));
                String version=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
                final long deadline=android.os.SystemClock.elapsedRealtime()+45000;String metrics=PlayerCapture.playbackState();String pageDetails=detailsAtCapture==null?PlayerCapture.pageDetails(source.uri):detailsAtCapture;String details=MediaDownloader.inspect(source.uri,source.userAgent,source.referrer,source.headers,app.getCacheDir(),new MediaDownloader.Progress(){public void update(String text,double fraction){}public boolean cancelled(){return cancel.get()||activity.isDestroyed()||android.os.SystemClock.elapsedRealtime()>deadline;}});
                report="version="+version+"\nsource_id="+id+"\nsource_age_ms="+(android.os.SystemClock.elapsedRealtime()-sourceAt)+"\nsnapshot="+(cached?"recent_cached":"live")+"\n"+pageDetails+metrics+"\n"+details+"\n说明：这是当前或30秒内最近播放连接重新请求到的响应，不一定与先前响应相同。endlist=1 表示所选清单有结束标记；manifest_seconds 是清单分片时长之和。detail_play_time_raw 为详情原始时长字段，detail_can_watch 是原始可播放标记，reason/type 为原始数字，不推测含义。只有 detail_match=exact_uri 才能与当前源对应。短清单不能单独证明是 VIP 限制，也可能是广告或短片。主清单检查选择最高带宽线路，未检查其他线路。不下载分片和密钥，不修改账户权限。";
            }catch(Throwable e){String message=e.getMessage();java.util.regex.Matcher match=java.util.regex.Pattern.compile("HTTP\\s+(\\d{3})").matcher(message==null?"":message);String status=match.find()?match.group(1):"unknown";
                report=cancel.get()?"检查已取消":"播放源检查未完成\nhttp="+status+"\nerror_type="+e.getClass().getSimpleName()+"\n请开启开发测试，返回目标视频播放几秒，再在30秒内重试；本报告不包含地址、请求头或账户凭据。";}
            final String result=report;running.set(false);main.post(()->{if(!activity.isDestroyed()&&!activity.isFinishing()){button.setEnabled(true);waiting.dismiss();if(cancel.get())return;TextView text=new TextView(activity);text.setText(result);text.setTextIsSelectable(true);int pad=(int)(16*activity.getResources().getDisplayMetrics().density);text.setPadding(pad,pad,pad,pad);ScrollView scroll=new ScrollView(activity);scroll.addView(text);new AlertDialog.Builder(activity).setTitle("播放源检查结果").setView(scroll).setPositiveButton("复制",(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("播放源检查",result));}).setNegativeButton("关闭",null).show();}});
        },"X2-source-probe");worker.setDaemon(true);worker.start();
    }
    private SourceProbe(){}
}
