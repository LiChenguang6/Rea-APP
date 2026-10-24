package org.x2.export;

/** Provider-safe names; leave collision resolution to createDocument atomically. */
public final class VideoFilename {
    private VideoFilename() {}
    public static String mp4(String title) {
        String text=title==null?"":title.trim();
        if(text.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))text=text.substring(0,text.length()-4);
        StringBuilder out=new StringBuilder();int bytes=0;
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isISOControl(cp)||"/\\:*?\"<>|".indexOf(cp)>=0)cp='_';
            if(cp>=0xD800&&cp<=0xDFFF)cp='_';
            int length=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
            if(bytes+length>200)break;out.appendCodePoint(cp);bytes+=length;
        }
        String name=out.toString().trim().replaceAll("[. ]+$","");
        if(name.isEmpty())name="当前播放视频";
        return name+".mp4";
    }
}
