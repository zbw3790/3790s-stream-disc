package com.zbw3790.streamdiscs.client;
import com.google.gson.JsonObject;
import com.zbw3790.streamdiscs.*;
import com.zbw3790.streamdiscs.core.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.concurrent.*;

public final class ClientRequests {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("stream_discs");
    private static final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(62),Thread.ofPlatform().daemon().name("stream-discs-info-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private record Job(Cancellation token,Object connection){}
    private static final Map<UUID,Job> jobs=new ConcurrentHashMap<>();
    private static final Map<String,View> views=new LinkedHashMap<>();
    private static final Map<UUID,View> history=new LinkedHashMap<>();
    private static final class Control {long serial,terminalRevision;Control(long serial){this.serial=serial;}}
    private static final Map<String,Control> controlSerials=new LinkedHashMap<>();
    private static long controlFloor;
    private static final Map<UUID,Long> pings=new LinkedHashMap<>();
    private static ClockEstimate estimate=new ClockEstimate();
    private static Object sender;
    private static UUID epoch,connectionId;private static long serial,lastPing;private static Object owner;private static boolean paused;
    private static java.util.function.LongSupplier clock=System::nanoTime;
    // Independent local monotonic lease: mapping loss/correction cannot retain history forever.
    private static java.util.function.LongSupplier historyClock=System::nanoTime;
    private static final long HISTORY_GRACE_NANOS=30_000_000_000L;
    private static final class View {
        final String key;final UUID id,token;long revision;final BilibiliSource.Metadata meta;final BlockPos pos;MediaTimeline timeline;final PreparationState preparation;
        final long durationNanos,retainedUntil;
        int attempts,corrections,launch,reportedLaunch;long retryAt,deadline,lastCorrection,errorSince,statusSequence;boolean running,exhausted,started,prepared;String reportedStatus="";
        View(String k,UUID i,UUID token,long r,BilibiliSource.Metadata m,BlockPos p,MediaTimeline t,PreparationState prep){key=k;id=i;this.token=token;revision=r;meta=m;pos=p;timeline=t;preparation=prep;durationNanos=(long)Math.ceil(m.duration()*1_000_000_000);retainedUntil=historyClock.getAsLong()+PreparationBarrier.WINDOW_NANOS+durationNanos+HISTORY_GRACE_NANOS;}
    }
    public static void init(){
        ClientPlayNetworking.registerGlobalReceiver(Wire.TYPE,(m,c)->{if(c.responseSender()==sender)receive(m);});
        ClientPlayConnectionEvents.JOIN.register((h,s,m)->{cancel();owner=h;sender=s;});
        ClientPlayConnectionEvents.DISCONNECT.register((h,m)->{if(owner==h)cancel();});
    }
    public static void cancel(){for(Job j:jobs.values())j.token.close();jobs.clear();workers.getQueue().clear();for(String key:new ArrayList<>(views.keySet()))StreamDiscsClient.INSTANCE.stopKey(key,"connection_closed");views.clear();history.clear();controlSerials.clear();controlFloor=0;pings.clear();estimate=new ClockEstimate();epoch=connectionId=null;owner=sender=null;serial=lastPing=0;paused=false;}
    /** World replacement is a local cancellation, never a failed acquisition. */
    public static void leaveWorld(){
        var old=new ArrayList<>(views.values());views.clear();
        for(View v:old){v.running=false;v.launch++;StreamDiscsClient.INSTANCE.stopKey(v.key,"world_replaced");}
    }
    private static void send(JsonObject j,Object connection){Minecraft mc=Minecraft.getInstance();mc.execute(()->{if(mc.getConnection()==connection&&connection!=null&&epoch!=null&&connectionId!=null){j.addProperty("epoch",epoch.toString());j.addProperty("connection",connectionId.toString());ClientPlayNetworking.send(Wire.of(j));}});}
    public static void receive(Wire wire){
        long received=clock.getAsLong();
        try{
            JsonObject j=wire.data();if(!Wire.supported(j))return;
            String kind=j.get("kind").getAsString();UUID id=UUID.fromString(j.get("id").getAsString());Minecraft mc=Minecraft.getInstance();Object connection=mc.getConnection();
            UUID incomingEpoch=UUID.fromString(j.get("epoch").getAsString()),incomingConnection=UUID.fromString(j.get("connection").getAsString());
            if(kind.equals("welcome")){
                if(owner!=connection)return;
                if(epoch==null){epoch=incomingEpoch;connectionId=incomingConnection;}else if(!incomingEpoch.equals(epoch)||!incomingConnection.equals(connectionId))return;
                send(Wire.message("hello",id.toString()),connection);return;
            }
            if(connection!=owner||!Wire.envelopeMatches(j,epoch,connectionId))return;
            long order=j.get("serial").getAsLong();
            if(Set.of("snapshot","stop","forget").contains(kind)){
                String key=j.get("key").getAsString();Control control=controlSerials.get(key);if(key.length()>200||order<=controlFloor||control!=null&&order<=control.serial)return;
                if(control==null)control=new Control(order);else control.serial=order;
                controlSerials.remove(key);controlSerials.put(key,control);
                while(controlSerials.size()>256){var entry=controlSerials.entrySet().iterator();var old=entry.next();controlFloor=Math.max(controlFloor,old.getValue().serial);entry.remove();}
            }
            switch(kind){
                case "clock"->{Long t1=pings.remove(id);if(t1!=null&&j.get("t1").getAsLong()==t1)estimate.add(t1,j.get("t2").getAsLong(),j.get("t3").getAsLong(),received);}
                case "cancel"->{Job job=jobs.remove(id);if(job!=null)job.token.close();}
                case "metadata"->{
                    if(jobs.containsKey(id)||jobs.size()>=64)return;MediaTarget target=new MediaTarget(j.get("bvid").getAsString(),j.get("page").getAsInt());
                    Job job=new Job(new Cancellation(),connection);jobs.put(id,job);
                    try{workers.execute(()->query(id,target,job));}catch(RejectedExecutionException e){jobs.remove(id);result(id,"FAILED",1,null,"BUSY",connection);}
                }
                case "snapshot"->{
                    String key=j.get("key").getAsString();if(key.length()>200||!j.get("source").getAsString().equals("bilibili")||j.get("rate").getAsDouble()!=1)return;
                    var meta=new BilibiliSource.Metadata(new MediaTarget(j.get("bvid").getAsString(),j.get("page").getAsInt()),j.get("cid").getAsLong(),Binding.text(j.get("title").getAsString(),256),j.get("duration").getAsDouble());
                    if(meta.cid()<=0||!Double.isFinite(meta.duration())||meta.duration()<=0||meta.duration()>86400)return;BlockPos pos=BlockPos.of(j.get("pos").getAsLong());
                    if(mc.level==null||mc.player==null||!key.equals(mc.level.dimension().identifier()+"/"+pos.asLong())||!mc.level.hasChunkAt(pos)||mc.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>80*80)return;
                    long rev=j.get("revision").getAsLong();if(rev<=0||rev<=controlSerials.get(key).terminalRevision)return;
                    UUID token=UUID.fromString(j.get("token").getAsString()),preparationId=UUID.fromString(j.get("preparation").getAsString());
                    long generation=j.get("prepareRevision").getAsLong(),preparedAt=j.get("preparedAt").getAsLong(),deadline=j.get("deadline").getAsLong();
                    var phase=PreparationState.Phase.valueOf(j.get("state").getAsString());Long anchor=j.has("anchor")?j.get("anchor").getAsLong():null;
                    if(j.has("mediaStart")&&j.get("mediaStart").getAsDouble()!=0)return;
                    MediaTimeline timeline=anchor==null?null:new MediaTimeline(anchor,0,meta.duration());
                    // Reject absurd anchors only after clocks are related; no naked nanoTime comparison.
                    if(timeline!=null&&estimate.ready(received)&&Math.abs(timeline.position(estimate.serverNow(received)))>86410)return;
                    if(estimate.ready(received)&&estimate.serverNow(received)-Math.addExact(anchor==null?deadline:anchor,(long)Math.ceil(meta.duration()*1_000_000_000))>estimate.uncertaintyNanos())return;
                    pruneHistory(received);
                    View old=views.get(key);
                    if(old!=null&&old.id.equals(id)){
                        if(!old.token.equals(token)||!old.meta.equals(meta)||!old.preparation.accept(preparationId,generation,preparedAt,deadline,rev,phase,anchor))return;
                        if(rev!=old.revision)LOG.info("preparation_snapshot session={} revision={} state={} anchor={} nano={} clockReady={}",id,rev,phase,anchor,System.nanoTime(),estimate.ready(received));
                        old.revision=rev;old.timeline=timeline;break;
                    }
                    if(old!=null){if(generation<=old.preparation.generation())return;views.remove(key);history.remove(old.id);old.running=false;StreamDiscsClient.INSTANCE.stopKey(key,"new_global_session");}
                    if(views.size()>=128)return;
                    View known=history.get(id);
                    if(known==null){if(history.size()>=256)return;var prep=new PreparationState(preparationId,generation,preparedAt,deadline);if(!prep.accept(preparationId,generation,preparedAt,deadline,rev,phase,anchor))return;known=new View(key,id,token,rev,meta,pos,timeline,prep);history.put(id,known);}
                    else if(!known.key.equals(key)||!known.token.equals(token)||!known.meta.equals(meta)||!known.preparation.accept(preparationId,generation,preparedAt,deadline,rev,phase,anchor))return;
                    known.revision=rev;known.timeline=timeline;
                    known.running=false;views.put(key,known);LOG.info("preparation_snapshot session={} revision={} state={} anchor={} nano={} clockReady={}",id,rev,phase,anchor,System.nanoTime(),estimate.ready(received));
                }
                case "stop","forget"->{
                    String key=j.get("key").getAsString();View v=history.get(id);
                    if(v!=null&&v.key.equals(key)&&j.get("revision").getAsLong()>=v.revision){
                        if(kind.equals("stop")){history.remove(id,v);Control control=controlSerials.get(key);control.terminalRevision=Math.max(control.terminalRevision,j.get("revision").getAsLong());}
                        if(views.get(key)==v){views.remove(key);v.running=false;StreamDiscsClient.INSTANCE.stopKey(key,kind);}
                    }
                }
                default->{}
            }
        }catch(RuntimeException e){LOG.warn("Rejected malformed client media-v3 message");}
    }
    private static double target(View v){long n=clock.getAsLong();return v.timeline==null||!estimate.ready(n)?Double.NaN:v.timeline.position(estimate.serverNow(n));}
    /** D+duration is the latest possible end even if this listener never got START. */
    private static boolean mediaEnded(View v,long local){return estimate.ready(local)&&estimate.serverNow(local)-Math.addExact(v.timeline==null?v.preparation.deadline():v.timeline.anchorNanos(),v.durationNanos)>estimate.uncertaintyNanos();}
    private static void pruneHistory(long local){
        long retainedNow=historyClock.getAsLong();
        // Only detached entries. Their fixed first-receipt lease is never renewed by snapshots/forget.
        // Control serials/floor stay intact, independently of media and budget reclamation.
        history.values().removeIf(v->views.get(v.key)!=v&&(retainedNow-v.retainedUntil>=0||mediaEnded(v,local)));
    }
    private static void preparationStatus(View v,String status){
        if(v.timeline!=null||v.launch<1||status.equals(v.reportedStatus)&&v.reportedLaunch==v.launch)return;
        v.reportedStatus=status;v.reportedLaunch=v.launch;
        JsonObject j=Wire.message("preparation_status",v.id.toString());j.addProperty("key",v.key);j.addProperty("token",v.token.toString());j.addProperty("preparation",v.preparation.preparation().toString());j.addProperty("prepareRevision",v.preparation.generation());j.addProperty("attempt",v.launch);j.addProperty("sequence",++v.statusSequence);j.addProperty("status",status);send(j,owner);
        LOG.info("preparation_send session={} status={} attempt={} sequence={} nano={}",v.id,status,v.launch,v.statusSequence,System.nanoTime());
    }
    private static void start(View v,boolean correction){
        long now=System.nanoTime();if(v.deadline==0)v.deadline=now+30_000_000_000L;v.running=true;v.started=false;v.prepared=false;
        Object connection=owner;int launch=++v.launch;
        java.util.function.Consumer<String> callback=event->{
            if(views.get(v.key)!=v||v.launch!=launch||Minecraft.getInstance().getConnection()!=connection)return;
            if(event.equals("READY")){v.prepared=true;return;}
            if(event.equals("DECLINED")){v.running=false;v.exhausted=true;preparationStatus(v,"DECLINED");return;}
            JsonObject m=Wire.message("playback",v.id.toString());m.addProperty("key",v.key);m.addProperty("revision",v.revision);m.addProperty("event",event);send(m,connection);
            if(event.equals("STARTED")){v.started=true;v.deadline=0;}
            if(event.equals("FAILED")||event.equals("ENDED")){
                v.prepared=false;preparationStatus(v,"NOT_READY");v.running=false;v.attempts++;long n=System.nanoTime();if(v.deadline==0)v.deadline=n+30_000_000_000L;
                if(v.attempts>=3||n>=v.deadline||target(v)>=v.meta.duration())v.exhausted=true;
                else v.retryAt=n+(v.attempts==1?1_000_000_000L:3_000_000_000L);
            }
        };
        if(v.timeline==null)StreamDiscsClient.INSTANCE.prepareSynced(v.key,v.meta,v.pos.getX()+.5,v.pos.getY()+.5,v.pos.getZ()+.5,()->target(v),callback);
        else StreamDiscsClient.INSTANCE.playSynced(v.key,v.meta,v.pos.getX()+.5,v.pos.getY()+.5,v.pos.getZ()+.5,()->target(v),callback);
        if(v.running)preparationStatus(v,"NOT_READY");
    }
    public static void tick(){
        Minecraft mc=Minecraft.getInstance();long local=clock.getAsLong(),n=System.nanoTime();
        if(owner!=null&&mc.getConnection()!=owner){cancel();return;}if(owner==null)return;
        pruneHistory(local);
        if(mc.isPaused()){
            if(!paused){paused=true;for(View v:views.values()){preparationStatus(v,"NOT_READY");v.running=false;v.started=false;v.prepared=false;v.launch++;StreamDiscsClient.INSTANCE.stopKey(v.key,"local_pause");}}
            return;
        }
        if(paused){paused=false;estimate=new ClockEstimate();pings.clear();lastPing=0;}
        if(local-lastPing>=2_000_000_000L||lastPing==0){lastPing=local;UUID id=UUID.randomUUID();pings.put(id,local);while(pings.size()>4)pings.remove(pings.keySet().iterator().next());JsonObject m=Wire.message("clock",id.toString());m.addProperty("t1",local);send(m,owner);}
        for(View v:new ArrayList<>(views.values())){
            if(mc.player==null||mc.level==null||!v.key.equals(mc.level.dimension().identifier()+"/"+v.pos.asLong())||!mc.level.hasChunkAt(v.pos)||mc.player.distanceToSqr(v.pos.getX()+.5,v.pos.getY()+.5,v.pos.getZ()+.5)>80*80){preparationStatus(v,"DECLINED");views.remove(v.key);v.running=false;StreamDiscsClient.INSTANCE.stopKey(v.key,"local_range");continue;}
            if(v.running&&!v.started&&v.deadline!=0&&n>=v.deadline){v.exhausted=true;v.running=false;StreamDiscsClient.INSTANCE.stopKey(v.key,"recovery_deadline");continue;}
            if(v.prepared)preparationStatus(v,estimate.ready(local)&&StreamDiscsClient.INSTANCE.prepared(v.key)?"READY":"NOT_READY");
            if(!estimate.ready(local))continue;
            if(mediaEnded(v,local)||target(v)>=v.meta.duration()){views.remove(v.key);history.remove(v.id);v.running=false;StreamDiscsClient.INSTANCE.stopKey(v.key,"timeline_end");continue;}
            if(!v.running&&!v.exhausted&&n>=v.retryAt){start(v,false);continue;}
            if(v.timeline==null)continue;
            double actual=StreamDiscsClient.INSTANCE.position(v.key);
            boolean outside=v.running&&Double.isFinite(actual)&&target(v)>0&&Math.abs(actual-target(v))>.15;
            if(!outside)v.errorSince=0;
            else if(v.errorSince==0)v.errorSince=n;
            if(outside&&n-v.errorSince>=500_000_000L&&n-v.lastCorrection>5_000_000_000L){
                if(v.corrections>=12){v.exhausted=true;v.running=false;StreamDiscsClient.INSTANCE.stopKey(v.key,"correction_budget");continue;}
                v.corrections++;v.lastCorrection=n;v.errorSince=0;LOG.info("sync_correct key={} error={} correction={}",v.key,actual-target(v),v.corrections);start(v,true);
            }
        }
    }
    private static void query(UUID id,MediaTarget target,Job job){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        try{
            for(int attempt=1;attempt<=3;attempt++){
                job.token.check();
                try{
                    var meta=ClientMediaAccess.provider.metadata(target,job.token);job.token.check();
                    result(id,"READY",attempt,meta,"",job.connection);return;
                }catch(java.io.IOException e){
                    job.token.check();boolean transientFailure=e.getMessage()!=null&&e.getMessage().startsWith("NETWORK:");
                    if(!transientFailure||attempt==3||System.nanoTime()>deadline){result(id,"FAILED",attempt,null,transientFailure?"NETWORK：读取失败":"SOURCE：媒体信息不可用",job.connection);return;}
                    result(id,"RETRYING",attempt,null,"",job.connection);
                    long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(attempt==1?1000:3000);
                    while(System.nanoTime()<until){job.token.check();Thread.sleep(100);}
                }
            }
        }catch(InterruptedException|java.io.InterruptedIOException cancelled){Thread.currentThread().interrupt();}
        catch(Exception e){if(!job.token.cancelled())result(id,"FAILED",1,null,"SOURCE：无效媒体信息",job.connection);}
        finally{jobs.remove(id,job);job.token.close();}
    }
    private static void result(UUID id,String state,int attempt,BilibiliSource.Metadata meta,String error,Object connection){
        JsonObject j=Wire.message("metadata_result",id.toString());j.addProperty("state",state);j.addProperty("attempt",attempt);
        if(meta!=null){j.addProperty("cid",meta.cid());j.addProperty("duration",meta.duration());j.addProperty("title",Binding.text(meta.title(),256));}else j.addProperty("error",error);
        send(j,connection);
    }
}
