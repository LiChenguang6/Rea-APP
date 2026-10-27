package org.x2.export;
import android.content.Context;import android.os.SystemClock;
final class PlaybackDiagnostics {
 static final class SourceSample {final PlayerCapture.Source source;final String details;final long at;SourceSample(PlayerCapture.Source s){source=s;details=PlayerCapture.pageDetails(s.uri);at=SystemClock.elapsedRealtime();}}
 private static volatile SourceSample last;
 static void start(Context c){}
 static void rememberSource(){try{last=new SourceSample(PlayerCapture.snapshot());}catch(Exception ignored){}}
 static SourceSample recentSource(){SourceSample s=last;return s!=null&&SystemClock.elapsedRealtime()-s.at<30000?s:null;}
 static String report(Context c){return BuildIdentity.report(c)+"\nbridge=BetterPlayer-read-only\n"+PlayerCapture.playbackState()+"\nlegacy native patches=absent\naccount and video entitlements=unchanged\nNo URLs or credentials included.";}
}
