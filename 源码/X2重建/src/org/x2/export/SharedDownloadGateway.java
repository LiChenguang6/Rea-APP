package org.x2.export;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Versioned, signature-protected contract. Queue and directory grants stay in X2. */
public final class SharedDownloadGateway extends ContentProvider {
    public static final String PERMISSION="org.x2.shared.permission.DOWNLOAD";
    public boolean onCreate(){return true;}
    static PlayerCapture.Source source(Bundle data){
        if(data==null)throw new IllegalArgumentException("缺少播放信息");
        String url=data.getString("url","");Uri uri=Uri.parse(url);
        if(url.length()>16384||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null)
            throw new IllegalArgumentException("只支持当前播放器的 HTTP(S) 视频地址");
        String[] headers=data.getStringArray("headers");
        if(headers==null)headers=new String[0];if(headers.length>100)throw new IllegalArgumentException("请求头过多");
        for(String h:headers)if(h==null||h.length()>16384||h.indexOf(':')<1||h.indexOf('\r')>=0||h.indexOf('\n')>=0)throw new IllegalArgumentException("请求头无效");
        String ua=data.getString("userAgent",""),ref=data.getString("referrer","");
        if(ua.indexOf('\n')>=0||ua.indexOf('\r')>=0||ref.indexOf('\n')>=0||ref.indexOf('\r')>=0)throw new IllegalArgumentException("请求头无效");
        return new PlayerCapture.Source(url,ua,ref,headers,data.getString("title","共享视频"));
    }
    public Bundle call(String method,String arg,Bundle extras){
        getContext().enforceCallingOrSelfPermission(PERMISSION,"同签名客户端才可访问共享下载");
        Bundle result=new Bundle();result.putInt("protocol",1);
        try{
            if("protocol".equals(method)){result.putString("status","ok");return result;}
            if("status".equals(method)){
                org.json.JSONArray jobs=new org.json.JSONArray();for(DownloadStore.Job j:DownloadStore.get(getContext()).all()){org.json.JSONObject row=new org.json.JSONObject();row.put("id",j.id);row.put("state",j.state);row.put("title",j.title);row.put("progress",j.progress);row.put("document",j.document);row.put("stage",j.stage);jobs.put(row);}
                result.putString("jobs",jobs.toString());result.putInt("parallel",DownloadConfig.parallel(getContext()));result.putInt("columns",DownloadConfig.columns(getContext()));result.putBoolean("previews",DownloadConfig.previews(getContext()));result.putString("status","ok");return result;
            }
            if(!"enqueue".equals(method))throw new IllegalArgumentException("不支持的协议操作");
            if(extras==null||extras.getInt("protocol",0)!=1)throw new IllegalArgumentException("不兼容的共享下载协议");
            PlayerCapture.Source source=source(extras);
            if(DownloadConfig.folder(getContext()).isEmpty()){result.putString("status","need_folder");return result;}
            result.putString("jobId",DownloadService.enqueue(getContext(),source));result.putString("status","ok");
        }catch(Exception e){result.putString("status","error");result.putString("message",e.getMessage()==null?"无法提交下载":e.getMessage());}
        return result;
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    public String getType(Uri u){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
