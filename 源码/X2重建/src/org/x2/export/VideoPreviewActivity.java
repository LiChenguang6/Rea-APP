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

/** Local completed-video gallery. No remote video requests or task controls. */
public final class VideoPreviewActivity extends Activity {
    private final Handler main=new Handler();
    private final ArrayList<DownloadQueueCache.Row> rows=new ArrayList<DownloadQueueCache.Row>();
    private GridView grid;private TextView empty;private boolean live,loading;private String stamp="";
    private final BaseAdapter adapter=new BaseAdapter(){
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){VideoPreviewCard card=old instanceof VideoPreviewCard?(VideoPreviewCard)old:new VideoPreviewCard(VideoPreviewActivity.this);card.bind(rows.get(p),false,false);card.status.setVisibility(View.GONE);return card;}
    };
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("视频预览");
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xffffffff);grid=new GridView(this);grid.setHorizontalSpacing(4);grid.setVerticalSpacing(4);grid.setAdapter(adapter);root.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        empty=new TextView(this);empty.setGravity(android.view.Gravity.CENTER);root.addView(empty,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        grid.setOnItemClickListener((parent,view,pos,id)->play(rows.get(pos)));
        DownloadQueueCache.prewarm(this);
    }
    @Override public void onResume(){super.onResume();live=true;render(true);load();}
    @Override public void onPause(){live=false;main.removeCallbacks(tick);super.onPause();}
    private final Runnable tick=()->load();
    private void load(){if(!live||loading)return;loading=true;final android.content.Context app=getApplicationContext();DownloadQueueCache.worker.execute(()->{DownloadQueueCache.read(app);main.post(()->{loading=false;if(isDestroyed())return;render(false);if(live)main.postDelayed(tick,1000);});});}
    private void render(boolean force){ArrayList<DownloadQueueCache.Row> next=new ArrayList<DownloadQueueCache.Row>();StringBuilder key=new StringBuilder();for(DownloadQueueCache.Row r:DownloadQueueCache.rows)if(r.state.equals("success")){next.add(r);key.append(r.id).append(r.title).append(r.document);}
        grid.setNumColumns(DownloadConfig.columns(this));if(force||!key.toString().equals(stamp)){int first=Math.max(0,grid.getFirstVisiblePosition());View child=grid.getChildAt(0);int top=child==null?0:child.getTop();rows.clear();rows.addAll(next);stamp=key.toString();adapter.notifyDataSetChanged();grid.setSelection(first);}
        empty.setText(!DownloadQueueCache.loaded?"正在读取本地视频…":DownloadQueueCache.error.isEmpty()?"暂无下载完成的视频，返回后可继续下载":DownloadQueueCache.error);empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);grid.setVisibility(rows.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void play(DownloadQueueCache.Row row){LocalPlaybackActivity.open(this,row.id);}
}
