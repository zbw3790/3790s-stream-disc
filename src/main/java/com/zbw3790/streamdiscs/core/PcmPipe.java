package com.zbw3790.streamdiscs.core;

import java.io.InterruptedIOException;
import java.nio.*;

/** Single producer, nonblocking consumer, fixed eight-second PCM ring at 48 kHz. */
public final class PcmPipe {
    public static final int CAPACITY=768000;
    private final byte[] ring=new byte[CAPACITY];
    private int read,write,size;
    private boolean done,closed;
    public volatile int sampleRate;
    public volatile long consumedFrames, silenceFrames;
    public volatile double firstPts=Double.NaN;
    private record Segment(long output,long media,int actual,int total){}
    private final java.util.ArrayDeque<Segment> segments=new java.util.ArrayDeque<>();
    private long outputFrames;
    /** Before audio submission only: discard stale media, never manufacture samples. */
    public synchronized boolean advanceTo(double target){
        if(sampleRate<=0||!Double.isFinite(firstPts)||!Double.isFinite(target))return false;
        long frames=Math.max(0,(long)Math.ceil((target-firstPts)*sampleRate));
        if(frames>size/2)return false;
        int bytes=(int)frames*2;read=(read+bytes)%ring.length;size-=bytes;firstPts+=frames/(double)sampleRate;notifyAll();return true;
    }
    /** AL output offset includes underrun zeroes; map them to frozen media time. */
    public synchronized double positionAt(double outputSeconds){
        if(sampleRate<=0||segments.isEmpty())return Double.NaN;
        double frame=outputSeconds*sampleRate;Segment found=null;
        for(Segment s:segments){if(s.output<=frame)found=s;else break;}
        if(found==null)return Double.NaN;
        while(segments.size()>1&&segments.getFirst()!=found)segments.removeFirst();
        return firstPts+(found.media+Math.max(0,Math.min(found.actual,frame-found.output)))/sampleRate;
    }
    public synchronized int queuedBytes(){return size;}
    public synchronized boolean drained(){return (closed||done)&&size==0;}
    public synchronized void put(byte[] bytes, int offset, int length, Cancellation token) throws InterruptedIOException {
        try {
            while(length>0) {
                token.check();if(closed)throw new InterruptedIOException("CANCELLED");
                while(size==ring.length&&!closed){wait(50);token.check();}
                if(closed)throw new InterruptedIOException("CANCELLED");
                int count=Math.min(length,Math.min(ring.length-size,ring.length-write));
                System.arraycopy(bytes,offset,ring,write,count);write=(write+count)%ring.length;size+=count;offset+=count;length-=count;
                notifyAll();
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("CANCELLED");}
    }
    public synchronized ByteBuffer read(int requested) {
        int wanted=Math.min(Math.max(0,requested&~1),32768);
        if(closed || (done&&size==0)) return ByteBuffer.allocateDirect(0);
        int count=Math.min(wanted,size)&~1;
        int output=done?count:wanted;
        ByteBuffer out=ByteBuffer.allocateDirect(output).order(ByteOrder.LITTLE_ENDIAN);
        int first=Math.min(count,ring.length-read);out.put(ring,read,first);
        if(count>first)out.put(ring,0,count-first);
        read=(read+count)%ring.length;size-=count;
        segments.addLast(new Segment(outputFrames,consumedFrames,count/2,output/2));
        while(segments.size()>64)segments.removeFirst();outputFrames+=output/2;
        consumedFrames+=count/2;silenceFrames+=(output-count)/2;
        while(out.position()<output)out.put((byte)0);
        out.flip();notifyAll();return out;
    }
    public synchronized void finish(){done=true;notifyAll();}
    public synchronized void close(){closed=true;size=0;notifyAll();}
}
