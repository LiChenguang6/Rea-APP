package org.x2.export;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Downloads only a captured playback URI. Does not construct application APIs or authentication. */
public final class MediaDownloader {
    private static final int MAX_MANIFEST = 4 * 1024 * 1024;
    private static final int MAX_SEGMENTS = 20000;
    private static final int MAX_KEYS = 128;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_MASTER_DEPTH = 3;
    private static final long MAX_SEGMENT_BYTES = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024;
    private static final double MAX_DURATION = 24 * 60 * 60;
    private static final Progress NO_PROGRESS = new Progress() {
        @Override public void update(String stage, double fraction) { }
        @Override public boolean cancelled() { return false; }
    };

    public interface Progress {
        /** fraction is -1 when a response does not have a known content length. */
        void update(String stage, double fraction);
        boolean cancelled();
        default void awaitActive() throws IOException { }
        default void transferFailed() { }
    }

    public static final class Result {
        public final File input;
        /** HLS EXTINF sum; -1 for a direct media file, which needs native probing. */
        public final double durationSeconds;
        public final String suggestedName;
        public Result(File input, double durationSeconds, String suggestedName) {
            this.input = input;
            this.durationSeconds = durationSeconds;
            this.suggestedName = suggestedName;
        }
    }

    private MediaDownloader() { }

    /** headers contains individual "Name: value" fields, as in mpv http-header-fields. */
    public static Result download(String uri, String userAgent, String referrer, String[] headers,
                                  File work, Progress progress) throws IOException {
        URI initial = httpUri(uri);
        Session session = new Session(initial, userAgent, referrer, headers, work,
                progress == null ? NO_PROGRESS : progress);
        try { return session.download(initial); }
        finally { session.close(); }
    }

    /** Read-only, bounded inspection of the captured manifest. Never fetches keys or segments. */
    public static String inspect(String uri, String agent, String referrer, String[] headers,
                                 File work, Progress progress) throws IOException {
        URI source=httpUri(uri);Session session=new Session(source,agent,referrer,headers,work,progress==null?NO_PROGRESS:progress);
        try {
            for(int depth=0;depth<=MAX_MASTER_DEPTH;depth++) {
                session.check();Response response=session.open(source,"检查播放清单");byte[] manifest;
                try {
                    BufferedInputStream stream=new BufferedInputStream(response.stream,8192);session.activeStream=stream;stream.mark(4096);
                    byte[] prefix=new byte[2048];int size=0,nonSpace=0;
                    while(size<prefix.length&&nonSpace<16){session.check();int v=stream.read();if(v<0)break;prefix[size++]=(byte)v;if(v>32)nonSpace++;}
                    stream.reset();String start=new String(prefix,0,size,StandardCharsets.UTF_8).trim();if(start.startsWith("\ufeff"))start=start.substring(1).trim();
                    if(!start.startsWith("#EXTM3U")) {
                        String lower=start.toLowerCase(Locale.ROOT),ct=response.connection.getContentType();ct=ct==null?"":ct.toLowerCase(Locale.ROOT);
                        boolean page=lower.startsWith("<html")||lower.startsWith("<!doctype")||ct.contains("text/html");
                        boolean json=lower.startsWith("{")||lower.startsWith("[")||ct.contains("application/json");
                        return "http=200\nkind="+(page?"html":json?"json":"direct_or_unknown")+"\nmanifest_depth="+depth+"\n";
                    }
                    manifest=session.readBounded(stream,MAX_MANIFEST,"检查播放清单");
                    if(response.length>=0&&manifest.length!=response.length)throw error("清单响应被截断");
                } finally {response.close();}
                List<String> entries=lines(manifest);URI variant=masterVariant(entries,response.uri);
                if(variant!=null){if(depth==MAX_MASTER_DEPTH)throw error("播放清单嵌套过深");source=variant;continue;}
                int segments=0;double seconds=0,pending=-1;boolean end=false,encrypted=false,unsupportedEncryption=false;
                for(String line:entries){
                    session.check();
                    if(line.startsWith("#EXTINF:")){
                        if(pending>=0)throw error("分片时长缺少对应地址");String value=afterColon(line);int comma=value.indexOf(',');if(comma>=0)value=value.substring(0,comma);
                        try{pending=Double.parseDouble(value);}catch(NumberFormatException e){throw error("分片时长格式错误");}
                        if(Double.isNaN(pending)||Double.isInfinite(pending)||pending<0||pending>MAX_DURATION)throw error("分片时长无效");
                    }else if(line.equals("#EXT-X-ENDLIST"))end=true;
                    else if(line.startsWith("#EXT-X-KEY:")){Map<String,String> a=attributes(afterColon(line));String method=a.get("METHOD");if(!"NONE".equals(method)){encrypted=true;if(!"AES-128".equals(method)||(a.containsKey("KEYFORMAT")&&!"identity".equals(a.get("KEYFORMAT"))))unsupportedEncryption=true;}}
                    else if(!line.startsWith("#")){if(pending<0)throw error("分片缺少时长");seconds+=pending;pending=-1;if(++segments>MAX_SEGMENTS||seconds>MAX_DURATION)throw error("清单超出检查限制");}
                }
                if(pending>=0||segments==0)throw error("媒体清单不完整");
                return "http=200\nkind=hls\nmanifest_depth="+depth+"\nvariant_policy="+(depth==0?"original_media_playlist":"highest_bandwidth")+"\nsegments="+segments+"\nmanifest_seconds="+String.format(Locale.ROOT,"%.3f",seconds)+"\nendlist="+(end?1:0)+"\nencrypted="+(encrypted?1:0)+"\nnonstandard_encryption="+(unsupportedEncryption?1:0)+"\n";
            }
            throw error("播放清单嵌套过深");
        } finally {session.close();session.directory.delete();}
    }

