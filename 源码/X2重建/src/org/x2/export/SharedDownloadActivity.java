package org.x2.export;
import android.app.*;
import android.os.Bundle;
import android.content.Intent;
/** Visible handoff for directory permission and management, guarded in manifest. */
public final class SharedDownloadActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);
  try{
   if("manage".equals(getIntent().getStringExtra("operation")))startActivity(new Intent(this,DownloadTasksActivity.class));
   else{if(!"enqueue".equals(getIntent().getStringExtra("operation"))||getIntent().getIntExtra("protocol",0)!=1)throw new IllegalArgumentException("不兼容的共享下载协议");PlayingExportActivity.open(this,SharedDownloadGateway.source(getIntent().getExtras()));}
   finish();
  }catch(Exception e){new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("知道了",(d,w)->finish()).show();}
 }
}
