package org.x2.refresh.test;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
import org.x2.export.PlayerCapture;
public class BridgeTest extends Instrumentation {
 Object plugin;Class<?> callClass,resultClass;Method method;Activity activity;
 void check(boolean ok,String s){if(!ok)throw new AssertionError(s);android.util.Log.i("X2RefreshTest","PASS "+s);}
 Object call(String name,Map<String,Object> args)throws Exception{
  Object[] answer={null};String[] error={null};CountDownLatch done=new CountDownLatch(1);
  Object result=Proxy.newProxyInstance(resultClass.getClassLoader(),new Class[]{resultClass},(obj,m,a)->{if(m.getName().equals("success")){answer[0]=a[0];done.countDown();}else if(m.getName().equals("error")||m.getName().equals("notImplemented")){error[0]=String.valueOf(a==null?null:a[0]);done.countDown();}return null;});
  Object request=callClass.getConstructor(String.class,Object.class).newInstance(name,args);
  runOnMainSync(()->{try{method.invoke(plugin,request,result);}catch(Exception e){error[0]=e.toString();done.countDown();}});
  if(!done.await(90,TimeUnit.SECONDS))throw new AssertionError("plugin callback timed out: "+name);
  if(error[0]!=null)throw new AssertionError(name+": "+error[0]);return answer[0];
 }
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle out=new Bundle();try{
  // Unplayed, preloaded and DRM sources must never be offered for downloading.
  Object p=new Object();Map<String,Object> source=new HashMap<>();source.put("uri","http://10.0.2.2:18867/Neon-motion.mp4");source.put("title","Synthetic test");Map<String,String> headers=new HashMap<>();headers.put("X-Synthetic","fixture-only");source.put("headers",headers);
  PlayerCapture.clear();PlayerCapture.bind(p,source);try{PlayerCapture.snapshot();throw new AssertionError("unplayed source exposed");}catch(java.io.IOException expected){}
  PlayerCapture.markPlaying(p);check(PlayerCapture.snapshot().uri.equals(source.get("uri")),"read-only source capture preserves URI");
  Object prefetch=new Object();Map<String,Object> other=new HashMap<>(source);other.put("uri","http://10.0.2.2:18867/not-playing.mp4");PlayerCapture.bind(prefetch,other);check(PlayerCapture.snapshot().uri.equals(source.get("uri")),"prefetch cannot replace playing source");
  Object protectedPlayer=new Object();other.put("licenseUrl","https://example.invalid/license");PlayerCapture.bind(protectedPlayer,other);PlayerCapture.markPlaying(protectedPlayer);try{PlayerCapture.snapshot();throw new AssertionError("DRM source exposed");}catch(java.io.IOException expected){}
  PlayerCapture.clear();
  activity=startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),"com.hr.xksilver.tq.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  check(!activity.isFinishing(),"supplied main activity starts without legacy wrapper");
  Method getEngine=Class.forName("io.flutter.embedding.android.FlutterActivity").getDeclaredMethod("getFlutterEngine");getEngine.setAccessible(true);Object engine=getEngine.invoke(activity);check(engine!=null,"real Flutter engine created");
  Object registry=engine.getClass().getMethod("getPlugins").invoke(engine);Class<?> pc=Class.forName("com.sarthak.better_player_enhanced.BetterPlayerPlugin");plugin=registry.getClass().getMethod("get",Class.class).invoke(registry,pc);check(plugin!=null,"original BetterPlayer plugin registered");
  callClass=Class.forName("io.flutter.plugin.common.MethodCall");resultClass=Class.forName("io.flutter.plugin.common.MethodChannel$Result");method=pc.getMethod("onMethodCall",callClass,resultClass);
  Object created=call("create",new HashMap<>());check(created instanceof Map,"original plugin creates texture player");Object texture=((Map<?,?>)created).get("textureId");
  Map<String,Object> args=new HashMap<>();args.put("textureId",texture);args.put("dataSource",source);call("setDataSource",args);
  args.remove("dataSource");call("play",args);check(PlayerCapture.snapshot().uri.equals(source.get("uri")),"actual BetterPlayer play hook selects correct source");check(Arrays.asList(PlayerCapture.snapshot().headers).contains("X-Synthetic: fixture-only"),"actual source hook preserves headers");
  SystemClock.sleep(1600);
  Field listField=pc.getDeclaredField("videoPlayers");listField.setAccessible(true);android.util.LongSparseArray<?> players=(android.util.LongSparseArray<?>)listField.get(plugin);Object player=players.get(((Number)texture).longValue());Field ef=player.getClass().getDeclaredField("exoPlayer");ef.setAccessible(true);Object exo=ef.get(player);Class<?> playerInterface=Class.forName("androidx.media3.common.Player");long[] pos={0};Object[] playbackError={null};runOnMainSync(()->{try{pos[0]=((Number)playerInterface.getMethod("getCurrentPosition").invoke(exo)).longValue();playbackError[0]=playerInterface.getMethod("getPlayerError").invoke(exo);}catch(Exception e){throw new RuntimeException(e);}});check(pos[0]>0&&playbackError[0]==null,"original ExoPlayer advances synthetic video without playback error");
  call("pause",args);check(PlayerCapture.snapshot().uri.equals(source.get("uri")),"recent paused source remains usable for download UI");call("dispose",args);try{PlayerCapture.snapshot();throw new AssertionError("disposed source retained");}catch(java.io.IOException expected){}
  View overlay=activity.findViewById(android.R.id.content).findViewWithTag("x2-download-buttons");check(overlay!=null,"download and management overlay attached");
  out.putString("result","PASS: source binding, prefetch isolation, DRM exclusion, real Flutter launch, original BetterPlayer setup/play/header/pause/dispose and download overlay");runOnMainSync(activity::finish);finish(Activity.RESULT_OK,out);
 }catch(Throwable e){android.util.Log.e("X2RefreshTest","FAIL",e);out.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,out);}}
}