    private static final class Session {
        final URI initial;
        final String origin;
        final String agent;
        final String referrer;
        final LinkedHashMap<String, String> headers;
        final File directory;
        final Progress progress;
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        final Thread cancellationMonitor;
        volatile HttpURLConnection active;
        volatile InputStream activeStream;
        volatile boolean done;
        long bytes;

        Session(URI initial, String agent, String referrer, String[] fields, File work,
                Progress progress) throws IOException {
            this.initial = initial;
            this.origin = origin(initial);
            this.headers = parseHeaders(fields);
            this.agent = checkedValue(agent, "User-Agent");
            this.referrer = checkedReferrer(referrer);
            this.progress = progress;
            if (work == null || (!work.isDirectory() && !work.mkdirs())) throw error("无法创建下载工作目录");
            directory = new File(work, "playback-" + UUID.randomUUID().toString());
            if (!directory.mkdir()) throw error("无法创建独立下载目录");
            cancellationMonitor = new Thread(new Runnable() {
                @Override public void run() {
                    while (!done) {
                        if (Session.this.progress.cancelled()) {
                            HttpURLConnection connection = active;
                            if (connection != null) try { connection.disconnect(); } catch (RuntimeException ignored) { }
                            InputStream stream = activeStream;
                            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
                            return;
                        }
                        try { Thread.sleep(100); }
                        catch (InterruptedException ignored) { return; }
                    }
                }
            }, "playback-download-cancel");
            cancellationMonitor.setDaemon(true);
            cancellationMonitor.start();
        }

        void check() throws IOException {
            progress.awaitActive();
            if (progress.cancelled() || Thread.currentThread().isInterrupted()) throw error("已取消下载");
        }

        void close() {
            done = true;
            cancellationMonitor.interrupt();
            InputStream stream = activeStream;
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            HttpURLConnection connection = active;
            if (connection != null) connection.disconnect();
            active = null;
            activeStream = null;
        }

