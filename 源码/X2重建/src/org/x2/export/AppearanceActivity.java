package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.*;

public final class AppearanceActivity extends Activity {
    private final Handler main=new Handler();private TextView status;private ImageView icon,splash;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);setTitle("名称、图标与开屏");LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        status=new TextView(this);root.addView(status);
        button(root,"更改显示名称",()->{EditText input=new EditText(this);input.setSingleLine(true);input.setText(Appearance.name(this));new AlertDialog.Builder(this).setTitle("自定义桌面入口名称").setView(input).setPositiveButton("保存",(d,w)->{try{Appearance.setName(this,input.getText().toString());refresh();}catch(Exception e){message(e.getMessage());}}).setNegativeButton("取消",null).show();});
        icon=new ImageView(this);icon.setAdjustViewBounds(true);root.addView(icon,new LinearLayout.LayoutParams(-1,pad*6));button(root,"选择桌面图标",()->pick(3401));
        splash=new ImageView(this);splash.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(splash,new LinearLayout.LayoutParams(-1,pad*10));button(root,"选择应用开屏图片",()->pick(3402));
        button(root,"创建／更新桌面入口",()->Appearance.worker.execute(()->{String result;try{result=Appearance.shortcut(getApplicationContext());}catch(Exception e){result=e.getMessage();}final String text=result;main.post(()->message(text));}));
        button(root,"恢复默认外观",()->new AlertDialog.Builder(this).setTitle("恢复默认外观？").setMessage("清除自定义名称、图标和开屏封面；下载任务及 MP4 不受影响。已有桌面入口可点击创建／更新按钮恢复。 ").setPositiveButton("恢复",(d,w)->Appearance.worker.execute(()->{Appearance.reset(getApplicationContext());main.post(()->refresh());})).setNegativeButton("取消",null).show());
        TextView hint=new TextView(this);hint.setText("名称和图标用于自定义桌面快捷入口，原应用列表名称、图标保留。保存后点击创建／更新桌面入口，并按系统提示确认。\n\n开屏图片在下次冷启动时用于应用内部启动封面，初始化照常进行，不增加强制等待，可点击关闭封面。Android 系统启动画面中的图标由系统控制。图片复制到应用私有目录，更换原相册文件不影响已保存外观。 ");root.addView(hint);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);refresh();
    }
    private void button(LinearLayout root,String text,Runnable action){Button button=new Button(this);button.setText(text);root.addView(button);button.setOnClickListener(v->action.run());}
    private void pick(int request){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivityForResult(intent,request);}catch(Exception e){message("系统图片选择器不可用");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if((request==3401||request==3402)&&result==RESULT_OK&&data!=null&&data.getData()!=null){status.setText("正在保存图片…");final android.net.Uri uri=data.getData();Appearance.worker.execute(()->{String error=null;try{Appearance.importImage(getApplicationContext(),uri,request==3401);}catch(Exception|OutOfMemoryError e){error="无法保存该图片，请尝试较小的本地图片";}final String problem=error;main.post(()->{if(isDestroyed())return;refresh();if(problem!=null)message(problem);});});}}
    private void refresh(){if(isDestroyed())return;status.setText("自定义名称："+Appearance.name(this));Appearance.worker.execute(()->{final android.graphics.Bitmap a=Appearance.bitmap(getApplicationContext(),true),b=Appearance.bitmap(getApplicationContext(),false);main.post(()->{if(isDestroyed()){if(a!=null)a.recycle();if(b!=null)b.recycle();return;}icon.setImageBitmap(a);splash.setImageBitmap(b);});});}
    private void message(String text){if(!isDestroyed())new AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了",null).show();}
}
