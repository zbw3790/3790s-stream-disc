package com.zbw3790.streamdiscs.core;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class SafeHttp {
    public static final int CONNECT_MS = 8000, READ_MS = 8000, REDIRECTS = 3, ATTEMPTS = 3;
    private final int testPort;
    private final Consumer<String> audit;
    private int attempts = ATTEMPTS;
    public SafeHttp(Consumer<String> audit) { this(-1, audit); }
    public SafeHttp(Consumer<String> audit, int attempts) { this(audit); if(attempts<1||attempts>ATTEMPTS)throw new IllegalArgumentException();this.attempts=attempts; }
    SafeHttp(int testPort, Consumer<String> audit) { this.testPort = testPort; this.audit = audit; }
    public record Response(byte[] bytes, long total, int status) {}
    public static boolean publicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (bytes.length == 16) return (bytes[0] & 0xe0) == 0x20;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a != 0 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 192 && (b == 0 || b == 2))
                && !(a == 198 && (b == 18 || b == 19 || b == 51)) && !(a == 203 && b == 0);
    }
    public void validate(URI uri) throws IOException {
        String host = uri.getHost();
        if (testPort > 0 && "http".equals(uri.getScheme()) && "127.0.0.1".equals(host) && uri.getPort() == testPort && uri.getUserInfo() == null) return;
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.equals("api.bilibili.com") || host.equals("upos-hz-mirrorakam.akamaized.net") || host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn")))
            throw new IOException("INPUT: destination rejected");
        FutureTask<InetAddress[]> lookup = new FutureTask<>(() -> InetAddress.getAllByName(host));
        Thread.ofVirtual().name("stream-discs-dns").start(lookup);
        try {
            for (InetAddress ip : lookup.get(5, TimeUnit.SECONDS)) if (!publicAddress(ip)) throw new IOException("INPUT: non-public address rejected");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("CANCELLED"); }
        catch (ExecutionException | TimeoutException e) { lookup.cancel(true); throw new IOException("NETWORK: DNS failed", e); }
    }
    public Response get(URI initial, long start, int length, Cancellation token) throws IOException {
        if (length < 1 || length > 2 * 1024 * 1024) throw new IOException("FORMAT: response bound exceeded");
        IOException failure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            token.check();
            try { return request(initial, start, length, token); }
            catch (IOException e) {
                failure = e; token.check();
                if (!e.getMessage().startsWith("NETWORK:")) throw e;
                audit.accept("http_retry attempt=" + attempt + " category=NETWORK");
            }
        }
        throw new IOException("NETWORK: retry limit exhausted", failure);
    }
    private Response request(URI initial, long start, int length, Cancellation token) throws IOException {
        URI uri = initial;
        for (int redirect = 0; redirect <= REDIRECTS; redirect++) {
            token.check(); validate(uri);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(CONNECT_MS); connection.setReadTimeout(READ_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "StreamDiscs-M0A/0.1");
            connection.setRequestProperty("Referer", "https://www.bilibili.com/");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (start >= 0) connection.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
            Closeable release = connection::disconnect; token.add(release);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    if (redirect == REDIRECTS) throw new IOException("SOURCE: redirect limit exceeded");
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("SOURCE: missing redirect");
                    uri = uri.resolve(location); continue;
                }
                if (status >= 500 || status == 408) throw new IOException("NETWORK: transient HTTP " + status);
                if (start >= 0 && status != 206) throw new IOException("SOURCE: Range support required, HTTP " + status);
                if (start < 0 && status != 200) throw new IOException("SOURCE: HTTP " + status);
                long total = -1;
                int expected = -1;
                if (start >= 0) {
                    String range = connection.getHeaderField("Content-Range");
                    if (range == null || !range.matches("bytes [0-9]+-[0-9]+/[0-9]+")) throw new IOException("SOURCE: invalid Content-Range");
                    String[] parts = range.substring(6).split("[-/]");
                    long first = Long.parseLong(parts[0]), last = Long.parseLong(parts[1]); total = Long.parseLong(parts[2]);
                    if (first != start || last < first || last >= total || last - first + 1 > length || total > 512L * 1024 * 1024)
                        throw new IOException("SOURCE: inconsistent range");
                    expected = Math.toIntExact(last - first + 1);
                }
                long declared = connection.getContentLengthLong();
                if (declared > length || (expected >= 0 && declared >= 0 && declared != expected)) throw new IOException("SOURCE: oversized response");
                try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    token.add(stream);
                    try {
                        byte[] buffer = new byte[8192]; int count;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                        while ((count = stream.read(buffer, 0, Math.min(buffer.length, length + 1 - out.size()))) != -1) {
                            token.check();
                            if (System.nanoTime() > deadline) throw new IOException("NETWORK: response deadline");
                            out.write(buffer, 0, count);
                            if (out.size() > length) throw new IOException("SOURCE: response bound exceeded");
                        }
                        if (expected >= 0 && out.size() != expected) throw new IOException("NETWORK: truncated range");
                        audit.accept("http status=" + status + " rangeStart=" + start + " bytes=" + out.size());
                        return new Response(out.toByteArray(), total, status);
                    } finally { token.remove(stream); }
                }
            } catch (SocketTimeoutException e) { throw new IOException("NETWORK: timeout", e); }
            catch (ConnectException | UnknownHostException e) { throw new IOException("NETWORK: connection failed", e); }
            catch (IOException e) {
                token.check();
                String message=e.getMessage();
                if(message!=null&&(message.startsWith("NETWORK:")||message.startsWith("SOURCE:")||message.startsWith("FORMAT:")||message.startsWith("INPUT:")))throw e;
                throw new IOException("NETWORK: transport "+e.getClass().getSimpleName(),e);
            }
            finally { token.remove(release); connection.disconnect(); }
        }
        throw new IOException("SOURCE: redirect limit");
    }
}
