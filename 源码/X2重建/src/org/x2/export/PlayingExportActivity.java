package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Only used for the first folder selection; downloads are never owned by this Activity. */
public final class PlayingExportActivity extends Activity {
    private static PlayerCapture.Source pending;
    private PlayerCapture.Source source;
    public static void open(Activity activity,PlayerCapture.Source source){
        if(!DownloadConfig.folder(activity).isEmpty()){
            try{DownloadService.enqueue(activity,source);Toast.makeText(activity,"已加入后台下载，可继续浏览视频",Toast.LENGTH_SHORT).show();}
            catch(Exception e){new AlertDialog.Builder(activity).setTitle("未能加入下载").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
            return;
        }
        synchronized(PlayingExportActivity.class){pending=source;}
        activity.startActivity(new Intent(activity,PlayingExportActivity.class));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle("首次下载设置");synchronized(PlayingExportActivity.class){source=pending;pending=null;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView text=new TextView(this);text.setText(source==null?"播放信息已失效，请返回视频页面重新点击下载。":"首次下载请选择保存文件夹，之后点击“下载”即可加入后台队列。\n"+DownloadStore.title(source.title));root.addView(text);
        Button choose=new Button(this);choose.setText("选择保存文件夹并下载");choose.setEnabled(source!=null);root.addView(choose);
        choose.setOnClickListener(v->startActivityForResult(new Intent(this,DownloadSettingsActivity.class).putExtra("choose",true),3203));setContentView(root);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==3203&&result==RESULT_OK&&source!=null&&!DownloadConfig.folder(this).isEmpty())try{
            DownloadService.enqueue(this,source);Toast.makeText(this,"已加入后台下载，可继续浏览视频",Toast.LENGTH_SHORT).show();finish();
        }catch(Exception e){new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    }
}
