package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExportEntry {
    private static final java.util.WeakHashMap<Activity,Boolean> navigationOwners=new java.util.WeakHashMap<Activity,Boolean>();
    public static void install(final Activity activity) {
        DownloadQueueCache.prewarm(activity);
        PlayerCapture.noteActivity(activity);
        Appearance.installCover(activity);
        ViewGroup content=(ViewGroup)activity.findViewById(android.R.id.content);
        if(content==null||content.findViewWithTag("x2-download-buttons")!=null)return;
        View underlying=content.getChildCount()==0?null:content.getChildAt(0);
        GestureRow row=new GestureRow(activity,underlying);row.setTag("x2-download-buttons");
        final Button download=new Button(activity);download.setText("下载");
        final Button manage=new Button(activity);manage.setText("管理");
        manage.setOnClickListener(v->openWhenReady(activity,DownloadTasksActivity.class));
        for(Button b:new Button[]{download,manage}) {b.setTextSize(12);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xDD395A85));row.addView(b,new LinearLayout.LayoutParams(0,-1,1));}
        float density=activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(Math.round(152*density),Math.round(44*density),Gravity.RIGHT|Gravity.TOP);
        params.rightMargin=Math.round(8*density);params.topMargin=Math.round(80*density);activity.addContentView(row,params);
        download.setOnLongClickListener(v->{
            new AlertDialog.Builder(activity).setTitle("下载功能").setItems(new String[]{"下载管理","下载设置"},(dialog,which)->{
                openWhenReady(activity,which==0?DownloadTasksActivity.class:DownloadSettingsActivity.class);
            }).show();return true;
        });
        download.setOnClickListener(v->{
            download.setEnabled(false);download.setText("读取中");final AtomicBoolean done=new AtomicBoolean();
            final Handler main=new Handler(Looper.getMainLooper());
            final Runnable timeout=()->{if(done.compareAndSet(false,true)){download.setEnabled(true);download.setText("下载");if(!activity.isFinishing())message(activity,"读取视频超时，请在当前视频播放时重试。");}};
            main.postDelayed(timeout,15000);
            new Thread(()->{
                PlayerCapture.Source source=null;String failure=null;
                try {source=PlayerCapture.snapshot();}catch(Exception e){failure=e.getMessage();}
                final PlayerCapture.Source captured=source;final String error=failure;
                main.post(()->{
                    if(!done.compareAndSet(false,true))return;main.removeCallbacks(timeout);download.setEnabled(true);download.setText("下载");
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    if(captured==null){message(activity,error==null?"请先播放视频再点击下载。":error);return;}
                    if(isLocalCache(captured.uri))activity.startActivity(new Intent(activity,CacheExportActivity.class));
                    else PlayingExportActivity.open(activity,captured);
                });
            },"X2-read-player").start();
        });
    }
    private static boolean flutterStarting(View view){
        if(view==null)return false;
        if(view.getClass().getName().equals("c9.r")){
            try{return !view.getClass().getField("g").getBoolean(view);}catch(Exception ignored){return false;}
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(flutterStarting(group.getChildAt(i)))return true;}
        return false;
    }
    private static void openWhenReady(final Activity activity,final Class<?> target){
        if(!activity.isDestroyed()&&!activity.isFinishing())activity.startActivity(new Intent(activity,target));
    }

    private static void ensureNavigation(final Activity activity){
        if(navigationOwners.containsKey(activity))return;
        navigationOwners.put(activity,Boolean.TRUE);
        final Handler navigation=new Handler(Looper.getMainLooper());
        navigation.post(new Runnable(){public void run(){
            if(activity.isDestroyed()||activity.isFinishing()){navigationOwners.remove(activity);return;}
            PlayerCapture.testState(false);
            // The event belongs to the Activity, not to a transient or unattached overlay view.
            if(activity.hasWindowFocus()&&!flutterStarting(activity.findViewById(android.R.id.content))&&PlayerCapture.manageRequested(false))
                openWhenReady(activity,DownloadTasksActivity.class);
            navigation.postDelayed(this,250);
        }});
    }
    private static void message(Activity activity,String text){new AlertDialog.Builder(activity).setTitle("下载").setMessage(text).setPositiveButton("知道了",null).show();}
    private static boolean isLocalCache(String value){try{java.net.URI uri=new java.net.URI(value);String host=uri.getHost();return ("localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"[::1]".equals(host))&&uri.getPath()!=null&&uri.getPath().matches("/[0-9]+/index\\.m3u8");}catch(Exception e){return false;}}

    /** Dragging moves both buttons together; a tap still invokes the selected action. */
    public static final class GestureRow extends LinearLayout {
        private final android.content.SharedPreferences position;private final int slop;
        private boolean dragging;private float downX,downY,startX,startY;
        GestureRow(Activity activity,View ignored){super(activity);position=activity.getSharedPreferences("x2-overlay-position",0);
            slop=ViewConfiguration.get(activity).getScaledTouchSlop();setOrientation(HORIZONTAL);setMotionEventSplittingEnabled(false);
            setContentDescription("下载与管理，按住可拖动位置");
            addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(!dragging)restore();});
        }
        private float maxX(){return getParent() instanceof View?Math.max(0,((View)getParent()).getWidth()-getWidth()):0;}
        private float maxY(){return getParent() instanceof View?Math.max(0,((View)getParent()).getHeight()-getHeight()):0;}
        private static float clamp(float value,float max){return Math.max(0,Math.min(value,max));}
        private void restore(){
            if(position.contains("x")){setX(clamp(position.getFloat("x",1)*maxX(),maxX()));setY(clamp(position.getFloat("y",0)*maxY(),maxY()));}
            else {setX(clamp(getX(),maxX()));setY(clamp(getY(),maxY()));}
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){dragging=false;downX=event.getRawX();downY=event.getRawY();startX=getX();startY=getY();}
            float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
            if(action==MotionEvent.ACTION_MOVE&&!dragging&&Math.hypot(dx,dy)>slop){
                dragging=true;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
            }
            if(dragging){
                setX(clamp(startX+dx,maxX()));setY(clamp(startY+dy,maxY()));
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                    position.edit().putFloat("x",maxX()>0?getX()/maxX():0).putFloat("y",maxY()>0?getY()/maxY():0).apply();
                    dragging=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(()->restore());}
    }
    private ExportEntry() { }
}
