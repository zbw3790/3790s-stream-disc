package com.zbw3790.streamdiscs.core;
import java.util.*;

/** Server-thread preparation barrier: frozen connection membership, one immutable commit. */
public final class PreparationBarrier {
    public static final long WINDOW_NANOS=4_000_000_000L,GAP_NANOS=200_000_000L;
    public enum Status { NOT_READY,READY,DECLINED }
    private static final class Member {int attempt;long sequence;Status status=Status.NOT_READY;}
    private final Map<UUID,Member> waiting=new LinkedHashMap<>();
    private final long preparedAt,deadline;private Long anchor;private String reason="";
    public PreparationBarrier(long preparedAt,Collection<UUID> connections){
        if(connections.size()>64)throw new IllegalArgumentException("Too many participants");
        this.preparedAt=preparedAt;deadline=Math.addExact(preparedAt,WINDOW_NANOS);
        for(UUID id:connections)waiting.put(Objects.requireNonNull(id),new Member());
    }
    public boolean acknowledge(UUID connection,int attempt,long sequence,Status status){
        if(anchor!=null||attempt<1||attempt>32||sequence<1||sequence>1_000_000_000L||status==null)return false;
        Member m=waiting.get(connection);if(m==null||attempt<m.attempt||(attempt==m.attempt&&sequence<=m.sequence))return false;
        m.attempt=attempt;m.sequence=sequence;m.status=status;
        if(status==Status.DECLINED)waiting.remove(connection);
        return true;
    }
    public boolean retain(Collection<UUID> eligible){return anchor==null&&waiting.keySet().removeIf(id->!eligible.contains(id));}
    public boolean commit(long now){
        if(anchor!=null)return false;
        boolean ready=waiting.values().stream().allMatch(m->m.status==Status.READY);
        if(!ready&&now<deadline-GAP_NANOS)return false;
        anchor=now>=deadline-GAP_NANOS?deadline:now+GAP_NANOS;
        reason=ready?"all_ready":"deadline";return true;
    }
    public long preparedAt(){return preparedAt;}public long deadline(){return deadline;}
    public Long anchor(){return anchor;}public String reason(){return reason;}
    public Set<UUID> members(){return Set.copyOf(waiting.keySet());}
    public long readyCount(){return waiting.values().stream().filter(m->m.status==Status.READY).count();}
}
