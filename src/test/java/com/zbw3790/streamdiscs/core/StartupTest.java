package com.zbw3790.streamdiscs.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteOrder;

public final class StartupTest {
    private PcmPipe pipe(double first)throws Exception{
        PcmPipe p=new PcmPipe();p.sampleRate=48000;p.firstPts=first;
        byte[] pcm=new byte[384000];java.nio.ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).putShort((short)12345);
        p.put(pcm,0,pcm.length,new Cancellation());return p;
    }
    @Test void preserveOpeningIncludingLastTwoSeconds()throws Exception{
        for(double signed:new double[]{-6,-2,-1.99,-.5,-.001,0}){
            assertTrue(StartupPolicy.fromOpening(signed));assertEquals(0,StartupPolicy.decodeTarget(signed));
            PcmPipe p=pipe(0);assertTrue(StartupPolicy.align(p,true,signed));assertEquals(0,p.firstPts);assertEquals(384000,p.queuedBytes());
            assertEquals(12345,p.read(2).order(ByteOrder.LITTLE_ENDIAN).getShort());
            assertEquals(signed==0,StartupPolicy.canOutput(signed,0));p.close();
        }
        // Baseline near-start +2 policy selects 1.5s: the opening marker cannot exist in that PCM.
        PcmPipe legacy=pipe(Math.max(0,-.5+2));legacy.advanceTo(Math.max(0,-.2+.10));assertEquals(1.5,legacy.firstPts);
        System.out.println("BASELINE_COUNTEREXAMPLE nearStart=-0.5 selectedFirstPts="+legacy.firstPts+" missingOpeningMs=1500");
    }
    @Test void readinessCrossingAnchorAndLateJoin()throws Exception{
        assertEquals(2_000_000_000L,StartupPolicy.LEAD_NANOS);
        for(double at:new double[]{-.01,0,.05,.1}){PcmPipe p=pipe(0);assertTrue(StartupPolicy.align(p,true,at));assertEquals(0,p.firstPts);}
        PcmPipe slow=pipe(0);assertTrue(StartupPolicy.align(slow,true,1.5));assertEquals(1.6,slow.firstPts,1.0/48000);assertFalse(StartupPolicy.canOutput(1.5,slow.firstPts));assertTrue(StartupPolicy.canOutput(1.61,slow.firstPts));
        assertFalse(StartupPolicy.fromOpening(30));assertEquals(32,StartupPolicy.decodeTarget(30));
        PcmPipe late=pipe(32);assertTrue(StartupPolicy.align(late,false,33));assertEquals(33.1,late.firstPts,1.0/48000);
        assertFalse(StartupPolicy.align(late,false,80));
    }
    @Test void invalidClockAndCancelledPipe()throws Exception{
        for(double n:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->StartupPolicy.decodeTarget(n));
        ClockEstimate c=new ClockEstimate();assertFalse(c.ready(100));
        PcmPipe old=pipe(0);old.close();assertFalse(StartupPolicy.align(old,true,1));assertEquals(0,old.queuedBytes());
    }
}
