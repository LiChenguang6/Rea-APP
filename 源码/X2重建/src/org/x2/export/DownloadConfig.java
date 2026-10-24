package org.x2.export;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.IOException;

public final class DownloadConfig {
    public static final int MAX_PARALLEL=32;
    public static boolean previews(Context c){return prefs(c).getBoolean("previews",true);}
    public static void setPreviews(Context c,boolean enabled){prefs(c).edit().putBoolean("previews",enabled).apply();}
    public static int columns(Context c){return Math.max(1,Math.min(4,prefs(c).getInt("preview_columns",1)));}
    public static void setColumns(Context c,int n){prefs(c).edit().putInt("preview_columns",Math.max(1,Math.min(4,n))).apply();}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("x2-download-config", 0); }
    public static String folder(Context c) { return prefs(c).getString("folder", ""); }
    public static int parallel(Context c) { return Math.max(1, Math.min(MAX_PARALLEL, prefs(c).getInt("parallel", 3))); }
    public static void setParallel(Context c, int n) { prefs(c).edit().putInt("parallel", Math.max(1, Math.min(MAX_PARALLEL, n))).apply(); }
    public static void setFolder(Context c, Uri uri, int flags) throws IOException {
        int grant = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((grant & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0 || !DocumentsContract.isTreeUri(uri))
            throw new IOException("请选择可写入的保存文件夹。");
        try {
            c.getContentResolver().takePersistableUriPermission(uri, grant);
            if (!prefs(c).edit().putString("folder", uri.toString()).putString("folder_label", readLabel(c,uri)).commit()) throw new IOException("无法保存下载目录配置。");
        } catch (SecurityException e) { throw new IOException("无法记住该目录的访问权限，请重新选择其他文件夹。"); }
    }
    public static String folderLabel(Context c) {
        String folder = folder(c);
        if (folder.isEmpty()) return "尚未选择保存文件夹";
        String label=prefs(c).getString("folder_label", "");
        if(!label.isEmpty())return label;
        label=readLabel(c,Uri.parse(folder));prefs(c).edit().putString("folder_label",label).apply();return label;
    }
    private static String readLabel(Context c,Uri tree) {
        try {
            Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            try(Cursor cursor=c.getContentResolver().query(doc,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if(cursor!=null&&cursor.moveToFirst()){String name=cursor.getString(0);if(name!=null&&!name.isEmpty())return name;}
            }
        }catch(Exception ignored) { }
        return "已配置保存文件夹";
    }
    private DownloadConfig() { }
}
