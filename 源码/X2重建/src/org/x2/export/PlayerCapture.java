package org.x2.export;
import android.app.Activity;
import android.view.*;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
/** Read-only BetterPlayer bridge. No Dart/native offsets, entitlement flags or license calls. */
public final class PlayerCapture {
 public static final class Source {
  public final String uri,userAgent,referrer,title;public final String[] headers;
  public Source(String uri,String ua,String ref,String[] headers,String title){this.uri=uri;userAgent=ua;referrer=ref;this.headers=headers==null?new String[0]:headers.clone();this.title=title;}
 }
 private static final WeakHashMap<Object,Source> sources=new WeakHashMap<>();
 private static WeakReference<Object> active=new WeakReference<>(null);
 private static WeakReference<Activity> owner=new WeakReference<>(null);
 private static boolean playing;private static long pausedAt;
 public static synchronized void bind(Object player,Map<?,?> data){
  sources.remove(player);if(active.get()==player){active=new WeakReference<>(null);playing=false;}
  try{
   String url=value(data,"uri");Uri uri=Uri.parse(url);
   if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||url.length()>16384||!value(data,"licenseUrl").isEmpty())return;
   ArrayList<String> headers=new ArrayList<>();String ua="",ref="";
   Object input=data.get("headers");if(input instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)input).entrySet()){
    if(!(e.getKey() instanceof String)||!(e.getValue() instanceof String))continue;
    String k=(String)e.getKey(),v=(String)e.getValue();if(k.contains("\n")||k.contains("\r")||k.contains(":")||v.contains("\n")||v.contains("\r")||k.length()+v.length()>16384)return;
    headers.add(k+": "+v);if(headers.size()>100)return;if(k.equalsIgnoreCase("User-Agent"))ua=v;if(k.equalsIgnoreCase("Referer"))ref=v;
   }
   String title=value(data,"title");if(title.isEmpty())title="当前视频";
   sources.put(player,new Source(url,ua,ref,headers.toArray(new String[0]),title));
  }catch(Throwable ignored){}
 }
 private static String value(Map<?,?> d,String k){Object o=d.get(k);return o instanceof String?(String)o:"";}
 public static synchronized void markPlaying(Object player){active=new WeakReference<>(player);playing=true;pausedAt=0;}
 public static synchronized void markPaused(Object player){if(active.get()==player){playing=false;pausedAt=SystemClock.elapsedRealtime();}}
 public static synchronized void dispose(Object player){sources.remove(player);if(active.get()==player){active=new WeakReference<>(null);playing=false;}}
 public static synchronized Source snapshot()throws IOException{
  Source s=sources.get(active.get());if(s==null||(!playing&&(pausedAt==0||SystemClock.elapsedRealtime()-pausedAt>30000)))throw new IOException("请先播放当前视频；本版本不读取 DRM 授权或受保护内容");
  return new Source(s.uri,s.userAgent,s.referrer,s.headers,s.title);
 }
 public static void noteActivity(Activity a){owner=new WeakReference<>(a);}
 private static boolean rendered(View v){try{if(v.getClass().getName().equals("io.flutter.embedding.android.FlutterView"))return (Boolean)v.getClass().getMethod("hasRenderedFirstFrame").invoke(v);}catch(Exception ignored){}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(rendered(((ViewGroup)v).getChildAt(i)))return true;return false;}
 static int homeReady(){Activity a=owner.get();return a!=null&&rendered(a.findViewById(android.R.id.content))?1:0;}
 public static synchronized void clear(){sources.clear();active=new WeakReference<>(null);playing=false;}
 public static void register(long ignored){}public static void unregister(long ignored){}
 public static boolean manageRequested(boolean ignored){return false;}
 static void setLocalPermission(boolean ignored){}static void setAdSkip(boolean ignored){}
 static String testState(boolean ignored){return "bridge=BetterPlayer-read-only legacy_native_flags=absent";}
 static synchronized String playbackState(){return "players="+sources.size()+" active="+(sources.containsKey(active.get())?1:0)+" playing="+(playing?1:0);}
 static String pageDetails(String uri){return "detail_match=unavailable\n";}
 private PlayerCapture(){}
}
