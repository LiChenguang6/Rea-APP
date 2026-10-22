package org.rea.cineshelf.test;

import android.app.*;
import android.os.*;
import java.io.*;

public class ReadActivity extends Activity {
  public void onCreate(Bundle b) {
    super.onCreate(b);
    try (InputStream in = getContentResolver().openInputStream(getIntent().getData())) {
      int total = 0, n;
      byte[] bytes = new byte[8192];
      while ((n = in.read(bytes)) != -1) total += n;
      getPreferences(0).edit().putInt("read", total).commit();
      android.util.Log.i("CineShelfTest", "EXTERNAL_GRANTED_READ_BYTES=" + total);
    } catch (Exception e) {
      android.util.Log.e("CineShelfTest", "EXTERNAL_READ_FAILED", e);
    }
    finish();
  }
}
