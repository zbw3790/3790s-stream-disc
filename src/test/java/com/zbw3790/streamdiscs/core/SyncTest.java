package com.zbw3790.streamdiscs.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public final class SyncTest {
    @Test void independentClocksAndScheduling(){
        ClockEstimate c=new ClockEstimate();long t=8_000_000_000L,offset=37_000_000_000L;
        assertTrue(c.add(t,t+offset+100_000_000,t+offset+130_000_000,t+230_000_000));
        assertEquals(offset,c.offsetNanos(),1);assertEquals(100_000_000,c.uncertaintyNanos());
        for(int i=1;i<=200;i++){
            long at=t+i*100_000_000L,delay=(75+i%51)*1_000_000L;
            assertTrue(c.add(at,at+offset+delay,at+offset+delay+50_000_000,at+delay*2+50_000_000));
            assertEquals(at+offset,c.serverNow(at),1);
        }
        assertFalse(c.add(t,t+1,t-1,t+2));assertFalse(c.add(t,t+1,t+2,t+3_000_000_000L));
        assertFalse(c.add(t,t+50,t+150,t+70));
        assertFalse(c.ready(t+100_000_000_000L));
    }
    @Test void timeIsNeitherTpsNorCalendar(){
        MediaTimeline t=new MediaTimeline(100_000_000_000L,0,700);
        assertEquals(-6,t.position(94_000_000_000L));assertFalse(t.active(99_000_000_000L));
        assertTrue(t.active(100_000_000_000L));assertEquals(300,t.position(400_000_000_000L));
        assertTrue(t.ended(800_000_000_000L));
        for(double n:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,86401})assertThrows(IllegalArgumentException.class,()->new MediaTimeline(0,0,n));
        assertThrows(IllegalArgumentException.class,()->new MediaTimeline(0,4,4));
    }
    @Test void stalePrebufferAndUnderrunMapping()throws Exception{
        PcmPipe p=new PcmPipe();p.sampleRate=48000;p.firstPts=30;
        Cancellation c=new Cancellation();p.put(new byte[192000],0,192000,c);
        assertTrue(p.advanceTo(31));assertEquals(96000,p.queuedBytes());assertFalse(p.advanceTo(40));
        p.read(32768);assertEquals(31.125,p.positionAt(.125),1e-6);
        while(p.queuedBytes()>0)p.read(32768);
        p.read(32768);assertEquals(32,p.positionAt(1.2),1e-6);
        assertTrue(p.silenceFrames>0);p.close();assertEquals(0,p.queuedBytes());
    }
}
