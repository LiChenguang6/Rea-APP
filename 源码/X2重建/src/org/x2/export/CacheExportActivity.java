package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public final class CacheExportActivity extends Activity {
    private final ArrayList<File> videos=new ArrayList<File>();private ListView list;private TextView status;private File selected;
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("已有缓存导出");LinearLayout root=new LinearLayout(this);root.setOrientation(1);
        int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView intro=new TextView(this);intro.setText("点选已完成的缓存，加入后台导出队列。保存到下载设置中的文件夹，可在管理页查看或取消任务。");root.addView(intro);
        Button refresh=new Button(this);refresh.setText("刷新已下载视频");root.addView(refresh);refresh.setOnClickListener(v->scan());status=new TextView(this);root.addView(status);
        list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setOnItemClickListener((p,v,pos,id)->choose(videos.get(pos)));setContentView(root);scan();}
    private void scan(){videos.clear();File base=new File(getDir("flutter",0),"downloads/video");File[] dirs=base.listFiles();
        if(dirs!=null){Arrays.sort(dirs,Comparator.comparing(File::getName));for(File d:dirs)if(d.isDirectory()&&new File(d,"index.m3u8").isFile())try{if(d.getCanonicalFile().getParentFile().equals(base.getCanonicalFile()))videos.add(d);}catch(Exception ignored){}}
        ArrayList<String> rows=new ArrayList<String>();for(File d:videos)rows.add("视频 "+d.getName()+" · 点击加入后台导出");list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows));status.setText(videos.isEmpty()?"尚无完成的缓存。":"找到 "+videos.size()+" 项缓存");}
    private void choose(File video){selected=video;if(DownloadConfig.folder(this).isEmpty())startActivityForResult(new Intent(this,DownloadSettingsActivity.class).putExtra("choose",true),3204);else enqueue();}
    private void enqueue(){try{DownloadService.enqueueCache(this,selected);Toast.makeText(this,"已加入后台导出队列",Toast.LENGTH_SHORT).show();}catch(Exception e){new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==3204&&result==RESULT_OK&&selected!=null&&!DownloadConfig.folder(this).isEmpty())enqueue();}
}
