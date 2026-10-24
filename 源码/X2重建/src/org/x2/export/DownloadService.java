package org.x2.export;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Foreground owner of all download/remux/write operations, independent of Activities. */
public final class DownloadService extends Service {
    private static final int NOTICE=3201;
    private static final String CHANNEL="x2-downloads";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());
    private final java.util.concurrent.ExecutorService fileOperations=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final HashSet<String> deleting=new HashSet<String>();
    private final Object pauseLock=new Object();
    private final HashSet<String> running=new HashSet<String>();
    private final String generation=UUID.randomUUID().toString();
    private DownloadStore store;
    private PowerManager.WakeLock wake;
    private volatile boolean closing;
    private boolean ready,processingCommands;
    private static boolean recoveredFiles;
    private int lastStartId;
    public static void start(Context c) {
        Intent intent=new Intent(c,DownloadService.class);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent);else c.startService(intent);
    }
    public static String enqueue(Context c, PlayerCapture.Source source) throws IOException {
        DownloadStore repository=DownloadStore.get(c);
        DownloadStore.Job job=DownloadStore.playback(c,source); repository.add(job);
        try {start(c);} catch(RuntimeException e) {job.state="failed";job.stage="无法启动后台服务，请返回前台重试。";repository.save();throw new IOException(job.stage);}
        return job.id;
    }
    public static void enqueueCache(Context c, File video) throws IOException {
        DownloadStore repository=DownloadStore.get(c);DownloadStore.Job job=DownloadStore.cache(c,video);
        for(DownloadStore.Job old:repository.all())if(old.active()&&old.cache.equals(job.cache))throw new IOException("此缓存已在任务队列中。");
        repository.add(job);try {start(c);} catch(RuntimeException e){job.state="failed";job.stage="无法启动后台服务，请返回前台重试。";repository.save();throw new IOException(job.stage);}
    }
    public static void command(Context c,String action,String id) {
        java.util.ArrayList<String> ids=new java.util.ArrayList<String>();ids.add(id);commandBatch(c,action,ids);
    }
    public static void commandBatch(Context c,String action,java.util.ArrayList<String> ids) {
        Intent intent=new Intent(c,DownloadService.class).setAction(action).putStringArrayListExtra("ids",ids);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent);else c.startService(intent);
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"视频下载",NotificationManager.IMPORTANCE_LOW));
        Notification notification=notification("正在准备下载队列",true);
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTICE,notification);
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":downloads");
        wake.setReferenceCounted(false);
        try {
            store=DownloadStore.get(this);
            if(!recoveredFiles){deleteWork(new File(getCacheDir(),"background-downloads"));recoveredFiles=true;}
            for(DownloadStore.Job job:store.all())if(job.state.equals("running")||job.state.equals("cancelling")) {
                job.owner=generation;job.state="queued";job.stage="任务曾中断，等待重新下载";job.progress=0;
            }
            for(DownloadStore.Job job:store.all())if(job.state.equals("deleting")){job.state="success";job.stage="上次删除可能中断，请重试删除文件";}
            store.save();ready=true;
        } catch(IOException e) {manager.notify(NOTICE,notification(e.getMessage(),false));stopForeground(false);stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        lastStartId=startId;
        // A new start can race with idle stopForeground/stopSelf on this instance.
        Notification notice=notification("正在处理下载任务",true);
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTICE,notice);
        if(!ready)return START_NOT_STICKY;
        if(intent!=null&&intent.getAction()!=null) {
            java.util.ArrayList<String> ids=intent.getStringArrayListExtra("ids");
            if(ids==null){ids=new java.util.ArrayList<String>();ids.add(intent.getStringExtra("id"));}
            processingCommands=true;
            for(String id:new java.util.LinkedHashSet<String>(ids)){
            DownloadStore.Job job=store.find(id);
            if(job!=null) {
                String action=intent.getAction();
                if(job.state.equals("deleting"))continue;
                if(action.equals("remove_file")&&job.state.equals("success")){deleteSavedFile(job);continue;}
                if(action.equals("remove_file"))action="remove";
                if(action.equals("pause")&&(job.state.equals("queued")||job.state.equals("running"))) {
                    job.paused=true;job.resumeRequested=false;job.state="paused";job.stage="已暂停";
                } else if(action.equals("resume")&&job.state.equals("paused")) {
                    job.cancelled=false;job.resumeRequested=running.contains(job.id);job.paused=job.resumeRequested;job.state=job.paused?"paused":"queued";job.stage=job.paused?"等待空闲下载名额后继续":"等待继续下载";
                    synchronized(pauseLock){pauseLock.notifyAll();}
                } else if(action.equals("retry")&&!job.active()&&(!job.cache.isEmpty()||job.source!=null)) {
                    job.cancelled=false;job.remove=false;job.paused=false;job.state="queued";job.stage="等待重试";job.progress=0;
                } else if(action.equals("cancel")||action.equals("remove")) {
                    job.paused=false;job.resumeRequested=false; synchronized(pauseLock){pauseLock.notifyAll();}
                    if(job.state.equals("success")) {if(action.equals("remove"))removeRecord(job);}
                    else if(running.contains(job.id)) {job.cancelled=true;job.remove=action.equals("remove");job.state="cancelling";job.stage=job.remove?"正在取消并删除任务":"正在取消";}
                    else if(!job.document.isEmpty()) {job.cancelled=true;job.remove=action.equals("remove");job.state="queued";job.stage="正在清理未完成文件";}
                    else if(action.equals("remove"))removeRecord(job);
                    else {job.cancelled=true;job.state="cancelled";job.stage="已取消";}
                }
            }
            }
            processingCommands=false;
            persist();
        }
        pump();return START_STICKY;
    }
    private void deleteSavedFile(final DownloadStore.Job job){
        if(!deleting.add(job.id))return;job.state="deleting";job.stage="正在删除 MP4 文件";
        final String document=job.document;
        fileOperations.execute(()->{
            final boolean ok=!document.isEmpty()&&deleteSavedDocument(Uri.parse(document));
            main.post(()->{if(closing)return;deleting.remove(job.id);if(ok)removeRecord(job);else{job.state="success";job.stage="无法删除 MP4（文件不存在、无权限或存储不可用）；可重试或仅删除记录";}persist();pump();});
        });
    }
    private void removeRecord(DownloadStore.Job job) {try{if(processingCommands)store.removeWithoutSave(job);else store.remove(job);}catch(IOException e){job.stage=e.getMessage();}}
    private void persist(){try{store.save();}catch(IOException e){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(e.getMessage(),true));}}
    private void pump() {
        if(closing||!ready)return;
        int limit=DownloadConfig.parallel(this);
        int pausedWorkers=0;for(DownloadStore.Job j:store.all())if(j.paused&&running.contains(j.id))pausedWorkers++;
        for(DownloadStore.Job j:store.all())if(j.paused&&j.resumeRequested&&running.contains(j.id)&&running.size()-pausedWorkers<limit){j.paused=false;j.resumeRequested=false;j.state="running";j.stage="继续下载";pausedWorkers--;synchronized(pauseLock){pauseLock.notifyAll();}}
        int threads=Math.min(64,limit+pausedWorkers);
        if(threads>workers.getMaximumPoolSize()){workers.setMaximumPoolSize(threads);workers.setCorePoolSize(threads);}
        else {workers.setCorePoolSize(threads);workers.setMaximumPoolSize(threads);}
        workers.allowCoreThreadTimeOut(true);
        for(final DownloadStore.Job job:store.all()) {
            if(running.size()-pausedWorkers>=limit||running.size()>=64)break;
            if(!job.state.equals("queued")||running.contains(job.id))continue;
            job.owner=generation;job.state="running";job.stage=job.cancelled?"正在清理任务":"开始下载";job.progress=0;running.add(job.id);
            persist();
            workers.execute(new Runnable(){@Override public void run(){execute(job);}});
        }
        main.removeCallbacks(tick);
        if(!running.isEmpty()||!deleting.isEmpty()) {
            if((running.size()>pausedWorkers||!deleting.isEmpty())&&!wake.isHeld())wake.acquire();
            if(running.size()==pausedWorkers&&deleting.isEmpty()&&wake.isHeld())wake.release();
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(summary(),true));
            main.postDelayed(tick,1000);
        } else {
            if(wake.isHeld())wake.release();
            stopForeground(false);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(summary(),false));
            stopSelfResult(lastStartId);
        }
    }
    private final Runnable tick=new Runnable(){@Override public void run(){pump();}};
    private boolean cancelled(DownloadStore.Job job){return closing||!generation.equals(job.owner)||job.cancelled||Thread.currentThread().isInterrupted();}
    private void awaitActive(DownloadStore.Job job)throws IOException {
        synchronized(pauseLock){while(job.paused&&!cancelled(job)){try{pauseLock.wait(250);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("任务已中断");}}}
    }
    private void check(DownloadStore.Job job)throws IOException{awaitActive(job);if(cancelled(job))throw new IOException("已取消");}
    private void update(DownloadStore.Job job,String stage,int progress){if(!closing&&generation.equals(job.owner)&&!job.cancelled&&!job.paused){job.stage=stage;job.progress=Math.max(0,Math.min(99,progress));}}
    private void execute(final DownloadStore.Job job) {
        File work=new File(getCacheDir(),"background-downloads/"+job.id+"/"+UUID.randomUUID().toString());
        Uri destination=null;boolean success=false;String failure="";boolean cleanup=true;
        try {
            if(closing||!generation.equals(job.owner))throw new IOException("已取消");
            if(!job.document.isEmpty()) {
                Uri stale=Uri.parse(job.document);
                if(!deleteDocument(stale))throw new IOException("无法清理上次未完成的文件，请恢复原目录权限后重试。");
                job.document="";store.save();
            }
            check(job);
            if(!work.mkdirs())throw new IOException("无法创建临时目录，请检查可用空间。");
            File input;double duration;
            if(!job.cache.isEmpty()) {
                PlaylistPreparer.Prepared p=PlaylistPreparer.prepare(new File(job.cache),work);input=p.playlist;duration=p.durationSeconds;
            } else {
                PlayerCapture.Source source=job.source;
                if(source==null)throw new IOException("播放信息已失效，请返回播放页面重新下载。");
                MediaDownloader.Result result=MediaDownloader.download(source.uri,source.userAgent,source.referrer,source.headers,work,new MediaDownloader.Progress(){
                    @Override public boolean cancelled(){return DownloadService.this.cancelled(job);}
                    @Override public void awaitActive() throws IOException {DownloadService.this.awaitActive(job);}
                    @Override public void transferFailed(){if(job.paused)job.restartNeeded=true;}
                    @Override public void update(String stage,double amount){DownloadService.this.update(job,stage,(int)(Math.max(0,amount)*80));}
                });input=result.input;duration=result.durationSeconds;
            }
            check(job);update(job,"正在合并并验证 MP4",80);File mp4=new File(work,"output.mp4");
            String error=NativeRemux.run(input.getAbsolutePath(),mp4.getAbsolutePath(),duration,new NativeRemux.Progress(){
                @Override public boolean cancelled(){try{awaitActive(job);}catch(IOException e){return true;}return DownloadService.this.cancelled(job);}
                @Override public void update(double value){DownloadService.this.update(job,"正在合并并验证 MP4",80+(int)(value*15));}
            });
            if(!error.isEmpty())throw new IOException("合并或验证失败："+error);
            check(job);if(mp4.length()<64)throw new IOException("生成的 MP4 无有效内容。");
            update(job,"正在写入保存文件夹",95);
            Uri tree=Uri.parse(job.folder);
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            String name=VideoFilename.mp4(job.title);
            destination=DocumentsContract.createDocument(getContentResolver(),parent,"video/mp4",name);
            if(destination==null)throw new IOException("无法在指定文件夹创建文件，请在设置中重新选择目录。");
            check(job);job.document=destination.toString();store.save();
            try(FileInputStream from=new FileInputStream(mp4);OutputStream to=getContentResolver().openOutputStream(destination,"wt")) {
                if(to==null)throw new IOException("保存文件夹无法写入。");byte[] buffer=new byte[1024*1024];int count;
                while((count=from.read(buffer))!=-1){check(job);to.write(buffer,0,count);}to.flush();
            }
            check(job);success=true;
        } catch(Exception|LinkageError e) {failure=safe(e);}
        finally {
            if(!success&&destination!=null){cleanup=deleteDocument(destination);if(cleanup&&generation.equals(job.owner)&&destination.toString().equals(job.document))job.document="";}
            deleteWork(work);
            File root=work.getParentFile();if(root!=null)root.delete();
        }
        final boolean ok=success, clean=cleanup;final String reason=failure;
        main.post(new Runnable(){@Override public void run(){
            if(closing||!generation.equals(job.owner))return;
            running.remove(job.id);
            if(ok){job.state="success";job.stage="已保存 MP4";job.progress=100;job.source=null;}
            else if(job.restartNeeded&&!job.cancelled&&!job.paused){job.restartNeeded=false;job.state="queued";job.stage="暂停期间连接失效，重新下载";}
            else if(job.paused&&!job.cancelled){job.state="paused";job.stage="已暂停；连接已断开，继续时重新下载";}
            else {job.state=job.cancelled?"cancelled":"failed";job.stage=reason+(clean?"":"；目标半成品需恢复目录权限后清理");}
            if(job.remove&&clean&&(ok||job.document.isEmpty()))removeRecord(job);
            else {job.remove=false;persist();}
            pump();
        }});
    }
    private boolean deleteSavedDocument(Uri uri){try{return DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception e){return false;}}
    private boolean deleteDocument(Uri uri) {
        try {return DocumentsContract.deleteDocument(getContentResolver(),uri);}
        catch(java.io.FileNotFoundException e){return true;}catch(Exception e){return false;}
    }
    private static void deleteWork(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteWork(child);file.delete();}
    private static String safe(Throwable e) {
        if(e instanceof SecurityException)return "保存目录权限已失效，请在设置中重新选择文件夹。";
        if(e instanceof LinkageError)return "当前设备无法启动视频保存组件。";
        String text=e.getMessage();return text==null||text.contains("://")?"保存失败（"+e.getClass().getSimpleName()+"），请重试。":text;
    }
    private String summary(){int waiting=0,done=0,failed=0;for(DownloadStore.Job j:store.all()){if(j.state.equals("queued"))waiting++;if(j.state.equals("success"))done++;if(j.state.equals("failed"))failed++;}return "进行中 "+running.size()+" · 等待 "+waiting+" · 删除中 "+deleting.size()+" · 已完成 "+done+(failed>0?" · 失败 "+failed:"");}
    private Notification notification(String text,boolean ongoing) {
        Intent intent=new Intent(this,DownloadTasksActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("X2 视频下载").setContentText(text)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing).setShowWhen(false).build();
    }
    @Override public void onTaskRemoved(Intent rootIntent){super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){closing=true;synchronized(pauseLock){pauseLock.notifyAll();}main.removeCallbacks(tick);workers.shutdownNow();fileOperations.shutdownNow();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
