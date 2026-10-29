package com.zbw3790.streamdiscs.core;

import java.util.ArrayDeque;
/** Four timestamps; server scheduling is removed from RTT. Origin is never shared. */
public final class ClockEstimate {
    private record Sample(long at,long rtt,double offset){}
    private final ArrayDeque<Sample> samples=new ArrayDeque<>();
    private double offset;private long last;
    public boolean add(long t1,long t2,long t3,long t4){
        long elapsed=t4-t1,service=t3-t2;
        if(elapsed<0||elapsed>2_000_000_000L||service<0||service>1_000_000_000L||service>elapsed)return false;
        long rtt=elapsed-service;double o=((double)(t2-t1)+(t3-t4))/2;
        samples.addLast(new Sample(t4,rtt,o));while(samples.size()>16)samples.removeFirst();
        while(!samples.isEmpty()&&t4-samples.getFirst().at>40_000_000_000L)samples.removeFirst();
        Sample best=samples.stream().min(java.util.Comparator.comparingLong(Sample::rtt)).orElseThrow();
        offset=best.offset;last=t4;return true;
    }
    public boolean ready(long now){return !samples.isEmpty()&&now-last<12_000_000_000L;}
    public long serverNow(long local){return local+Math.round(offset);}
    public double offsetNanos(){return offset;}
    public long uncertaintyNanos(){return samples.stream().mapToLong(Sample::rtt).min().orElse(4_000_000_000L)/2;}
}