        Result download(URI source) throws IOException {
            for (int depth = 0; depth <= MAX_MASTER_DEPTH; depth++) {
                check();
                String stage = depth == 0 ? "读取当前播放地址" : "读取所选媒体清单";
                progress.update(stage, -1);
                Response response = open(source, stage);
                byte[] manifest;
                try {
                    BufferedInputStream stream = new BufferedInputStream(response.stream, 65536);
                    activeStream = stream;
                    stream.mark(4096);
                    byte[] prefix = new byte[2048];
                    int length = 0;
                    int nonSpace = 0;
                    while (length < prefix.length && nonSpace < 16) {
                        check();
                        int value = stream.read();
                        if (value < 0) break;
                        prefix[length++] = (byte)value;
                        if (value > 32) nonSpace++;
                    }
                    stream.reset();
                    String start = new String(prefix, 0, length, StandardCharsets.UTF_8).trim();
                    if (start.startsWith("\ufeff")) start = start.substring(1).trim();
                    boolean hls = start.startsWith("#EXTM3U");
                    if (!hls) {
                        String trimmed = start.trim().toLowerCase(Locale.ROOT);
                        String contentType = response.connection.getContentType();
                        if (trimmed.startsWith("<html") || trimmed.startsWith("<!doctype")
                                || trimmed.startsWith("{") || trimmed.startsWith("[")
                                || (contentType != null && (contentType.toLowerCase(Locale.ROOT).contains("text/html")
                                    || contentType.toLowerCase(Locale.ROOT).contains("application/json"))))
                            throw error(stage + "：服务器返回网页或错误数据，不是媒体（" + safeHost(response.uri) + "）");
                        if (response.uri.getPath() != null && response.uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"))
                            throw error(stage + "：播放清单缺少 EXTM3U 标记（" + safeHost(response.uri) + "）");
                        File target = new File(directory, "source-media.bin");
                        write(stream, response.length, target, MAX_TOTAL_BYTES, "下载当前视频", 0, 1);
                        return new Result(target, -1, "video.mp4");
                    }
                    manifest = readBounded(stream, MAX_MANIFEST, stage);
                    if (response.length >= 0 && manifest.length != response.length)
                        throw retryable(stage + "：播放清单响应被截断");
                } catch (IOException exception) {
                    progress.transferFailed();
                    check();
                    throw sanitized(exception, stage, response.uri);
                } finally { response.close(); }
                List<String> lines = lines(manifest);
                URI variant = masterVariant(lines, response.uri);
                if (variant != null) {
                    if (depth == MAX_MASTER_DEPTH) throw error("播放清单嵌套过深");
                    source = variant;
                    continue;
                }
                return mediaPlaylist(lines, response.uri);
            }
            throw error("播放清单嵌套过深");
        }

        Result mediaPlaylist(List<String> lines, URI base) throws IOException {
            List<String> rewritten = new ArrayList<String>();
            List<Segment> segments = new ArrayList<Segment>();
            LinkedHashMap<URI, File> keys = new LinkedHashMap<URI, File>();
            double duration = 0;
            double pending = -1;
            boolean end = false;
            boolean aes = false;
            for (String line : lines) {
                check();
                rejectUnsupported(line);
                if (line.startsWith("#EXT-X-KEY:")) {
                    LinkedHashMap<String, String> attrs = attributes(afterColon(line));
                    String method = attrs.get("METHOD");
                    if ("NONE".equals(method)) {
                        if (attrs.containsKey("URI")) throw error("未加密标签包含异常密钥地址");
                        aes = false;
                    } else {
                        if (!"AES-128".equals(method)) throw error("只支持普通 AES-128 HLS；此视频使用不支持的加密方式");
                        if (attrs.containsKey("KEYFORMAT") && !"identity".equals(attrs.get("KEYFORMAT")))
                            throw error("不支持 DRM 或非标准密钥格式");
                        if (attrs.containsKey("KEYFORMATVERSIONS") && !"1".equals(attrs.get("KEYFORMATVERSIONS")))
                            throw error("不支持此密钥格式版本");
                        for (String name : attrs.keySet()) if (!name.equals("METHOD") && !name.equals("URI")
                                && !name.equals("IV") && !name.equals("KEYFORMAT") && !name.equals("KEYFORMATVERSIONS"))
                            throw error("不支持此加密清单属性");
                        String iv = attrs.get("IV");
                        if (iv != null && !iv.matches("0[xX][0-9A-Fa-f]{1,32}")) throw error("HLS IV 格式错误");
                        URI keyUri = resolve(base, attrs.get("URI"));
                        File key = keys.get(keyUri);
                        if (key == null) {
                            if (keys.size() >= MAX_KEYS) throw error("密钥数量超过限制");
                            key = new File(directory, String.format(Locale.ROOT, "key%03d.key", keys.size()));
                            keys.put(keyUri, key);
                        }
                        attrs.put("URI", key.toURI().toASCIIString());
                        aes = true;
                    }
                    line = "#EXT-X-KEY:" + serialize(attrs);
                } else if (line.startsWith("#EXTINF:")) {
                    if (pending >= 0) throw error("清单缺少对应媒体分片");
                    String value = afterColon(line);
                    int comma = value.indexOf(',');
                    if (comma >= 0) value = value.substring(0, comma);
                    try { pending = Double.parseDouble(value); }
                    catch (NumberFormatException exception) { throw error("清单分片时长无效"); }
                    if (Double.isNaN(pending) || Double.isInfinite(pending) || pending <= 0)
                        throw error("清单分片时长无效");
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try { if (Long.parseLong(afterColon(line)) < 0) throw error("媒体序号无效"); }
                    catch (NumberFormatException exception) { throw error("媒体序号无效"); }
                } else if (line.equals("#EXT-X-ENDLIST")) {
                    if (pending >= 0 || end) throw error("清单结束标记无效");
                    end = true;
                } else if (!line.startsWith("#")) {
                    if (end || pending < 0) throw error("媒体清单顺序错误");
                    if (segments.size() >= MAX_SEGMENTS) throw error("媒体分片数量超过限制");
                    URI remote = resolve(base, line);
                    File local = new File(directory, String.format(Locale.ROOT, "segment%05d.ts", segments.size()));
                    segments.add(new Segment(remote, local, aes));
                    line = local.toURI().toASCIIString();
                    duration += pending;
                    if (duration > MAX_DURATION) throw error("视频时长超过单次下载限制");
                    pending = -1;
                } else if (line.contains("URI=")) {
                    throw error("不支持此清单中的外部资源标签");
                }
                rewritten.add(line);
            }
            if (!end) throw error("当前为直播或未结束的 HLS 清单，无法完整保存");
            if (pending >= 0 || segments.isEmpty()) throw error("媒体清单不完整或没有分片");
            // Validate the whole playlist before requesting keys or segments.
            for (Map.Entry<URI, File> key : keys.entrySet()) {
                transfer(key.getKey(), key.getValue(), 17, "下载 HLS 密钥", 0, 1);
                if (key.getValue().length() != 16) throw error("HLS AES-128 密钥长度不是 16 字节");
            }
            for (int index = 0; index < segments.size(); index++) {
                check();
                Segment segment = segments.get(index);
                transfer(segment.uri, segment.file, MAX_SEGMENT_BYTES,
                        "下载媒体分片 " + (index + 1) + "/" + segments.size(), index, segments.size());
                if (segment.encrypted && segment.file.length() % 16 != 0)
                    throw error("加密分片长度无效，未生成可导出的清单");
            }
            File playlist = new File(directory, "local.m3u8");
            File temporary = new File(directory, "local.m3u8.part");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                for (String line : rewritten) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                }
            }
            check();
            if (!temporary.renameTo(playlist)) throw error("无法完成本地播放清单");
            progress.update("当前视频下载完成", 1);
            return new Result(playlist, duration, "video.mp4");
        }

