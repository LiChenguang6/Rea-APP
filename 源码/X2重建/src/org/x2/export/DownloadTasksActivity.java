package org.x2.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.HashSet;

public final class DownloadTasksActivity extends Activity {
    private final Handler main=new Handler();
    private final HashSet<String> selected=new HashSet<String>();
    private final ArrayList<DownloadQueueCache.Row> visible=new ArrayList<DownloadQueueCache.Row>();
    private ListView list;private GridView completedGrid;private TextView summary,empty;private Button ongoing,completed,multi;private LinearLayout batch;
    private boolean done,selecting,live,loading,recoverOnLoad;private String stamp="";
    private final BaseAdapter adapter=new BaseAdapter(){
        public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){
            if(done){VideoPreviewCard card=old instanceof VideoPreviewCard?(VideoPreviewCard)old:new VideoPreviewCard(DownloadTasksActivity.this);card.bind(visible.get(p),selecting,selected.contains(visible.get(p).id));return card;}
            CheckedTextView text=old instanceof CheckedTextView?(CheckedTextView)old:new CheckedTextView(DownloadTasksActivity.this);
            DownloadQueueCache.Row r=visible.get(p);int pad=(int)(12*getResources().getDisplayMetrics().density);
            text.setPadding(pad,pad,pad,pad);text.setText(r.title+"\n"+r.stage+(r.state.equals("running")?" · "+r.progress+"%":""));
            text.setCheckMarkDrawable(selecting?(selected.contains(r.id)?android.R.drawable.checkbox_on_background:android.R.drawable.checkbox_off_background):0);text.setChecked(selected.contains(r.id));
            text.setContentDescription(r.title+"，"+r.stage+(selecting?(selected.contains(r.id)?"，已选择":"，未选择"):""));return text;
        }
    };
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("下载管理");
        if(state!=null){done=state.getBoolean("done");selecting=state.getBoolean("selecting");ArrayList<String> ids=state.getStringArrayList("selected");if(ids!=null)selected.addAll(ids);}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=(int)(8*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        LinearLayout settings=new LinearLayout(this);button(settings,"下载设置",()->startActivity(new Intent(this,DownloadSettingsActivity.class)));button(settings,"视频预览",()->startActivity(new Intent(this,VideoPreviewActivity.class)));root.addView(settings);
        LinearLayout tabs=new LinearLayout(this);ongoing=button(tabs,"正在下载",()->switchTab(false));completed=button(tabs,"下载完成",()->switchTab(true));root.addView(tabs);
        summary=new TextView(this);root.addView(summary);
        LinearLayout modes=new LinearLayout(this);multi=button(modes,"多选",()->{selecting=!selecting;selected.clear();render(true);});button(modes,"全选",()->{selecting=true;for(DownloadQueueCache.Row r:visible)selected.add(r.id);render(true);});root.addView(modes);
        batch=new LinearLayout(this);button(batch,"暂停",()->sendBatch("pause"));button(batch,"继续",()->sendBatch("resume"));button(batch,"取消",()->sendBatch("cancel"));button(batch,"删除",()->sendBatch("remove"));root.addView(batch);
        FrameLayout body=new FrameLayout(this);list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(0xffeeeeee));list.setDividerHeight(dp(8));list.setAdapter(adapter);body.addView(list);completedGrid=new GridView(this);completedGrid.setHorizontalSpacing(dp(4));completedGrid.setVerticalSpacing(dp(4));completedGrid.setAdapter(adapter);body.addView(completedGrid);empty=new TextView(this);empty.setGravity(android.view.Gravity.CENTER);body.addView(empty,new FrameLayout.LayoutParams(-1,-1));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        list.setOnItemClickListener((p,v,pos,id)->{DownloadQueueCache.Row r=visible.get(pos);if(selecting){if(!selected.add(r.id))selected.remove(r.id);render(true);}else if(done)play(r);else actions(r);});
        list.setOnItemLongClickListener((p,v,pos,id)->{if(!selecting)actions(visible.get(pos));return true;});
        completedGrid.setOnItemClickListener((p,v,pos,id)->{DownloadQueueCache.Row r=visible.get(pos);if(selecting){if(!selected.add(r.id))selected.remove(r.id);render(true);}else play(r);});
        completedGrid.setOnItemLongClickListener((p,v,pos,id)->{if(!selecting)actions(visible.get(pos));return true;});
        DownloadQueueCache.prewarm(this);render(true);
    }
    private Button button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(12);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());return b;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("done",done);state.putBoolean("selecting",selecting);state.putStringArrayList("selected",new ArrayList<String>(selected));}
    @Override public void onResume(){super.onResume();live=true;recoverOnLoad=true;render(true);load();}
    @Override public void onPause(){live=false;main.removeCallbacks(tick);super.onPause();}
    private final Runnable tick=()->load();
    private void load(){if(loading||!live)return;loading=true;final android.content.Context app=getApplicationContext();DownloadQueueCache.worker.execute(()->{
        DownloadQueueCache.read(app);main.post(()->{loading=false;if(isDestroyed())return;render(false);if(live&&recoverOnLoad){recoverOnLoad=false;for(DownloadQueueCache.Row r:DownloadQueueCache.rows)if(r.state.equals("queued")||r.state.equals("running")||r.state.equals("cancelling")){try{DownloadService.start(this);}catch(Exception ignored){}break;}}if(live)main.postDelayed(tick,750);});
    });}
    private void switchTab(boolean value){done=value;selected.clear();render(true);list.setSelection(0);completedGrid.setSelection(0);}
    private void render(boolean force){
        ArrayList<DownloadQueueCache.Row> rows=DownloadQueueCache.rows;ArrayList<DownloadQueueCache.Row> next=new ArrayList<DownloadQueueCache.Row>();int finished=0;
        StringBuilder key=new StringBuilder();HashSet<String> valid=new HashSet<String>();
        for(DownloadQueueCache.Row r:rows){boolean success=r.state.equals("success")||r.state.equals("deleting");if(success)finished++;if(success==done){next.add(r);valid.add(r.id);key.append(r.id).append(r.state).append(r.stage).append(r.progress);}}
        selected.retainAll(valid);ongoing.setText("正在下载 ("+(rows.size()-finished)+")");completed.setText("下载完成 ("+finished+")");ongoing.setEnabled(done);completed.setEnabled(!done);
        multi.setText(selecting?"退出多选（"+selected.size()+"）":"多选");batch.setVisibility(selecting?View.VISIBLE:View.GONE);
        for(int i=0;i<batch.getChildCount();i++)batch.getChildAt(i).setEnabled(!selected.isEmpty()&&(!done||i==3));
        summary.setText(DownloadQueueCache.error.isEmpty()?DownloadQueueCache.caption:DownloadQueueCache.error);
        empty.setText(!DownloadQueueCache.loaded?"正在读取下载记录…":done?"暂无已完成视频":"暂无正在下载的任务（含暂停、失败和已取消）");
        completedGrid.setNumColumns(DownloadConfig.columns(this));
        if(force||!key.toString().equals(stamp)){AbsListView active=done?completedGrid:list;int first=Math.max(0,active.getFirstVisiblePosition());View child=active.getChildAt(0);int top=child==null?0:child.getTop();visible.clear();visible.addAll(next);stamp=key.toString();adapter.notifyDataSetChanged();if(done)completedGrid.setSelection(first);else list.setSelectionFromTop(first,top);}
        list.setVisibility(!done&&!visible.isEmpty()?View.VISIBLE:View.GONE);completedGrid.setVisibility(done&&!visible.isEmpty()?View.VISIBLE:View.GONE);empty.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);
    }
    private void play(DownloadQueueCache.Row r){LocalPlaybackActivity.open(this,r.id);}
    private void actions(DownloadQueueCache.Row r){if(r.state.equals("deleting")){android.widget.Toast.makeText(this,"正在删除文件，请稍候",0).show();return;}ArrayList<String> labels=new ArrayList<String>(),commands=new ArrayList<String>();
        if(r.state.equals("paused")){labels.add("继续下载");commands.add("resume");}else if(r.state.equals("running")||r.state.equals("queued")){labels.add("暂停下载");commands.add("pause");}
        if(r.state.equals("running")||r.state.equals("queued")||r.state.equals("paused")){labels.add("取消下载");commands.add("cancel");}else if(!r.state.equals("success")){labels.add("重新下载");commands.add("retry");}
        labels.add(r.state.equals("success")?"仅删除记录（保留 MP4）":"删除任务记录");commands.add("remove");if(r.state.equals("success")){labels.add("删除记录及 MP4 文件");commands.add("remove_file");}new AlertDialog.Builder(this).setTitle(r.title).setItems(labels.toArray(new String[0]),(d,w)->{ArrayList<String> ids=new ArrayList<String>();ids.add(r.id);send(commands.get(w),ids);}).show();
    }
    private void sendBatch(String command){final ArrayList<String> ids=new ArrayList<String>(selected);if(ids.isEmpty())return;
        if(command.equals("remove")&&done)new AlertDialog.Builder(this).setTitle("删除方式").setItems(new String[]{"仅删除记录（保留 MP4）","删除记录及 MP4 文件"},(d,w)->send(w==0?"remove":"remove_file",ids)).show();else send(command,ids);
    }
    private void send(String command,ArrayList<String> ids){if(ids.isEmpty())return;
        if(command.equals("remove")||command.equals("remove_file"))new AlertDialog.Builder(this).setTitle("删除 "+ids.size()+" 个任务？").setMessage(command.equals("remove_file")?"将永久删除所选 MP4 文件及记录，无法在应用内撤销。文件删除失败时保留记录并显示原因。":"未完成任务将取消并清理；已经保存的 MP4 文件保留。").setPositiveButton("删除",(d,w)->dispatch(command,ids)).setNegativeButton("保留",null).show();else dispatch(command,ids);
    }
    private void dispatch(String command,ArrayList<String> ids){try{DownloadService.commandBatch(this,command,ids);selected.clear();render(true);}catch(Exception e){new AlertDialog.Builder(this).setMessage("无法启动后台任务，请在前台重试。").setPositiveButton("知道了",null).show();}}
}
