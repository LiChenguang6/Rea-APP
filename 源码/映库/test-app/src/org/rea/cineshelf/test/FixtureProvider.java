package org.rea.cineshelf.test;

import android.database.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;

public class FixtureProvider extends ContentProvider {
  File dir;

  // Test-only SAF-shaped provider; deliberately not a system DocumentsProvider.
  // Synthetic fixtures are exported only by the separate test APK.
  public Cursor query(Uri uri,String[] p,String selection,String[] args,String sort){
   try{return uri.getPath().endsWith("/children")?queryChildDocuments(DocumentsContract.getDocumentId(uri),p,sort):queryDocument(DocumentsContract.getDocumentId(uri),p);}catch(FileNotFoundException e){return null;}
  }
  public String getType(Uri uri){return "video/mp4";}
  public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
  public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
  public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
  public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return openDocument(DocumentsContract.getDocumentId(uri),mode,null);}
  public boolean onCreate() {
    dir = new File(getContext().getFilesDir(), "videos");
    dir.mkdirs();
    return true;
  }

  public Bundle call(String method, String arg, Bundle extras) {
    try {
      if (method.equals("reset")) {
        for (File f : dir.listFiles()) f.delete();
        for (String name : getContext().getAssets().list("")) {
          if (name.endsWith(".mp4")) {
            copy(name, name);
            if (name.equals("Neon-motion.mp4")) copy(name, "Neon-motion-copy.mp4");
          }
        }
      } else if (method.equals("mutate")) {
        try (RandomAccessFile f =
            new RandomAccessFile(new File(dir, "Neon-motion-copy.mp4"), "rw")) {
          f.seek(170000);
          f.write(42);
        }
      } else if(method.equals("android:deleteDocument")){Uri uri=extras.getParcelable("uri");deleteDocument(DocumentsContract.getDocumentId(uri));} else return super.call(method, arg, extras);
      return new Bundle();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void copy(String asset, String name) throws Exception {
    try (InputStream in = getContext().getAssets().open(asset);
        OutputStream out = new FileOutputStream(new File(dir, name))) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
  }

  String[] cols(String[] p) {
    return p == null
        ? new String[] {
          "document_id", "_display_name", "mime_type", "_size", "last_modified", "flags"
        }
        : p;
  }

  void add(MatrixCursor c, String id) {
    File f = new File(dir, id);
    MatrixCursor.RowBuilder r = c.newRow();
    for (String col : c.getColumnNames()) {
      Object v = null;
      switch (col) {
        case "document_id":
          v = id;
          break;
        case "_display_name":
          v = id.equals("root") ? "Synthetic videos" : id;
          break;
        case "mime_type":
          v = id.equals("root") ? DocumentsContract.Document.MIME_TYPE_DIR : "video/mp4";
          break;
        case "_size":
          v = f.length();
          break;
        case "last_modified":
          v = f.lastModified();
          break;
        case "flags":
          v = id.equals("root") ? 0 : DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
          break;
      }
      r.add(col, v);
    }
  }

  public Cursor queryRoots(String[] p) {
    return new MatrixCursor(p == null ? new String[] {"root_id"} : p);
  }

  public Cursor queryDocument(String id, String[] p) throws FileNotFoundException {
    if (!id.equals("root") && !new File(dir, id).isFile()) throw new FileNotFoundException();
    MatrixCursor c = new MatrixCursor(cols(p));
    add(c, id);
    return c;
  }

  public Cursor queryChildDocuments(String id, String[] p, String sort) {
    MatrixCursor c = new MatrixCursor(cols(p));
    for (File f : dir.listFiles()) add(c, f.getName());
    return c;
  }

  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    if (!mode.equals("r")) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(new File(dir, id), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public boolean isChildDocument(String parent, String child) {
    return parent.equals("root") && !child.contains("/") && new File(dir, child).isFile();
  }

  public void deleteDocument(String id) throws FileNotFoundException {
    if (id.contains("/") || !new File(dir, id).delete()) throw new FileNotFoundException();
  }
}
