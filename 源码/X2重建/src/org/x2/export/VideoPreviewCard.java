package org.x2.export;
import android.view.View;
import android.widget.*;
final class VideoPreviewCard extends LinearLayout {
        final FrameLayout frame;final ImageView cover;final TextView duration,title,status,check;
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
        VideoPreviewCard(android.content.Context context){super(context);setOrientation(VERTICAL);setPadding(dp(6),dp(8),dp(6),dp(12));setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            frame=new FrameLayout(getContext()){@Override protected void onMeasure(int width,int height){int size=View.MeasureSpec.getSize(width);super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(Math.max(1,size*9/16),View.MeasureSpec.EXACTLY));}};frame.setBackgroundColor(0xff202124);addView(frame,new LinearLayout.LayoutParams(-1,-2));
            cover=new ImageView(getContext());cover.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            TextView play=new TextView(getContext());play.setText("▶");play.setTextSize(34);play.setTextColor(0xffffffff);play.setGravity(android.view.Gravity.CENTER);play.setShadowLayer(dp(3),0,0,0xff000000);frame.addView(play,new FrameLayout.LayoutParams(-1,-1));
            duration=new TextView(getContext());duration.setTextColor(0xffffffff);duration.setTextSize(12);duration.setBackgroundColor(0xcc000000);duration.setPadding(dp(5),dp(2),dp(5),dp(2));FrameLayout.LayoutParams time=new FrameLayout.LayoutParams(-2,-2,android.view.Gravity.BOTTOM|android.view.Gravity.RIGHT);time.setMargins(dp(6),dp(6),dp(6),dp(6));frame.addView(duration,time);
            check=new TextView(getContext());check.setTextColor(0xffffffff);check.setTextSize(17);check.setBackgroundColor(0xcc245bb0);check.setPadding(dp(6),dp(3),dp(6),dp(3));FrameLayout.LayoutParams choice=new FrameLayout.LayoutParams(-2,-2,android.view.Gravity.TOP|android.view.Gravity.RIGHT);choice.setMargins(dp(6),dp(6),dp(6),dp(6));frame.addView(check,choice);
            title=new TextView(getContext());title.setTextSize(16);title.setTextColor(0xff202124);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(2),dp(8),dp(2),dp(3));addView(title);
            status=new TextView(getContext());status.setTextSize(12);status.setTextColor(0xff60646b);status.setPadding(dp(2),0,dp(2),dp(4));addView(status);
            for(View child:new View[]{frame,cover,play,duration,check,title,status}){child.setClickable(false);child.setLongClickable(false);child.setFocusable(false);}
        }
        void bind(DownloadQueueCache.Row row,boolean selecting,boolean selected){
            title.setText(row.title);status.setText(row.stage);check.setVisibility(selecting?View.VISIBLE:View.GONE);check.setText(selected?"☑ 已选":"☐");setBackgroundColor(selecting&&selected?0xffe9f0ff:0xffffffff);
            setContentDescription(row.title+"，"+row.stage+(selecting?(selected?"，已选择":"，未选择"):"，点击播放"));
            boolean images=DownloadConfig.previews(getContext());frame.setVisibility(images?View.VISIBLE:View.GONE);
            title.setTextSize(DownloadConfig.columns(getContext())>1?14:16);
            if(images)VideoThumbnails.bind(getContext(),row.id,row.document,cover,duration);
            else{cover.setTag(null);duration.setTag(null);cover.setImageDrawable(null);duration.setText("");}
        }
    }
