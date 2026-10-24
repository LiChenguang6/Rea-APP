package org.x2.export;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One process-wide repository. Signed playback URLs are encrypted at rest. */
public final class DownloadStore {
    private static DownloadStore instance;
    private final AtomicFile file;
    private final ArrayList<Job> jobs = new ArrayList<Job>();
    public static final class Job {
        public final String id, title, folder, cache;
        public volatile PlayerCapture.Source source;
        public volatile String sourceKey="",videoKey="";
        public volatile String state = "queued", stage = "等待下载", document = "";
        public volatile int progress;
        public volatile boolean cancelled, remove, paused, restartNeeded, resumeRequested;
        public volatile String owner="";
        Job(String id, String title, String folder, String cache, PlayerCapture.Source source) {
            this.id=id; this.title=title; this.folder=folder; this.cache=cache; this.source=source;
            this.sourceKey=source==null?"":VideoIdentity.source(source.uri);
        }
        public boolean active() { return state.equals("running") || state.equals("queued") || state.equals("cancelling") || state.equals("paused"); }
    }
    public static synchronized DownloadStore get(Context context) throws IOException {
        if (instance == null) instance = new DownloadStore(context.getApplicationContext());
        return instance;
    }
    private DownloadStore(Context context) throws IOException {
        file = new AtomicFile(new File(context.getFilesDir(), "download-queue.enc"));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists()) return;
        try (FileInputStream input=file.openRead()) {
            ByteArrayOutputStream data=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
            while ((n=input.read(buffer))!=-1) { if (data.size()+n>8*1024*1024) throw new IOException(); data.write(buffer,0,n); }
            byte[] bytes=data.toByteArray();
            if (bytes.length<30 || bytes[0]!=1 || bytes[1]!=12) throw new IOException();
            byte[] iv=new byte[12]; System.arraycopy(bytes,2,iv,0,12);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            JSONArray array=new JSONArray(new String(cipher.doFinal(bytes,14,bytes.length-14),StandardCharsets.UTF_8));
            for (int i=0;i<array.length();i++) {
                JSONObject v=array.getJSONObject(i); PlayerCapture.Source source=null;
                if (v.has("uri")) {
                    JSONArray h=v.getJSONArray("headers"); String[] headers=new String[h.length()];
                    for(int j=0;j<headers.length;j++) headers[j]=h.getString(j);
                    source=new PlayerCapture.Source(v.getString("uri"),v.optString("ua"),v.optString("ref"),headers,v.getString("title"));
                }
                Job job=new Job(v.getString("id"),v.getString("title"),v.getString("folder"),v.optString("cache"),source);
                job.sourceKey=v.optString("sourceKey",job.sourceKey);job.videoKey=v.optString("videoKey");
                job.state=v.getString("state");job.paused=job.state.equals("paused");job.resumeRequested=v.optBoolean("resumeRequested"); job.stage=v.optString("stage"); job.progress=v.optInt("progress");
                job.document=v.optString("document"); job.cancelled=v.optBoolean("cancelled"); job.remove=v.optBoolean("remove");
                if(job.state.equals("deleting")){job.state="success";job.stage="上次删除可能中断，请重试删除文件";}
                jobs.add(job);
            }
        } catch(Exception e) { throw new IOException("无法读取加密下载队列，请保留应用数据后重试。"); }
    }
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias="x2-download-queue-v1";
        if(store.containsAlias(alias)) return (SecretKey)store.getKey(alias,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public synchronized ArrayList<Job> all() { return new ArrayList<Job>(jobs); }
    public synchronized Job find(String id) { for(Job j:jobs) if(j.id.equals(id)) return j; return null; }
    public synchronized void add(Job job) throws IOException {
        if(!job.sourceKey.isEmpty()||!job.videoKey.isEmpty())for(Job old:jobs){
            boolean sameSource=!job.sourceKey.isEmpty()&&job.sourceKey.equals(old.sourceKey);
            boolean sameVideo=!job.videoKey.isEmpty()&&job.videoKey.equals(old.videoKey);
            // Contradictory exact video IDs take precedence over a reused endpoint.
            if(sameSource&&!job.videoKey.isEmpty()&&!old.videoKey.isEmpty()&&!job.videoKey.equals(old.videoKey))sameSource=false;
            if(sameSource||sameVideo)throw new IOException(old.state.equals("success")?"此视频已经下载完成，请在管理页面查看；如需重新下载，请先删除原记录。":old.state.equals("deleting")?"此视频正在删除，请稍后重试。":"此视频已有下载任务（"+old.stage+"），请在管理页面继续或重试原任务。");
        }
        if(jobs.size()>=200) throw new IOException("任务记录已达 200 条，请先删除不需要的记录。");
        jobs.add(job); try { save(); } catch(IOException e) { jobs.remove(job); throw e; }
    }
    public synchronized void remove(Job job) throws IOException { jobs.remove(job); save(); }
    synchronized void removeWithoutSave(Job job) { jobs.remove(job); }
    public synchronized void save() throws IOException {
        FileOutputStream output=null;
        try {
            JSONArray array=new JSONArray();
            for(Job job:jobs) {
                JSONObject v=new JSONObject(); v.put("id",job.id);v.put("title",job.title);v.put("folder",job.folder);v.put("cache",job.cache);
                v.put("sourceKey",job.sourceKey);v.put("videoKey",job.videoKey);
                v.put("state",job.state);v.put("stage",job.stage);v.put("progress",job.progress);v.put("document",job.document);
                v.put("resumeRequested",job.resumeRequested);v.put("cancelled",job.cancelled);v.put("remove",job.remove);
                PlayerCapture.Source s=job.source;
                if(s!=null) {v.put("uri",s.uri);v.put("ua",s.userAgent);v.put("ref",s.referrer);v.put("headers",new JSONArray(s.headers));}
                array.put(v);
            }
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            byte[] plain=array.toString().getBytes(StandardCharsets.UTF_8);
            if(plain.length>8*1024*1024-128)throw new IOException();
            byte[] encrypted=cipher.doFinal(plain);
            output=file.startWrite();output.write(1);output.write(cipher.getIV().length);output.write(cipher.getIV());output.write(encrypted);
            file.finishWrite(output);
        } catch(Exception e) {if(output!=null)file.failWrite(output);throw new IOException("无法保存下载队列，请检查可用空间。");}
    }
    public synchronized boolean importLocal(String folder,String document,String title) throws IOException {
        for(Job old:jobs)if(document.equals(old.document))return false;
        Job job=new Job(UUID.randomUUID().toString(),title(title.replaceFirst("(?i)\\.mp4$","")),folder,"",null);
        job.state="success";job.stage="已导入本地视频（原视频源未知）";job.progress=100;job.document=document;
        jobs.add(job);try{save();}catch(IOException e){jobs.remove(job);throw e;}return true;
    }
    public static Job playback(Context c, PlayerCapture.Source source) throws IOException {
        String folder=DownloadConfig.folder(c);if(folder.isEmpty())throw new IOException("请先在管理页面设置保存文件夹。");
        Job job=new Job(UUID.randomUUID().toString(),title(source.title),folder,"",source);
        job.videoKey=VideoIdentity.video(PlayerCapture.pageDetails(source.uri));return job;
    }
    public static Job cache(Context c,File cache) throws IOException {
        File root=new File(c.getDir("flutter",0),"downloads/video").getCanonicalFile();
        if(!root.equals(cache.getCanonicalFile().getParentFile()))throw new IOException("缓存目录无效。");
        String folder=DownloadConfig.folder(c);if(folder.isEmpty())throw new IOException("请先设置保存文件夹。");
        return new Job(UUID.randomUUID().toString(),"缓存视频 "+cache.getName(),folder,cache.getCanonicalPath(),null);
    }
    public static String title(String value) {
        if(value==null||value.contains("://"))return "当前播放视频";
        value=value.replaceAll("[\\p{Cntrl}]"," ").trim();
        return value.isEmpty()?"当前播放视频":value.substring(0,value.offsetByCodePoints(0,Math.min(100,value.codePointCount(0,value.length()))));
    }
}
