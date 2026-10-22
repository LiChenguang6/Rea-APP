package org.rea.cineshelf;

import java.io.*;
import java.security.*;
import java.util.*;

/** Platform-independent exact matching. Samples only shortlist; never prove equality. */
public final class Dedupe {
  public interface Source {
    InputStream open() throws IOException;

    long size() throws IOException;
  }

  public static String hash(Source source) throws IOException {
    long before = source.size(), read = 0;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = source.open()) {
        byte[] b = new byte[262144];
        int n;
        while ((n = in.read(b)) != -1) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
          digest.update(b, 0, n);
          read += n;
        }
      }
      if (read != before || source.size() != before)
        throw new IOException("File changed while reading");
      StringBuilder out = new StringBuilder();
      for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  public static String sample(Source source) throws IOException {
    long size = source.size();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] b = new byte[65536];
      for (long offset : new long[] {0, Math.max(0, size / 2 - 32768), Math.max(0, size - 65536)}) {
        try (InputStream in = source.open()) {
          long left = offset;
          while (left > 0) {
            long n = in.skip(left);
            if (n == 0) {
              if (in.read() < 0) throw new EOFException();
              n = 1;
            }
            left -= n;
          }
          int remain = (int) Math.min(b.length, size - offset);
          while (remain > 0) {
            int n = in.read(b, 0, remain);
            if (n < 0) throw new EOFException();
            digest.update(b, 0, n);
            remain -= n;
          }
        }
      }
      return Base64.getEncoder().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  public static boolean verifiedEqual(Source keeper, Source candidate) throws IOException {
    return keeper.size() == candidate.size() && hash(keeper).equals(hash(candidate));
  }

  public static int distance(long a, long b) {
    return Long.bitCount(a ^ b);
  }

  /** Conservative hint only: three non-flat frames and similar duration. */
  public static boolean visuallySimilar(long[] a, long[] b, long durationA, long durationB) {
    if (a == null
        || b == null
        || a.length != 3
        || b.length != 3
        || durationA < 3000
        || durationB < 3000) return false;
    if (Math.abs(durationA - durationB) > Math.max(1500, Math.min(durationA, durationB) / 50))
      return false;
    int sum = 0;
    for (int i = 0; i < 3; i++) {
      int bits = Long.bitCount(a[i]);
      int other = Long.bitCount(b[i]);
      if (bits < 6 || bits > 58 || other < 6 || other > 58) return false;
      int d = distance(a[i], b[i]);
      if (d > 10) return false;
      sum += d;
    }
    return sum <= 21;
  }
}