        void transfer(URI uri, File target, long maximum, String stage, int index, int total) throws IOException {
            IOException failure = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                check();
                Response response = null;
                try {
                    response = open(uri, stage);
                    write(response.stream, response.length, target, maximum, stage, index, total);
                    return;
                } catch (IOException exception) {
                    progress.transferFailed();
                    check();
                    failure = sanitized(exception, stage, response == null ? uri : response.uri);
                    if (exception instanceof ReportedException && !((ReportedException)exception).retryable) throw failure;
                    if (attempt == 0) {
                        progress.update(stage + "，正在重试", -1);
                        try { Thread.sleep(150); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); check(); }
                    }
                } finally { if (response != null) response.close(); }
            }
            throw failure == null ? error(stage + "失败") : failure;
        }

        void write(InputStream input, long expected, File target, long maximum,
                   String stage, int index, int total) throws IOException {
            if (expected > maximum || (expected > 0 && bytes + expected > MAX_TOTAL_BYTES))
                throw error(stage + "：媒体大小超过限制");
            File part = new File(target.getPath() + ".part");
            long count = 0;
            boolean complete = false;
            try {
                try (FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[65536];
                    while (true) {
                        check();
                        int amount = input.read(buffer);
                        if (amount < 0) break;
                        if (amount == 0) continue;
                        count += amount;
                        bytes += amount;
                        if (count > maximum || bytes > MAX_TOTAL_BYTES) throw error(stage + "：媒体大小超过限制");
                        output.write(buffer, 0, amount);
                        double fraction = expected > 0 ? (index + Math.min(1.0, (double)count / expected)) / total : -1;
                        progress.update(stage, fraction);
                    }
                }
                check();
                if (count == 0) throw error(stage + "：服务器返回空文件");
                if (expected >= 0 && count != expected) throw retryable(stage + "：响应被截断");
                if (!part.renameTo(target)) throw error(stage + "：无法完成本地文件");
                complete = true;
            } finally { if (!complete) part.delete(); }
        }

        Response open(URI requested, String stage) throws IOException {
            URI uri = requested;
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                check();
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection)uri.toURL().openConnection();
                    active = connection;
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(15000);
                    connection.setUseCaches(false);
                    connection.setRequestMethod("GET");
                    boolean sameOrigin = origin(uri).equals(origin);
                    if (sameOrigin) for (Map.Entry<String, String> field : headers.entrySet())
                        connection.setRequestProperty(field.getKey(), field.getValue());
                    String effectiveAgent = agent != null ? agent : field(headers, "User-Agent");
                    if (effectiveAgent != null) connection.setRequestProperty("User-Agent", effectiveAgent);
                    String effectiveReferrer = referrer != null ? referrer : field(headers, "Referer");
                    if (effectiveReferrer != null) {
                        if (!sameOrigin) effectiveReferrer = crossOriginReferrer(effectiveReferrer, uri);
                        if (effectiveReferrer != null) connection.setRequestProperty("Referer", effectiveReferrer);
                    }
                    for (Map.Entry<String, List<String>> cookie : cookies.get(uri, Collections.<String, List<String>>emptyMap()).entrySet()) {
                        if (!cookie.getKey().equalsIgnoreCase("Cookie") && !cookie.getKey().equalsIgnoreCase("Cookie2")) continue;
                        String existing = connection.getRequestProperty(cookie.getKey());
                        StringBuilder joined = new StringBuilder(existing == null ? "" : existing);
                        for (String value : cookie.getValue()) {
                            if (joined.length() > 0) joined.append("; ");
                            joined.append(value);
                        }
                        if (joined.length() > 0) connection.setRequestProperty(cookie.getKey(), joined.toString());
                    }
                    // Encoding, ranges, connection framing and redirects are transport controls,
                    // not application authentication; request complete uncompressed objects.
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    int status = connection.getResponseCode();
                    cookies.put(uri, connection.getHeaderFields());
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = connection.getHeaderField("Location");
                        URI next = resolve(uri, location);
                        connection.disconnect();
                        if (redirect == MAX_REDIRECTS) throw error(stage + "：重定向次数超过限制（" + safeHost(uri) + "）");
                        uri = next;
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        connection.disconnect();
                        throw new ReportedException(stage + "：HTTP " + status + "（" + safeHost(uri) + "）",
                                status == 408 || status == 429 || status >= 500);
                    }
                    String encoding = connection.getContentEncoding();
                    if (encoding != null && encoding.length() > 0 && !encoding.equalsIgnoreCase("identity"))
                        throw error(stage + "：服务器未返回请求的原始媒体编码（" + safeHost(uri) + "）");
                    InputStream stream = connection.getInputStream();
                    activeStream = stream;
                    return new Response(connection, stream, uri, connection.getContentLengthLong(), this);
                } catch (IOException exception) {
                    if (connection != null) connection.disconnect();
                    check();
                    throw sanitized(exception, stage, uri);
                } catch (IllegalArgumentException exception) {
                    if (connection != null) connection.disconnect();
                    throw error(stage + "：请求字段无效（" + safeHost(uri) + "）");
                }
            }
            throw error(stage + "：重定向失败");
        }

        byte[] readBounded(InputStream input, int limit, String stage) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                check();
                int amount = input.read(buffer);
                if (amount < 0) break;
                if (output.size() + amount > limit) throw error(stage + "：清单过大");
                output.write(buffer, 0, amount);
            }
            return output.toByteArray();
        }
    }

    private static final class Segment {
        final URI uri;
        final File file;
        final boolean encrypted;
        Segment(URI uri, File file, boolean encrypted) { this.uri = uri; this.file = file; this.encrypted = encrypted; }
    }

    private static final class Response {
        final HttpURLConnection connection;
        final InputStream stream;
        final URI uri;
        final long length;
        final Session session;
        Response(HttpURLConnection connection, InputStream stream, URI uri, long length, Session session) {
            this.connection = connection; this.stream = stream; this.uri = uri; this.length = length; this.session = session;
        }
        void close() {
            try { stream.close(); } catch (Exception ignored) { }
            connection.disconnect();
            if (session.active == connection) { session.active = null; session.activeStream = null; }
        }
    }

    private static final class ReportedException extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean retryable;
        ReportedException(String message, boolean retryable) { super(message); this.retryable = retryable; }
    }

    private static IOException sanitized(IOException exception, String stage, URI uri) {
        if (exception instanceof ReportedException) return exception;
        // Networking exceptions may include signed URLs or user data in their messages/causes.
        return retryable(stage + "：" + exception.getClass().getSimpleName() + "（" + safeHost(uri) + "）");
    }

    private static ReportedException error(String message) { return new ReportedException(message, false); }
    private static ReportedException retryable(String message) { return new ReportedException(message, true); }

    private static URI httpUri(String value) throws IOException {
        if (value == null || value.length() == 0 || value.length() > 65536) throw error("播放地址无效");
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() > 65535)
                throw error("只支持无内嵌账号密码的 HTTP/HTTPS 播放地址");
            String raw = uri.toASCIIString();
            int fragment = raw.indexOf('#');
            return new URI(fragment < 0 ? raw : raw.substring(0, fragment));
        } catch (URISyntaxException exception) { throw error("播放地址格式无效"); }
    }

    private static URI resolve(URI base, String reference) throws IOException {
        if (reference == null || reference.length() == 0) throw error("清单或重定向缺少地址");
        try {
            URI relative = new URI(reference);
            // Java URI.resolve historically mishandles query-only references.
            // RFC 3986 retains the base path for "?new-query" and "#fragment".
            if (!relative.isAbsolute() && relative.getRawAuthority() == null
                    && (relative.getRawPath() == null || relative.getRawPath().length() == 0)) {
                String raw = base.toASCIIString();
                int hash = raw.indexOf('#');
                if (hash >= 0) raw = raw.substring(0, hash);
                if (relative.getRawQuery() != null) {
                    int query = raw.indexOf('?');
                    if (query >= 0) raw = raw.substring(0, query);
                    raw += "?" + relative.getRawQuery();
                }
                return httpUri(raw);
            }
            return httpUri(base.resolve(relative).toASCIIString());
        } catch (URISyntaxException exception) { throw error("清单或重定向地址格式无效"); }
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port == -1) port = uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static String safeHost(URI uri) { return uri.getHost() == null ? "服务器" : uri.getHost(); }

    private static String checkedValue(String value, String name) throws IOException {
        if (value == null || value.length() == 0) return null;
        if (value.length() > 16384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\u0000') >= 0)
            throw error(name + " 请求字段无效");
        return value;
    }

    private static String checkedReferrer(String value) throws IOException {
        value = checkedValue(value, "Referer");
        if (value == null) return null;
        return httpUri(value).toASCIIString();
    }

    private static String crossOriginReferrer(String value, URI target) throws IOException {
        URI original = httpUri(value);
        if (original.getScheme().equalsIgnoreCase("https") && target.getScheme().equalsIgnoreCase("http")) return null;
        String host = original.getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        return original.getScheme() + "://" + host + (original.getPort() < 0 ? "" : ":" + original.getPort()) + "/";
    }

    private static LinkedHashMap<String, String> parseHeaders(String[] fields) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        if (fields == null) return result;
        if (fields.length > 128) throw error("播放请求字段过多");
        int total = 0;
        for (String field : fields) {
            if (field == null || field.length() == 0) continue;
            if (field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) throw error("播放请求字段包含非法换行");
            total += field.length();
            if (total > 128 * 1024) throw error("播放请求字段过大");
            int colon = field.indexOf(':');
            if (colon <= 0) throw error("播放请求字段格式无效");
            String name = field.substring(0, colon).trim();
            if (name.length() > 128 || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw error("播放请求字段名称无效");
            String value = checkedValue(field.substring(colon + 1).trim(), name);
            if (value == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("referer")) value = checkedReferrer(value);
            if (lower.equals("host") || lower.equals("content-length") || lower.equals("connection")
                    || lower.equals("transfer-encoding") || lower.equals("range") || lower.equals("if-range")
                    || lower.equals("accept-encoding") || lower.equals("proxy-authorization")) continue;
            String existing = field(result, name);
            if (existing != null && !existing.equals(value)) throw error("播放请求字段存在冲突");
            if (existing == null) result.put(name, value);
        }
        return result;
    }

    private static String field(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return null;
    }

    private static List<String> lines(byte[] data) throws IOException {
        List<String> result = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (result.isEmpty() && line.startsWith("\ufeff")) line = line.substring(1);
                if (line.length() > 0) result.add(line);
            }
        } finally { reader.close(); }
        if (result.isEmpty() || !result.get(0).equals("#EXTM3U")) throw error("无效的 HLS 清单");
        return result;
    }

    private static URI masterVariant(List<String> lines, URI base) throws IOException {
        boolean master = false;
        for (String line : lines) if (line.startsWith("#EXT-X-STREAM-INF:")) master = true;
        if (!master) return null;
        URI selected = null;
        long bandwidth = -1;
        boolean pending = false;
        long current = 0;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    || line.startsWith("#EXT-X-SESSION-KEY:") || line.startsWith("#EXT-X-SESSION-DATA:"))
                throw error("此主清单含独立音轨、字幕或额外会话资源，暂不支持");
            rejectUnsupported(line);
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pending) throw error("主清单缺少线路地址");
                LinkedHashMap<String, String> attrs = attributes(afterColon(line));
                if (attrs.containsKey("AUDIO") || attrs.containsKey("VIDEO") || attrs.containsKey("SUBTITLES")
                        || (attrs.containsKey("CLOSED-CAPTIONS") && !attrs.get("CLOSED-CAPTIONS").equals("NONE")))
                    throw error("只支持音视频合并在同一线路的主清单");
                try { current = Long.parseLong(attrs.get("BANDWIDTH")); }
                catch (Exception exception) { throw error("主清单线路带宽无效"); }
                if (current <= 0) throw error("主清单线路带宽无效");
                pending = true;
            } else if (!line.startsWith("#")) {
                if (!pending) throw error("主清单线路顺序无效");
                URI candidate = resolve(base, line);
                if (current > bandwidth) { selected = candidate; bandwidth = current; }
                pending = false;
            } else if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-KEY:") || line.contains("URI=")) {
                throw error("主清单包含不支持的混合媒体资源");
            }
        }
        if (pending || selected == null) throw error("主清单没有完整媒体线路");
        return selected;
    }

    private static void rejectUnsupported(String line) throws IOException {
        if (line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-BYTERANGE:")
                || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT")
                || line.startsWith("#EXT-X-RENDITION-REPORT") || line.startsWith("#EXT-X-SKIP")
                || line.startsWith("#EXT-X-DEFINE") || line.startsWith("#EXT-X-GAP")
                || line.startsWith("#EXT-X-I-FRAMES-ONLY"))
            throw error("此清单使用暂不支持的初始化片段、字节范围或低延迟特性");
        if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                || line.startsWith("#EXT-X-SESSION-KEY:") || line.startsWith("#EXT-X-SESSION-DATA:"))
            throw error("此清单包含不支持的独立音轨或额外会话资源");
    }

    private static String afterColon(String line) { return line.substring(line.indexOf(':') + 1).trim(); }

    private static LinkedHashMap<String, String> attributes(String text) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        int cursor = 0;
        while (cursor < text.length()) {
            int equals = text.indexOf('=', cursor);
            if (equals < 0) throw error("HLS 属性格式无效");
            String name = text.substring(cursor, equals).trim();
            if (!name.matches("[A-Z0-9-]+") || result.containsKey(name)) throw error("HLS 属性名称无效或重复");
            cursor = equals + 1;
            String value;
            if (cursor < text.length() && text.charAt(cursor) == '"') {
                int end = text.indexOf('"', cursor + 1);
                if (end < 0) throw error("HLS 属性引号未闭合");
                value = text.substring(cursor + 1, end);
                cursor = end + 1;
                if (cursor < text.length() && text.charAt(cursor) != ',') throw error("HLS 属性分隔无效");
            } else {
                int end = text.indexOf(',', cursor);
                if (end < 0) end = text.length();
                value = text.substring(cursor, end).trim();
                cursor = end;
            }
            if (value.length() == 0) throw error("HLS 属性为空");
            result.put(name, value);
            if (cursor < text.length()) {
                cursor++;
                if (cursor == text.length()) throw error("HLS 属性分隔无效");
            }
        }
        return result;
    }

    private static String serialize(LinkedHashMap<String, String> attributes) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            if (result.length() > 0) result.append(',');
            String key = attribute.getKey();
            boolean quoted = key.equals("URI") || key.equals("KEYFORMAT") || key.equals("KEYFORMATVERSIONS");
            result.append(key).append('=');
            if (quoted) result.append('"');
            result.append(attribute.getValue());
            if (quoted) result.append('"');
        }
        return result.toString();
    }
}
