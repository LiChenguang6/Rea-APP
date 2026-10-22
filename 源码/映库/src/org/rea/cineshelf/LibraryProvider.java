package org.rea.cineshelf;

import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.util.*;

/** Read-only virtual document entries; video bytes stay in the user's selected folder. */
public class LibraryProvider extends DocumentsProvider {
  static final String[] ROOT = {
    "root_id", "flags", "icon", "title", "summary", "document_id", "mime_types", "available_bytes"
  };
  static final String[] DOC = {
    "document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"
  };

  public static Uri uri(String token) {
    return DocumentsContract.buildDocumentUri(Catalog.AUTH, token);
  }

  public boolean onCreate() {
    return true;
  }

  public Cursor queryRoots(String[] projection) {
    MatrixCursor c = new MatrixCursor(projection == null ? ROOT : projection);
    MatrixCursor.RowBuilder r = c.newRow();
    put(r, c, "root_id", "library");
    put(r, c, "flags", DocumentsContract.Root.FLAG_LOCAL_ONLY);
    put(r, c, "icon", org.rea.cineshelf.R.drawable.icon);
    put(r, c, "title", "映库");
    put(r, c, "summary", "一份真实文件，一个视频入口");
    put(r, c, "document_id", "root");
    put(r, c, "mime_types", "video/*");
    put(r, c, "available_bytes", 0L);
    return c;
  }

  public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    MatrixCursor c = new MatrixCursor(projection == null ? DOC : projection);
    if (id.equals("root")) root(c);
    else {
      Catalog.Video v = Catalog.get(getContext()).find(id);
      if (v == null) throw new FileNotFoundException("视频不可用");
      add(c, v, id);
    }
    return c;
  }

  public Cursor queryChildDocuments(String parent, String[] projection, String sort)
      throws FileNotFoundException {
    if (!parent.equals("root")) throw new FileNotFoundException();
    MatrixCursor c = new MatrixCursor(projection == null ? DOC : projection);
    for (Catalog.Group g : Catalog.get(getContext()).groups)
      if (g.first().available) add(c, g.first(), g.first().token());
    c.setNotificationUri(
        getContext().getContentResolver(), DocumentsContract.buildRootsUri(Catalog.AUTH));
    return c;
  }

  public String getDocumentType(String id) throws FileNotFoundException {
    if (id.equals("root")) return DocumentsContract.Document.MIME_TYPE_DIR;
    Catalog.Video v = Catalog.get(getContext()).find(id);
    if (v == null) throw new FileNotFoundException();
    return mime(v);
  }

  static String mime(Catalog.Video v) {
    String n = v.name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".mkv")) return "video/x-matroska";
    if (n.endsWith(".webm")) return "video/webm";
    if (n.endsWith(".mov")) return "video/quicktime";
    if (n.endsWith(".ts") || n.endsWith(".m2ts")) return "video/mp2t";
    String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
    String result = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    return result != null ? result : "video/mp4";
  }

  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("虚拟影库为只读");
    Catalog.Video v = Catalog.get(getContext()).find(id);
    if (v == null) throw new FileNotFoundException("文件不存在或目录权限已失效");
    long identity = Binder.clearCallingIdentity();
    try {
      ParcelFileDescriptor p =
          getContext().getContentResolver().openFileDescriptor(Uri.parse(v.uri), "r", signal);
      if (p == null) throw new FileNotFoundException();
      return p;
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  public boolean isChildDocument(String parent, String child) {
    return parent.equals("root") && Catalog.get(getContext()).find(child) != null;
  }

  void root(MatrixCursor c) {
    MatrixCursor.RowBuilder r = c.newRow();
    put(r, c, "document_id", "root");
    put(r, c, "_display_name", "映库");
    put(r, c, "mime_type", DocumentsContract.Document.MIME_TYPE_DIR);
    put(r, c, "flags", 0);
  }

  void add(MatrixCursor c, Catalog.Video v, String id) {
    MatrixCursor.RowBuilder r = c.newRow();
    put(r, c, "document_id", id);
    put(r, c, "_display_name", v.name);
    put(r, c, "mime_type", mime(v));
    put(r, c, "flags", 0);
    put(r, c, "_size", v.size);
    put(r, c, "last_modified", v.modified);
  }

  void put(MatrixCursor.RowBuilder r, MatrixCursor c, String key, Object value) {
    if (c.getColumnIndex(key) >= 0) r.add(key, value);
  }
}
