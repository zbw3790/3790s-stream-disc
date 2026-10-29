package com.zbw3790.streamdiscs.core;

/** Signed timeline positions distinguish scheduled opening from late catch-up. */
public final class StartupPolicy {
    public static final long LEAD_NANOS=2_000_000_000L;
    private static final double OPENING_GRACE=.10,CATCHUP_PREFETCH=2,READY_AHEAD=.10;
    private StartupPolicy(){}
    public static boolean fromOpening(double position){valid(position);return position<=0;}
    public static double decodeTarget(double position){valid(position);return position<=0?0:position+CATCHUP_PREFETCH;}
    public static double readyTarget(boolean opening,double position){valid(position);return opening&&position<=OPENING_GRACE?0:Math.max(0,position+READY_AHEAD);}
    public static boolean canOutput(double position,double firstPts){valid(position);return position>=0&&position>=firstPts-.02;}
    /** If the game thread was delayed after ready, do not release stale opening PCM. */
    public static boolean align(PcmPipe pipe,boolean opening,double position){
        double wanted=readyTarget(opening,position);
        return wanted<=pipe.firstPts||pipe.advanceTo(wanted);
    }
    private static void valid(double n){if(!Double.isFinite(n))throw new IllegalArgumentException("Invalid timeline position");}
}
