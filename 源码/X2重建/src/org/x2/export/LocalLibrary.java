package org.x2.export;
import android.content.Context;import android.database.Cursor;import android.net.Uri;import android.provider.DocumentsContract;import java.util.*;import java.io.IOException;
/** Imports file references, never moves or rewrites the old library or fabricates source identities. */
public final class LocalLibrary {
 public static int scan(Context context)throws Exception {
  String folder=DownloadConfig.folder(context);if(folder.isEmpty())throw new IOException("请先选择旧视频所在保存目录");
  Uri tree=Uri.parse(folder);String root=DocumentsContract.getTreeDocumentId(tree);ArrayDeque<String> pending=new ArrayDeque<String>();pending.add(root);Set<String> seen=new HashSet<String>();int count=0,visited=0;
  while(!pending.isEmpty()){
   String parent=pending.removeFirst();if(!seen.add(parent))continue;if(++visited>10000)throw new IOException("目录过多，请选择更小的视频目录");
   Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
   try(Cursor c=context.getContentResolver().query(children,new String[]{"document_id","_display_name","mime_type","_size"},null,null,null)){
    if(c==null)throw new IOException("无法读取视频目录");while(c.moveToNext()){
     String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
     if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){pending.add(id);continue;}
     if(name==null||(!name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")&&(mime==null||!mime.startsWith("video/"))))continue;
     if(!c.isNull(3)&&c.getLong(3)<64)continue;
     Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,id);
     if(DownloadStore.get(context).importLocal(folder,doc.toString(),name))count++;
    }
   }
  }
  return count;
 }
 private LocalLibrary(){}
}
