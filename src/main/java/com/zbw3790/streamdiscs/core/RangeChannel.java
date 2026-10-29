package com.zbw3790.streamdiscs.core;

import org.jcodec.common.io.SeekableByteChannel;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;

public final class RangeChannel implements SeekableByteChannel {
    public static final int BLOCK = 128 * 1024;
    private final SafeHttp http;
    private final URI uri;
    private final Cancellation token;
    private byte[] cache = new byte[0];
    private long cacheStart = -1, position, size = -1;
    public long bytesRead, requests;
    private boolean open = true;
    public RangeChannel(SafeHttp http, URI uri, Cancellation token) throws IOException {
        this.http = http; this.uri = uri; this.token = token;
        fetch(0);
    }
    private void fetch(long start) throws IOException {
        token.check(); if (!open) throw new IOException("CANCELLED");
        int length = (int) (size < 0 ? BLOCK : Math.min(BLOCK, size - start));
        SafeHttp.Response response = http.get(uri, start, length, token);
        if (size >= 0 && size != response.total()) throw new IOException("SOURCE: size changed");
        size = response.total(); cache = response.bytes(); cacheStart = start;
        bytesRead += cache.length; requests++;
    }
    public byte[] at(long offset, int length) throws IOException {
        if (length < 0 || length > 2 * 1024 * 1024 || offset < 0 || offset > size - length) throw new IOException("FORMAT: box bounds");
        setPosition(offset); ByteBuffer result = ByteBuffer.allocate(length);
        while (result.hasRemaining()) if (read(result) < 0) throw new EOFException("FORMAT: truncated media");
        return result.array();
    }
    @Override public int read(ByteBuffer dst) throws IOException {
        token.check(); if (!open) throw new IOException("CANCELLED");
        if (position == size) return -1;
        if (!dst.hasRemaining()) return 0;
        if (position < cacheStart || position >= cacheStart + cache.length) fetch(position);
        int count = (int) Math.min(dst.remaining(), cacheStart + cache.length - position);
        if (count <= 0) throw new IOException("SOURCE: empty range");
        dst.put(cache, (int) (position-cacheStart), count); position += count; return count;
    }
    @Override public long position() { return position; }
    @Override public RangeChannel setPosition(long next) throws IOException {
        if (next < 0 || next > size) throw new IOException("FORMAT: invalid position"); position = next; return this;
    }
    @Override public long size() { return size; }
    @Override public boolean isOpen() { return open; }
    @Override public void close() { open = false; cache = new byte[0]; }
    @Override public int write(ByteBuffer src) throws IOException { throw new IOException("read only"); }
    @Override public RangeChannel truncate(long size) throws IOException { throw new IOException("read only"); }
}
