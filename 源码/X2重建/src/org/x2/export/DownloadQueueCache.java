package org.x2.export;

import android.content.Context;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process memory only; no signed URLs or headers are exposed to the UI cache. */
final class DownloadQueueCache {
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    static volatile ArrayList<Row> rows=new ArrayList<Row>();
    static volatile boolean loaded;
    static volatile String error="",caption="正在读取下载记录…";
    private static boolean warming;
    static final class Row {
        final String id,title,state,stage,document;final int progress;
        Row(DownloadStore.Job j){id=j.id;title=j.title;state=j.state;stage=j.stage;document=j.document;progress=j.progress;}
    }
    static synchronized void prewarm(Context c){if(warming)return;warming=true;final Context app=c.getApplicationContext();worker.execute(()->read(app));}
    static void read(Context c){try{
        ArrayList<Row> next=new ArrayList<Row>();for(DownloadStore.Job j:DownloadStore.get(c).all())next.add(0,new Row(j));
        caption="并发上限 "+DownloadConfig.parallel(c)+" · 保存目录："+DownloadConfig.folderLabel(c);
        rows=next;error="";loaded=true;
    }catch(Exception e){error="无法读取下载记录，请重试。";}}
    private DownloadQueueCache(){}
}
