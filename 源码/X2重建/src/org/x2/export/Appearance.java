package org.x2.export;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Appearance {
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final WeakHashMap<Activity,Boolean> covers=new WeakHashMap<Activity,Boolean>();
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("x2-appearance",0);}
    static String name(Context c){return prefs(c).getString("name",c.getApplicationInfo().loadLabel(c.getPackageManager()).toString());}
    static void setName(Context c,String name)throws IOException{String value=name.trim().replace('\n',' ').replace('\r',' ');if(value.isEmpty()||value.codePointCount(0,value.length())>40)throw new IOException("请输入 1–40 个字符的名称");prefs(c).edit().putString("name",value).apply();}
    static File file(Context c,boolean icon){return new File(c.getFilesDir(),icon?"appearance-icon.png":"appearance-splash.png");}
    static void importImage(Context c,Uri uri,boolean icon)throws IOException{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取图片");BitmapFactory.decodeStream(in,null,options);}
        if(options.outWidth<=0||options.outHeight<=0)throw new IOException("所选文件不是支持的图片");
        options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1600)options.inSampleSize*=2;
        Bitmap source=null,image=null;AtomicFile destination=new AtomicFile(file(c,icon));FileOutputStream out=null;
        try{
            try(InputStream in=c.getContentResolver().openInputStream(uri)){source=BitmapFactory.decodeStream(in,null,options);}if(source==null)throw new IOException("无法解码图片");
            if(icon){image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);float scale=Math.min(256f/source.getWidth(),256f/source.getHeight());float w=source.getWidth()*scale,h=source.getHeight()*scale;canvas.drawBitmap(source,null,new RectF((256-w)/2,(256-h)/2,(256+w)/2,(256+h)/2),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));}else image=source;
            out=destination.startWrite();if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("无法保存图片");destination.finishWrite(out);out=null;
        }catch(Exception e){if(out!=null)destination.failWrite(out);throw new IOException("图片保存失败，请重新选择本地图片");}finally{if(image!=null&&image!=source)image.recycle();if(source!=null)source.recycle();}
    }
    static Bitmap bitmap(Context c,boolean icon){try(InputStream in=new AtomicFile(file(c,icon)).openRead()){return BitmapFactory.decodeStream(in);}catch(Exception|OutOfMemoryError e){return null;}}
    static void reset(Context c){prefs(c).edit().clear().apply();new AtomicFile(file(c,true)).delete();new AtomicFile(file(c,false)).delete();}
    static String shortcut(Context c)throws IOException{
        if(android.os.Build.VERSION.SDK_INT<26)throw new IOException("当前系统不支持自定义桌面入口");
        ShortcutManager manager=c.getSystemService(ShortcutManager.class);if(manager==null)throw new IOException("系统桌面服务不可用");
        Intent launch=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());if(launch==null)throw new IOException("未找到应用启动入口");launch.setAction(Intent.ACTION_MAIN);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bitmap bitmap=bitmap(c,true);Icon icon=bitmap==null?Icon.createWithResource(c,c.getApplicationInfo().icon):Icon.createWithBitmap(bitmap);
        ShortcutInfo info=new ShortcutInfo.Builder(c,"x2-custom-entry").setShortLabel(name(c)).setLongLabel(name(c)).setIntent(launch).setIcon(icon).build();
        boolean pinned=false;for(ShortcutInfo old:manager.getPinnedShortcuts())if(old.getId().equals(info.getId()))pinned=true;
        if(pinned){if(!manager.updateShortcuts(Collections.singletonList(info)))throw new IOException("桌面入口更新失败，请稍后重试");return "桌面入口已更新";}
        if(!manager.isRequestPinShortcutSupported()||!manager.requestPinShortcut(info,null))throw new IOException("当前桌面不支持添加入口，请检查桌面设置");
        return "请在系统提示中确认添加桌面入口";
    }
    private static View flutter(View v){if(v==null)return null;if(v.getClass().getName().equals("c9.r"))return v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View found=flutter(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private static boolean firstFrame(View v){View found=flutter(v);if(found==null)return false;try{return found.getClass().getField("g").getBoolean(found);}catch(Exception e){return false;}}
    static void installCover(final Activity activity){
        if(covers.containsKey(activity))return;covers.put(activity,true);
        if(!file(activity,false).isFile()&&!new File(file(activity,false).getPath()+".bak").isFile())return;
        final boolean mainActivity=activity.getClass().getName().equals("com.hr.xksilver.tq.MainActivity");
        final ViewGroup content=(ViewGroup)activity.findViewById(android.R.id.content);if(content==null||(mainActivity&&(android.os.Process.is64Bit()?PlayerCapture.homeReady()==1:firstFrame(content))))return;
        final boolean[] closed={false};
        final FrameLayout cover=new FrameLayout(activity);cover.setTag("x2-custom-cover");cover.setBackgroundColor(0xff000000);
        final ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.addView(image,new FrameLayout.LayoutParams(-1,-1));
        Button close=new Button(activity);close.setText("关闭封面");FrameLayout.LayoutParams closeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);cover.addView(close,closeParams);close.setOnClickListener(v->{closed[0]=true;if(cover.getParent() instanceof ViewGroup)((ViewGroup)cover.getParent()).removeView(cover);});
        activity.addContentView(cover,new FrameLayout.LayoutParams(-1,-1));final Handler main=new Handler(Looper.getMainLooper());
        worker.execute(()->{Bitmap bitmap=bitmap(activity.getApplicationContext(),false);main.post(()->{if(activity.isDestroyed()||activity.isFinishing()||closed[0]){if(bitmap!=null)bitmap.recycle();return;}image.setImageBitmap(bitmap);});});
        final long deadline=android.os.SystemClock.uptimeMillis()+30000;
        main.post(new Runnable(){public void run(){
            if(activity.isDestroyed()||activity.isFinishing()||closed[0]){if(cover.getParent() instanceof ViewGroup)((ViewGroup)cover.getParent()).removeView(cover);return;}
            boolean ready=mainActivity&&(android.os.Process.is64Bit()?PlayerCapture.homeReady()==1:firstFrame(content));
            if(ready||android.os.SystemClock.uptimeMillis()>deadline){closed[0]=true;if(cover.getParent() instanceof ViewGroup)((ViewGroup)cover.getParent()).removeView(cover);return;}
            if(cover.getParent()==null)activity.addContentView(cover,new FrameLayout.LayoutParams(-1,-1));main.postDelayed(this,100);
        }});

    }
    private Appearance(){}
}
