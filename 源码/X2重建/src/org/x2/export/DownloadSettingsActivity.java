package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.text.InputType;

public final class DownloadSettingsActivity extends Activity {
    private TextView folder;
    private static final int PICK=3202;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);PlaybackDiagnostics.start(this);setTitle("下载设置");
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        folder=new TextView(this);root.addView(folder);
        Button choose=new Button(this);choose.setText("更改下载文件夹");root.addView(choose);
        choose.setOnClickListener(v->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);try{startActivityForResult(pick,PICK);}catch(Exception e){error("无法打开系统文件夹选择窗口。");}});
        final Button parallel=new Button(this);parallel.setText("同时下载数量："+DownloadConfig.parallel(this));root.addView(parallel);
        parallel.setOnClickListener(v->{
            final EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setSingleLine(true);input.setText(String.valueOf(DownloadConfig.parallel(this)));input.selectAll();
            final AlertDialog dialog=new AlertDialog.Builder(this).setTitle("同时下载数量（1–"+DownloadConfig.MAX_PARALLEL+"）")
                    .setMessage("输入希望同时执行的任务数。数量越大，占用的网络、内存和存储越多。")
                    .setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
            dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                int count;try{count=Integer.parseInt(input.getText().toString().trim());}catch(NumberFormatException e){input.setError("请输入整数");return;}
                if(count<1||count>DownloadConfig.MAX_PARALLEL){input.setError("请输入 1–"+DownloadConfig.MAX_PARALLEL+" 之间的整数");return;}
                DownloadConfig.setParallel(this,count);parallel.setText("同时下载数量："+count);
                try{for(DownloadStore.Job job:DownloadStore.get(this).all())if(job.active()){DownloadService.start(this);break;}}catch(Exception e){error(e.getMessage());}
                dialog.dismiss();
            }));dialog.show();
        });
        android.widget.Switch previews=new android.widget.Switch(this);previews.setText("显示视频预览图");previews.setChecked(DownloadConfig.previews(this));previews.setOnCheckedChangeListener((button,checked)->DownloadConfig.setPreviews(this,checked));root.addView(previews);
        Button columns=new Button(this);columns.setText("预览每行数量："+DownloadConfig.columns(this));root.addView(columns);
        columns.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("预览每行数量").setSingleChoiceItems(new String[]{"1 个","2 个","3 个","4 个"},DownloadConfig.columns(this)-1,(dialog,which)->{DownloadConfig.setColumns(this,which+1);columns.setText("预览每行数量："+(which+1));dialog.dismiss();}).setNegativeButton("取消",null).show());
        Button cache=new Button(this);cache.setText("导出已有缓存");root.addView(cache);cache.setOnClickListener(v->startActivity(new Intent(this,CacheExportActivity.class)));
        TextView hint=new TextView(this);hint.setText("保存目录只需选择一次。更改目录用于新加入的任务，已有任务继续使用原目录。并发数量减少时，不中断正在执行的任务。\n\n下载由通知栏中的后台服务执行，可继续浏览、返回桌面或锁屏。部分手机还需在系统设置允许后台运行。强行停止应用后，需要重新打开管理页面恢复任务。");root.addView(hint);
        Button battery=new Button(this);battery.setText("允许持续后台下载");root.addView(battery);
        battery.setOnClickListener(v->{try{
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if(!power.isIgnoringBatteryOptimizations(getPackageName()))startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));
            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        }catch(Exception e){error("请在系统应用设置中允许此应用后台运行。");}});
        Button importLocal=new Button(this);importLocal.setText("导入此目录已有视频（保留旧文件）");root.addView(importLocal);
        importLocal.setOnClickListener(v->{importLocal.setEnabled(false);new Thread(()->{String message;try{int count=LocalLibrary.scan(getApplicationContext());message="已导入 "+count+" 个本地视频；已有记录不会重复添加。旧文件不包含原源指纹，无法预先按原源去重。";}catch(Exception e){message="导入失败："+e.getMessage();}final String text=message;runOnUiThread(()->{if(!isDestroyed()){importLocal.setEnabled(true);error(text);}});},"shared-library-import").start();});
        Button appearance=new Button(this);appearance.setText("名称、图标与开屏");root.addView(appearance);appearance.setOnClickListener(v->startActivity(new Intent(this,AppearanceActivity.class)));
        Button diagnose=new Button(this);diagnose.setText("播放诊断（复制）");root.addView(diagnose);
        diagnose.setOnClickListener(v->{final String report=PlaybackDiagnostics.report(this);new AlertDialog.Builder(this).setTitle("播放诊断").setMessage(report).setPositiveButton("复制",(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("播放诊断",report));android.widget.Toast.makeText(this,"已复制诊断，不含播放地址或账号凭据",android.widget.Toast.LENGTH_SHORT).show();}).setNegativeButton("关闭",null).show();});
        Button sourceProbe=new Button(this);sourceProbe.setText("检查当前播放源（复制）");root.addView(sourceProbe);sourceProbe.setOnClickListener(v->SourceProbe.start(this,sourceProbe));
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(root);setContentView(scroll);refresh();
        if(getIntent().getBooleanExtra("choose",false)&&DownloadConfig.folder(this).isEmpty())choose.performClick();
    }
    private void refresh(){folder.setText("保存文件夹：\n"+DownloadConfig.folderLabel(this)+"\n");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==PICK&&result==RESULT_OK&&data!=null&&data.getData()!=null)try{
            DownloadConfig.setFolder(this,data.getData(),data.getFlags());refresh();setResult(RESULT_OK);
            if(getIntent().getBooleanExtra("choose",false))finish();
        }catch(Exception e){error(e.getMessage());}
    }
    private void error(String text){new AlertDialog.Builder(this).setTitle("下载设置").setMessage(text).setPositiveButton("知道了",null).show();}
}
