package com.zbw3790.streamdiscs.core;
import java.util.*;

/** Client snapshot transition guard; preparation identity and deadline never change. */
public final class PreparationState {
    public enum Phase { PREPARING,SCHEDULED,PLAYING }
    private final UUID preparation;private final long generation,preparedAt,deadline;
    private long revision;private Phase phase;private Long anchor;
    public PreparationState(UUID preparation,long generation,long preparedAt,long deadline){
        this.preparation=Objects.requireNonNull(preparation);if(generation<=0||deadline!=Math.addExact(preparedAt,PreparationBarrier.WINDOW_NANOS))throw new IllegalArgumentException("Invalid preparation");
        this.generation=generation;this.preparedAt=preparedAt;this.deadline=deadline;
    }
    public boolean accept(UUID id,long generation,long preparedAt,long deadline,long revision,Phase phase,Long anchor){
        if(!preparation.equals(id)||this.generation!=generation||this.preparedAt!=preparedAt||this.deadline!=deadline||revision<generation||revision<this.revision||phase==null)return false;
        if((phase==Phase.PREPARING)!=(anchor==null))return false;
        if(anchor!=null&&(anchor<preparedAt||anchor>deadline))return false;
        if(this.phase!=null&&(phase.ordinal()<this.phase.ordinal()||this.anchor!=null&&!this.anchor.equals(anchor)))return false;
        if(revision==this.revision&&this.phase!=null&&(this.phase!=phase||!Objects.equals(this.anchor,anchor)))return false;
        this.revision=revision;this.phase=phase;this.anchor=anchor;return true;
    }
    public UUID preparation(){return preparation;}public long generation(){return generation;}
    public long preparedAt(){return preparedAt;}public long deadline(){return deadline;}
    public long revision(){return revision;}public Long anchor(){return anchor;}public Phase phase(){return phase;}
}
