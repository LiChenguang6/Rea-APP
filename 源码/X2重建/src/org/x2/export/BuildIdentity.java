package org.x2.export;
import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.*;
import java.security.MessageDigest;
final class BuildIdentity {
 static String version(Context c){try{PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0);return p.versionName+" ("+p.versionCode+")";}catch(Exception e){return "unknown";}}
 static String report(Context c){
  String digest="unavailable";
  try{File f=new File(c.getApplicationInfo().nativeLibraryDir,"libapp.so");if(f.isFile()){
   MessageDigest m=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(b))!=-1)m.update(b,0,n);}
   StringBuilder s=new StringBuilder();for(byte v:m.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));digest=s.toString();
  }}catch(Exception ignored){}
  return "version="+version(c)+"\npackage="+c.getPackageName()+"\nprocess64="+android.os.Process.is64Bit()+"\narch="+System.getProperty("os.arch","unknown")+"\ninstalled_libapp_sha256="+digest+"\nextra_dex_loader=disabled\ndefault_tab_policy=video (ARM64 only)";
 }
 private BuildIdentity(){}
}
