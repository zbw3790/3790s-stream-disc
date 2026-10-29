package com.zbw3790.streamdiscs.core;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

public final class Cancellation implements Closeable {
    private final List<Closeable> resources = new ArrayList<>();
    private volatile boolean cancelled;
    public void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
    }
    public synchronized void add(Closeable resource) throws InterruptedIOException {
        if (cancelled) { try { resource.close(); } catch (Exception ignored) {} check(); }
        resources.add(resource);
    }
    public synchronized void remove(Closeable resource) { resources.remove(resource); }
    public boolean cancelled() { return cancelled; }
    @Override public void close() {
        List<Closeable> snapshot;
        synchronized (this) { if (cancelled) return; cancelled = true; snapshot = new ArrayList<>(resources); resources.clear(); }
        // Network shutdown never runs on the game or sound thread.
        Thread.ofVirtual().name("stream-discs-cancel").start(() -> {
            for (Closeable resource : snapshot) try { resource.close(); } catch (Exception ignored) {}
        });
    }
}
