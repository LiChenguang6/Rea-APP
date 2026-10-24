package org.x2.export;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local-only preparation of the download format found in this APK. */
public final class PlaylistPreparer {
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;

    private PlaylistPreparer() { }

    public static final class Prepared {
        public final File playlist;
        public final int segments;
        public final double durationSeconds;
        public final String sourceName;

        public Prepared(File playlist, int segments, double durationSeconds, String sourceName) {
            this.playlist = playlist;
            this.segments = segments;
            this.durationSeconds = durationSeconds;
            this.sourceName = sourceName;
        }
    }

    public static Prepared prepare(File videoDir, File workDir) throws IOException {
        File root = videoDir.getCanonicalFile();
        if (!root.isDirectory()) throw failure("缓存目录不存在");
        // The app writes this only after finishing all segments. Never export a
        // partial raw manifest merely because some segment files already exist.
        checkedFile(root, new File(root, "index.m3u8"));
        File raw = new File(root, "index.raw.m3u8");
        boolean usingRaw = raw.exists();
        File source = checkedFile(root, usingRaw ? raw : new File(root, "index.m3u8"));
        if (source.length() > MAX_MANIFEST_BYTES) throw failure("播放清单过大");

        File work = workDir.getCanonicalFile();
        if (within(root, work)) throw failure("导出工作目录不得覆盖原缓存");
        if (!work.isDirectory() && !work.mkdirs()) throw failure("无法创建导出工作目录");

        Map<String, String> originalNames = new HashMap<String, String>();
        StringBuilder output = new StringBuilder();
        int segments = 0;
        double duration = 0;
        double pendingDuration = -1;
        boolean sawHeader = false;
        boolean sawEnd = false;
        boolean encrypted = false;
        boolean hasIv = false;
        String singleKeyIdentity = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > MAX_MANIFEST_BYTES) throw failure("播放清单过大");
                line = line.trim();
                if (!sawHeader && line.startsWith("\ufeff")) line = line.substring(1);
                if (line.length() == 0) continue;
                if (!sawHeader) {
                    if (!"#EXTM3U".equals(line)) throw failure("无效的 HLS 清单");
                    sawHeader = true;
                    output.append(line).append('\n');
                    continue;
                }
                if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                        || line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-SESSION-KEY")
                        || line.startsWith("#EXT-X-SESSION-DATA")) {
                    throw failure("暂不支持主播放清单或独立音轨");
                }
                if (line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-PART")
                        || line.startsWith("#EXT-X-PRELOAD-HINT") || line.startsWith("#EXT-X-RENDITION-REPORT")
                        || line.startsWith("#EXT-X-SKIP") || line.startsWith("#EXT-X-DEFINE")) {
                    throw failure("暂不支持字节范围、低延迟或变量清单");
                }
                if (line.startsWith("#EXT-X-KEY:")) {
                    LinkedHashMap<String, String> attrs = attributes(line.substring(line.indexOf(':') + 1));
                    String method = attrs.get("METHOD");
                    if ("NONE".equals(method)) {
                        if (attrs.containsKey("URI")) throw failure("未加密标签包含异常密钥地址");
                        encrypted = false;
                        hasIv = false;
                    } else {
                        if (!"AES-128".equals(method)) throw failure("仅支持普通 AES-128 HLS，不支持此加密方式");
                        String format = attrs.get("KEYFORMAT");
                        if (format != null && !"identity".equals(format)) throw failure("不支持 DRM 或非标准密钥格式");
                        String versions = attrs.get("KEYFORMATVERSIONS");
                        if (versions != null && !"1".equals(versions)) throw failure("不支持此密钥格式版本");
                        String keyUri = attrs.get("URI");
                        if (keyUri == null || keyUri.length() == 0) throw failure("缺少缓存密钥地址");
                        validateSourceUri(root, keyUri);
                        if (singleKeyIdentity != null && !singleKeyIdentity.equals(keyUri)) {
                            throw failure("缓存仅保留一把密钥，暂不支持密钥轮换");
                        }
                        singleKeyIdentity = keyUri;
                        File key = checkedFile(root, new File(root, "enc.key"));
                        if (key.length() != 16) throw failure("AES-128 缓存密钥长度应为 16 字节");
                        attrs.put("URI", key.toURI().toASCIIString());
                        String iv = attrs.get("IV");
                        if (iv != null && !iv.matches("0[xX][0-9a-fA-F]{1,32}")) throw failure("无效的 AES-128 IV");
                        if (!usingRaw && iv == null) {
                            throw failure("缺少原始清单，无法确认加密参数，请重新下载");
                        }
                        encrypted = true;
                        hasIv = iv != null;
                    }
                    line = "#EXT-X-KEY:" + serialize(attrs);
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    LinkedHashMap<String, String> attrs = attributes(line.substring(line.indexOf(':') + 1));
                    if (attrs.containsKey("BYTERANGE")) throw failure("暂不支持初始化片段字节范围");
                    if (encrypted && !hasIv) throw failure("加密初始化片段必须有显式 IV");
                    String uri = attrs.get("URI");
                    if (uri == null) throw failure("初始化片段缺少地址");
                    attrs.put("URI", localMedia(root, uri, originalNames).toURI().toASCIIString());
                    line = "#EXT-X-MAP:" + serialize(attrs);
                } else if (line.startsWith("#EXTINF:")) {
                    if (pendingDuration >= 0) throw failure("清单片段时长缺少对应文件");
                    String value = line.substring(8);
                    int comma = value.indexOf(',');
                    if (comma >= 0) value = value.substring(0, comma);
                    try { pendingDuration = Double.parseDouble(value); }
                    catch (NumberFormatException error) { throw failure("无效片段时长"); }
                    if (Double.isNaN(pendingDuration) || Double.isInfinite(pendingDuration) || pendingDuration < 0) {
                        throw failure("无效片段时长");
                    }
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    nonnegativeInteger(line.substring(line.indexOf(':') + 1));
                } else if ("#EXT-X-ENDLIST".equals(line)) {
                    if (pendingDuration >= 0) throw failure("清单末尾缺少片段");
                    sawEnd = true;
                } else if (!line.startsWith("#")) {
                    if (sawEnd || pendingDuration < 0) throw failure("媒体清单顺序异常或为主播放清单");
                    line = localMedia(root, line, originalNames).toURI().toASCIIString();
                    segments++;
                    duration += pendingDuration;
                    pendingDuration = -1;
                } else if (line.indexOf("URI=") >= 0 || line.indexOf("SERVER-URI=") >= 0) {
                    // Unknown URI-bearing extension must never leave a network
                    // reference in a playlist later handed to a media engine.
                    throw failure("暂不支持此清单外部资源标签");
                }
                output.append(line).append('\n');
            }
        } finally { reader.close(); }
        if (!sawHeader || !sawEnd || segments == 0 || pendingDuration >= 0) throw failure("缓存清单未完整结束");
        if (Double.isInfinite(duration) || Double.isNaN(duration)) throw failure("总时长异常");
        File playlist = File.createTempFile("x2-local-", ".m3u8", work);
        boolean finished = false;
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(playlist), "UTF-8");
            try { writer.write(output.toString()); }
            finally { writer.close(); }
            finished = true;
        } finally { if (!finished) playlist.delete(); }
        return new Prepared(playlist, segments, duration, source.getName());
    }

    private static File localMedia(File root, String original, Map<String, String> originalNames) throws IOException {
        URI uri = validateSourceUri(root, original);
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        if (name.length() == 0 || ".".equals(name) || "..".equals(name) || name.endsWith(".tmp")) {
            throw failure("无效或尚未完成的缓存文件名");
        }
        String previous = originalNames.get(name);
        if (previous != null && !previous.equals(original)) throw failure("不同片段地址使用同一文件名，缓存可能覆盖");
        originalNames.put(name, original);
        return checkedFile(root, new File(root, name));
    }

    private static URI validateSourceUri(File root, String original) throws IOException {
        if (original.indexOf('\\') >= 0 || original.indexOf('\0') >= 0) throw failure("缓存地址包含非法路径字符");
        URI uri;
        try { uri = new URI(original); }
        catch (URISyntaxException error) { throw failure("清单资源地址无效"); }
        String scheme = uri.getScheme();
        if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)
                && !"file".equalsIgnoreCase(scheme)) throw failure("不支持此资源地址类型");
        String path = uri.getPath();
        if (path == null || path.length() == 0 || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw failure("资源地址缺少有效路径");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && (rawPath.toLowerCase(java.util.Locale.US).indexOf("%2f") >= 0
                || rawPath.toLowerCase(java.util.Locale.US).indexOf("%5c") >= 0)) {
            // Dart pathSegments decodes each component separately. An encoded
            // slash must not silently turn a downloaded nested name into a
            // different basename during Java normalization.
            throw failure("资源地址包含编码路径分隔符");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if ("..".equals(part)) throw failure("资源地址不得逃出缓存目录");
        }
        if ("file".equalsIgnoreCase(scheme)) {
            if (uri.getAuthority() != null && uri.getAuthority().length() != 0) throw failure("拒绝外部文件主机");
            File local;
            try { local = new File(uri).getCanonicalFile(); }
            catch (IllegalArgumentException error) { throw failure("无效本地资源地址"); }
            if (!within(root, local)) throw failure("资源地址不得逃出缓存目录");
        } else if (scheme == null && path.startsWith("/") && uri.getAuthority() == null) {
            // Server-root paths in the downloaded raw playlist are valid remote
            // media paths; they are mapped only by basename, never opened as-is.
        }
        return uri;
    }

    private static File checkedFile(File root, File candidate) throws IOException {
        File file = candidate.getCanonicalFile();
        if (!within(root, file)) throw failure("缓存文件路径越界");
        if (!file.isFile() || file.length() <= 0) throw failure("缓存缺少完整文件：" + candidate.getName());
        return file;
    }

    private static boolean within(File root, File file) {
        return file.equals(root) || file.getPath().startsWith(root.getPath() + File.separator);
    }

    private static void nonnegativeInteger(String value) throws IOException {
        try { if (Long.parseLong(value.trim()) < 0) throw failure("无效媒体序号"); }
        catch (NumberFormatException error) { throw failure("无效媒体序号"); }
    }

    private static LinkedHashMap<String, String> attributes(String text) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        int cursor = 0;
        while (cursor < text.length()) {
            int equals = text.indexOf('=', cursor);
            if (equals < 0) throw failure("清单属性格式错误");
            String name = text.substring(cursor, equals).trim();
            if (!name.matches("[A-Z0-9-]+") || result.containsKey(name)) throw failure("无效或重复的清单属性");
            cursor = equals + 1;
            String value;
            if (cursor < text.length() && text.charAt(cursor) == '"') {
                int close = text.indexOf('"', cursor + 1);
                if (close < 0) throw failure("清单属性引号未闭合");
                value = text.substring(cursor + 1, close);
                cursor = close + 1;
                if (cursor < text.length() && text.charAt(cursor) != ',') throw failure("清单属性后存在异常字符");
            } else {
                int comma = text.indexOf(',', cursor);
                if (comma < 0) comma = text.length();
                value = text.substring(cursor, comma).trim();
                cursor = comma;
            }
            if (value.length() == 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw failure("清单属性值无效");
            result.put(name, value);
            if (cursor < text.length()) {
                cursor++;
                if (cursor == text.length()) throw failure("清单属性末尾异常");
            }
        }
        return result;
    }

    private static String serialize(LinkedHashMap<String, String> attrs) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> attr : attrs.entrySet()) {
            if (text.length() > 0) text.append(',');
            text.append(attr.getKey()).append('=');
            String name = attr.getKey();
            boolean quoted = "URI".equals(name) || "KEYFORMAT".equals(name) || "KEYFORMATVERSIONS".equals(name);
            if (quoted) text.append('"');
            text.append(attr.getValue());
            if (quoted) text.append('"');
        }
        return text.toString();
    }

    private static IOException failure(String reason) { return new IOException(reason); }
}
