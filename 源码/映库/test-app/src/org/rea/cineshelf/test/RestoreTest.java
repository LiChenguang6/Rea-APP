package org.rea.cineshelf.test;
import android.app.*;
import android.os.*;
import org.rea.cineshelf.*;
public class RestoreTest extends Instrumentation {
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle out=new Bundle();try{
  Catalog lib=Catalog.get(getTargetContext());
  if(lib.videos.size()!=3||lib.groups.size()!=3)throw new AssertionError("index not restored after process termination");
  Catalog.Video v=null;for(Catalog.Video item:lib.videos)if(item.name.equals("Neon-motion.mp4"))v=item;
  if(v==null||v.hash.isEmpty()||lib.position(v)<5000)throw new AssertionError("content identity or progress not restored");
  if(lib.roots().size()!=1||lib.find(v.token())==null)throw new AssertionError("folder or virtual entry not restored");
  out.putString("result","PASS: fresh process restored library, source folder, full-hash identity, virtual entry and saved playback position="+lib.position(v));finish(Activity.RESULT_OK,out);
 }catch(Throwable e){out.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,out);}}
}
