package org.x2.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local-only derived previews. Provider/video I/O never runs on the UI thread. */
final class VideoThumbnails {
    private static final int MAGIC=0x58325435;
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService worker=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(256),r->{Thread t=new Thread(r,"X2-video-preview");t.setDaemon(true);return t;});
    private static final LruCache<String,Preview> memory=new LruCache<String,Preview>(12*1024){protected int sizeOf(String key,Preview value){return value.bitmap==null?16:Math.max(1,value.bitmap.getAllocationByteCount()/1024);}};
    private static final HashMap<String,ArrayList<Target>> pending=new HashMap<String,ArrayList<Target>>();
    private static final class Preview {final Bitmap bitmap;final long duration;final long at=android.os.SystemClock.elapsedRealtime();Preview(Bitmap bitmap,long duration){this.bitmap=bitmap;this.duration=duration;}}
    private static final class Target {final WeakReference<ImageView> image;final WeakReference<TextView> time;Target(ImageView i,TextView t){image=new WeakReference<ImageView>(i);time=new WeakReference<TextView>(t);}}
    static void bind(Context context,String id,String document,ImageView image,TextView time){
        String key=key(id,document);image.setTag(key);time.setTag(key);Preview hit=memory.get(key);if(hit!=null&&hit.bitmap==null&&android.os.SystemClock.elapsedRealtime()-hit.at>30000){memory.remove(key);hit=null;}show(image,time,hit);if(hit!=null||document.isEmpty())return;
        ArrayList<Target> targets=pending.get(key);if(targets!=null){for(Iterator<Target> it=targets.iterator();it.hasNext();){ImageView old=it.next().image.get();if(old==null||old==image)it.remove();}targets.add(new Target(image,time));return;}
        targets=new ArrayList<Target>();targets.add(new Target(image,time));pending.put(key,targets);final Context app=context.getApplicationContext();
        try{worker.execute(()->{Preview value;try{value=read(app,key);if(value==null){value=extract(app,document);if(value.bitmap!=null)write(app,key,value);}memory.put(key,value);}catch(Exception|OutOfMemoryError failure){value=new Preview(null,-1);}final Preview result=value;main.post(()->{ArrayList<Target> waiting=pending.remove(key);if(waiting!=null)for(Target target:waiting){ImageView i=target.image.get();TextView t=target.time.get();if(i!=null&&t!=null&&key.equals(i.getTag())&&key.equals(t.getTag()))show(i,t,result);}});});}
        catch(RejectedExecutionException e){pending.remove(key);}
    }
    private static void show(ImageView image,TextView time,Preview value){if(value==null||value.bitmap==null)image.setImageDrawable(null);else image.setImageBitmap(value.bitmap);time.setText(value==null||value.duration<0?"--:--":format(value.duration));}
    private static String key(String id,String document){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((id+"\n"+document).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String format(long duration){long seconds=duration/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private static Preview extract(Context c,String document){MediaMetadataRetriever retriever=null;Bitmap frame=null;long duration=-1;
        try{retriever=new MediaMetadataRetriever();retriever.setDataSource(c,Uri.parse(document));String text=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(text!=null)try{duration=Long.parseLong(text);}catch(NumberFormatException ignored){}
            if(android.os.Build.VERSION.SDK_INT>=27)frame=retriever.getScaledFrameAtTime(1000000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,640,360);else frame=retriever.getFrameAtTime(1000000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(frame==null){if(android.os.Build.VERSION.SDK_INT>=27)frame=retriever.getScaledFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,640,360);else frame=retriever.getFrameAtTime();}
            if(frame!=null&&(frame.getWidth()>640||frame.getHeight()>360)){float scale=Math.min(640f/frame.getWidth(),360f/frame.getHeight());Bitmap small=Bitmap.createScaledBitmap(frame,Math.max(1,Math.round(frame.getWidth()*scale)),Math.max(1,Math.round(frame.getHeight()*scale)),true);if(small!=frame)frame.recycle();frame=small;}
        }catch(Exception|OutOfMemoryError ignored){}finally{try{if(retriever!=null)retriever.release();}catch(Exception ignored){}}
        if(frame!=null&&(frame.getWidth()>640||frame.getHeight()>360)){frame.recycle();frame=null;}return new Preview(frame,duration);
    }
    private static File directory(Context c){return new File(c.getCacheDir(),"video-previews-v1");}
    private static Preview read(Context c,String key){File file=new File(directory(c),key+".thumb");if(!file.isFile()||file.length()>1024*1024)return null;
        try(DataInputStream in=new DataInputStream(new AtomicFile(file).openRead())){if(in.readInt()!=MAGIC)return null;long duration=in.readLong();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeStream(in,null,bounds);if(bounds.outWidth<=0||bounds.outWidth>640||bounds.outHeight<=0||bounds.outHeight>360)return null;
            try(DataInputStream again=new DataInputStream(new AtomicFile(file).openRead())){again.readInt();again.readLong();Bitmap bitmap=BitmapFactory.decodeStream(again);if(bitmap==null)return null;file.setLastModified(System.currentTimeMillis());return new Preview(bitmap,duration);}}
        catch(Exception|OutOfMemoryError ignored){return null;}
    }
    private static void write(Context c,String key,Preview value){File dir=directory(c);if(!dir.isDirectory()&&!dir.mkdirs())return;FileOutputStream out=null;AtomicFile file=new AtomicFile(new File(dir,key+".thumb"));
        try{out=file.startWrite();DataOutputStream header=new DataOutputStream(out);header.writeInt(MAGIC);header.writeLong(value.duration);header.flush();if(!value.bitmap.compress(Bitmap.CompressFormat.JPEG,78,out))throw new IOException();file.finishWrite(out);out=null;trim(dir);}
        catch(Exception|OutOfMemoryError e){if(out!=null)file.failWrite(out);}
    }
    private static void trim(File dir){File[] files=dir.listFiles((d,n)->n.endsWith(".thumb"));if(files==null)return;Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));long bytes=0;for(int i=0;i<files.length;i++){bytes+=files[i].length();if(i>=128||bytes>32L*1024*1024)new AtomicFile(files[i]).delete();}}
    private VideoThumbnails(){}
}
