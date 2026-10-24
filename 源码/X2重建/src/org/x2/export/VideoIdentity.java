package org.x2.export;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Stable fingerprints only; never retain URLs or authentication in identifiers. */
final class VideoIdentity {
    static String source(String value){
        if(value==null||value.trim().isEmpty())return "";
        String canonical=value.trim();
        try{
            URI uri=new URI(canonical).normalize();String scheme=uri.getScheme(),host=uri.getHost();
            if(scheme!=null&&host!=null){
                scheme=scheme.toLowerCase(Locale.ROOT);host=host.toLowerCase(Locale.ROOT);
                int port=uri.getPort();if((scheme.equals("https")&&port==443)||(scheme.equals("http")&&port==80))port=-1;
                if(host.indexOf(':')>=0&&!host.startsWith("["))host="["+host+"]";
                String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";
                canonical=scheme+"://"+(uri.getRawUserInfo()==null?"":uri.getRawUserInfo()+"@")+host+(port<0?"":":"+port)+path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
            }else{int fragment=canonical.indexOf('#');if(fragment>=0)canonical=canonical.substring(0,fragment);}
        }catch(Exception ignored){ }
        return digest("source-v1\n"+canonical);
    }
    static String video(String report){
        if(report==null||!report.startsWith("detail_match=exact_uri\n"))return "";
        try{
            String id="",type="";
            for(String line:report.split("\n")){if(line.startsWith("detail_video_id="))id=line.substring(16);if(line.startsWith("detail_video_type="))type=line.substring(18);}
            long number=Long.parseLong(id),kind=Long.parseLong(type);if(number<=0||kind<0)return "";
            return digest("video-v1\n"+number+"\n"+kind);
        }catch(Exception ignored){return "";}
    }
    private static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private VideoIdentity(){ }
}
