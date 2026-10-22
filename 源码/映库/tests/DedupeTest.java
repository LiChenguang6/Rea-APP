import java.io.*;
import java.util.*;
import org.rea.cineshelf.Dedupe;

public class DedupeTest {
  static Dedupe.Source source(byte[] b) {
    return new Dedupe.Source() {
      public InputStream open() {
        return new ByteArrayInputStream(b);
      }

      public long size() {
        return b.length;
      }
    };
  }

  static void check(boolean b, String s) {
    if (!b) throw new AssertionError(s);
  }

  public static void main(String[] args) throws Exception {
    byte[] a = new byte[1000000];
    new Random(42).nextBytes(a);
    byte[] copy = a.clone();
    byte[] collision = a.clone();
    collision[170000] ^= 1;
    check(Dedupe.verifiedEqual(source(a), source(copy)), "exact copy");
    check(
        Dedupe.sample(source(a)).equals(Dedupe.sample(source(collision))),
        "sample collision fixture");
    check(!Dedupe.verifiedEqual(source(a), source(collision)), "sample collision must not merge");
    check(!Dedupe.verifiedEqual(source(a), source(new byte[3])), "different lengths");
    check(
        !Dedupe.visuallySimilar(new long[] {0, 0, 0}, new long[] {0, 0, 0}, 10000, 10000),
        "flat frames rejected");
    long[] fp = {0x123456789ABCDEFL, 0xFEDCBA987654321L, 0xF0F0F0F0F0F0F0F0L};
    check(Dedupe.visuallySimilar(fp, fp, 10000, 10010), "visual hint");
    check(!Dedupe.visuallySimilar(fp, fp, 10000, 20000), "duration mismatch");
    Dedupe.Source changed =
        new Dedupe.Source() {
          int n;

          public InputStream open() {
            return new ByteArrayInputStream(a);
          }

          public long size() {
            return n++ == 0 ? a.length : a.length + 1;
          }
        };
    try {
      Dedupe.hash(changed);
      throw new AssertionError("changed file accepted");
    } catch (IOException expected) {
    }
    Dedupe.Source broken =
        new Dedupe.Source() {
          public InputStream open() throws IOException {
            throw new IOException("denied");
          }

          public long size() {
            return 10;
          }
        };
    try {
      Dedupe.hash(broken);
      throw new AssertionError("unreadable accepted");
    } catch (IOException expected) {
    }
    Thread.currentThread().interrupt();
    try {
      Dedupe.hash(source(a));
      throw new AssertionError("cancel ignored");
    } catch (InterruptedIOException expected) {
    } finally {
      Thread.interrupted();
    }
    System.out.println(
        "PASS: 10 exact-match, collision, change, failure, cancellation and similarity checks");
  }
}
