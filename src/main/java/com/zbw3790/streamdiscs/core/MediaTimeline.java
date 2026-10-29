package com.zbw3790.streamdiscs.core;

/** Process-local monotonic timeline. No calendar, TPS, or client observation authority. */
public record MediaTimeline(long anchorNanos,double mediaStart,double duration) {
    public MediaTimeline {
        if(!Double.isFinite(mediaStart)||!Double.isFinite(duration)||mediaStart<0||duration<=0||duration>86400||mediaStart>=duration)throw new IllegalArgumentException("timeline bounds");
    }
    public double position(long now){return mediaStart+(now-anchorNanos)/1_000_000_000.0;}
    public boolean ended(long now){return position(now)>=duration;}
    public boolean active(long now){return position(now)>=0&&!ended(now);}
}
