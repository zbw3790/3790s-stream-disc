package com.zbw3790.streamdiscs.client;

import com.zbw3790.streamdiscs.core.*;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.ChannelAccess;
import com.mojang.blaze3d.audio.Channel;
import com.zbw3790.streamdiscs.mixin.SoundManagerAccessor;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.slf4j.*;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public final class StreamDiscsClient implements ClientModInitializer {
    public static final StreamDiscsClient INSTANCE=new StreamDiscsClient();
    private static final Logger LOG=LoggerFactory.getLogger("stream_discs");
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),
            Thread.ofPlatform().daemon().name("stream-discs-media-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private long generation;
    private volatile Session current;
    private final java.util.Map<Long,Session> sessions=new ConcurrentHashMap<>();
    private final java.util.Map<String,Session> keyed=new java.util.HashMap<>();
    private volatile Object world;
    private int ticks;
    private static final class Session {
        final long generation;
        String key="dev";
        BilibiliSource.Metadata metadata;
        java.util.function.Consumer<String> callback=e->{};
        int missingTicks;
        boolean started,buffered,fromOpening,preparing,reservationClaimed;
        ChannelAccess.ChannelHandle reservation;
        java.util.function.DoubleSupplier timeline;
        volatile double observedPosition=Double.NaN;
        volatile long observedAt;
        volatile boolean observedPlaying;
        boolean terminal;
        volatile boolean decoded;
        final Cancellation cancellation=new Cancellation();
        final PcmPipe pipe=new PcmPipe();
        final String input;
        final double target,x,y,z;
        volatile String state="RESOLVING";
        volatile long cid;
        volatile double duration;
        volatile double playedSeconds;
        volatile String channel="NOT_ATTACHED";
        Future<?> job;
        PositionSound sound;
        Session(long id,String input,double target,double x,double y,double z){generation=id;this.input=input;this.target=target;this.x=x;this.y=y;this.z=z;}
    }
    @Override public void onInitializeClient(){ItemCapabilityClient.init();ClientRequests.init();LOG.info("Stream Disc client initialized; decoder=JCodec/JAAD 0.2.5; decoders<=2; metadata workers<=2");}
    public boolean command(String text) {
        switch(LocalCommand.parse(text)) {
            case NONE -> {return false;}
            case STOP -> {stop("command");message("已停止本客户端音源");}
            case STATUS -> message(status());
            case HELP -> message("/sd status 查看本客户端状态；/sd stop 停止本客户端音源。唱片请通过铁砧绑定后放入唱片机。");
        }
        return true;
    }
    /** Nonautomatic integration seam. No release command or startup path calls it. */
    public void play(String input,double target,double x,double y,double z) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null)throw new IllegalArgumentException("请先进入世界");
        stopKey("dev","replace");world=mc.level;
        if(sessions.size()>=2){message("音源繁忙：最多同时播放两张唱片");return;}
        Session session=new Session(++generation,input,target,x,y,z);current=session;sessions.put(session.generation,session);keyed.put(session.key,session);
        LOG.info("session={} stage=RESOLVING target={} position={},{},{}",session.generation,target,x,y,z);
        try {session.job=workers.submit(()->run(session));}
        catch(RejectedExecutionException e){terminate(session,"FAILED","queue_rejected");message("旧网络操作正在结束，请稍后重试");}
    }
    public void playBound(String key,BilibiliSource.Metadata meta,double x,double y,double z,java.util.function.Consumer<String> callback){
        stopKey(key,"replace");
        if(sessions.size()>=2){callback.accept("FAILED");message("音源繁忙：最多同时播放两张唱片");return;}
        world=Minecraft.getInstance().level;
        Session session=new Session(++generation,meta.target().bvid(),0,x,y,z);session.key=key;session.metadata=meta;session.callback=callback;
        sessions.put(session.generation,session);keyed.put(key,session);current=session;
        try{session.job=workers.submit(()->run(session));}catch(RejectedExecutionException e){terminate(session,"FAILED","queue_rejected");}
    }
    private boolean live(Session s){return sessions.get(s.generation)==s;}
    private void run(Session session) {
        try {
            Runnable ready=()->{LOG.info("startup session={} stage=buffer_ready nano={} queuedBytes={} firstPts={}",session.generation,System.nanoTime(),session.pipe.queuedBytes(),session.pipe.firstPts);Minecraft.getInstance().execute(()->{
                if(!live(session)||session.cancellation.cancelled()||Minecraft.getInstance().level!=world)return;
                if(session.timeline!=null){
                    double position=session.timeline.getAsDouble();
                    if(Double.isFinite(position)&&!StartupPolicy.align(session.pipe,session.fromOpening,position)){terminate(session,"FAILED","stale_prebuffer");return;}
                    session.buffered=true;session.state="SCHEDULED";
                    LOG.info("startup session={} stage=ready_applied nano={} position={} firstPts={} opening={}",session.generation,System.nanoTime(),position,session.pipe.firstPts,session.fromOpening);
                    if(session.preparing){
                        session.state="RESERVING";
                        var engine=((SoundManagerAccessor)Minecraft.getInstance().getSoundManager()).streamDiscs$engine();
                        ((SoundReservation)engine).streamDiscs$reserve().whenComplete((handle,error)->Minecraft.getInstance().execute(()->{
                            if(!live(session)||session.cancellation.cancelled()){if(handle!=null&&!handle.isStopped())handle.execute(Channel::stop);return;}
                            if(error!=null||handle==null||handle.isStopped()){terminate(session,"FAILED","reservation_failed");return;}
                            session.reservation=handle;session.state="PREPARED";
                            LOG.info("startup session={} stage=resource_ready nano={} queuedBytes={} firstPts={} consumedFrames={}",session.generation,System.nanoTime(),session.pipe.queuedBytes(),session.pipe.firstPts,session.pipe.consumedFrames);
                            notifySession(session,"READY");
                        }));
                    }
                }else output(session);
            });};
            if(session.input.equals("tone")){session.state="DECODING_TONE";AudioDecoder.tone(session.target,session.pipe,session.cancellation,ready);session.decoded=true;return;}
            SafeHttp http=ClientMediaAccess.provider.http();
            long started=System.nanoTime();
            if(session.timeline!=null){
                var verified=ClientMediaAccess.provider.metadata(session.metadata.target(),session.cancellation);
                if(!verified.target().equals(session.metadata.target())||verified.cid()!=session.metadata.cid()
                        ||!Double.isFinite(verified.duration())||verified.duration()<=0||verified.duration()>86400
                        ||Math.abs(verified.duration()-session.metadata.duration())>1)
                    throw new IOException("SOURCE: bound media identity changed");
            }
            BilibiliSource.Resolved media=ClientMediaAccess.provider.audio(session.metadata==null?ClientMediaAccess.provider.metadata(MediaTarget.parse(session.input),session.cancellation):session.metadata,session.cancellation);
            if(session.metadata!=null&&(!media.target().equals(session.metadata.target())||media.cid()!=session.metadata.cid()))throw new IOException("SOURCE: media identity changed");
            session.cancellation.check();session.cid=media.cid();session.duration=media.duration();
            LOG.info("session={} metadata identity={} cid={} duration={} elapsedMs={} nano={}",session.generation,media.target().identity(),media.cid(),media.duration(),(System.nanoTime()-started)/1_000_000,System.nanoTime());
            Minecraft.getInstance().execute(()->{if(live(session)&&!session.cancellation.cancelled())message(media.title()+"；P"+media.target().page()+"；"+media.duration()+"秒");});
            if(session.target>=media.duration())throw new IOException("INPUT: progress exceeds duration");
            session.state="DECODING";
            try(RangeChannel channel=new RangeChannel(http,media.audio(),session.cancellation)) {
                AudioDecoder.decode(channel,session.target,session.pipe,session.cancellation,ready,line->LOG.info("session={} {} nano={}",session.generation,line,System.nanoTime()));
                session.decoded=true;
            }
        } catch(Exception e) {
            String detail=e.getMessage();String category="FORMAT";
            if(detail!=null) for(String prefix:new String[]{"INPUT","SOURCE","NETWORK","FORMAT","OUTPUT"}) if(detail.startsWith(prefix+":"))category=prefix;
            session.pipe.close();session.cancellation.close();
            LOG.warn("session={} failure={} exception={}",session.generation,category,e.getClass().getSimpleName());
            String finalCategory=category;
            Minecraft.getInstance().execute(()->{
                if(session.terminal)return;
                if(session.decoded&&session.started)terminate(session,"ENDED","decoded_stream_closed");
                else {terminate(session,"FAILED",finalCategory);message("播放失败："+finalCategory+"；请查看本 Mod 日志（无 CDN 地址）");}
            });
        }
    }
    private void output(Session session){
        if(!live(session)||session.started)return;
                try {
                session.sound=new PositionSound(session);
                session.state="OUTPUT_REQUESTED";
                LOG.info("startup session={} stage=output_request nano={} firstPts={}",session.generation,System.nanoTime(),session.pipe.firstPts);
                Object result=Minecraft.getInstance().getSoundManager().play(session.sound);
                boolean accepted=result.toString().equals("STARTED")||result.toString().equals("STARTED_SILENTLY");
                session.state=accepted?"PLAYING":"FAILED:OUTPUT";
                LOG.info("session={} output_result={} sampleRate={} firstPts={}",session.generation,result,session.pipe.sampleRate,session.pipe.firstPts);
                session.started=accepted;
                if(session.started)notifySession(session,"STARTED");
                else terminate(session,"FAILED","output_rejected");
                } catch(RuntimeException e){terminate(session,"FAILED","output_exception");message("播放失败：OUTPUT");}
    }
    public void playSynced(String key,BilibiliSource.Metadata meta,double x,double y,double z,java.util.function.DoubleSupplier timeline,java.util.function.Consumer<String> callback){
        synced(key,meta,x,y,z,timeline,callback,false);
    }
    public void prepareSynced(String key,BilibiliSource.Metadata meta,double x,double y,double z,java.util.function.DoubleSupplier timeline,java.util.function.Consumer<String> callback){
        synced(key,meta,x,y,z,timeline,callback,true);
    }
    private void synced(String key,BilibiliSource.Metadata meta,double x,double y,double z,java.util.function.DoubleSupplier timeline,java.util.function.Consumer<String> callback,boolean preparing){
        stopKey(key,"sync_replace");
        if(sessions.size()>=2){callback.accept("DECLINED");return;}
        world=Minecraft.getInstance().level;
        double signedPosition=preparing?0:timeline.getAsDouble();double target=StartupPolicy.decodeTarget(signedPosition);
        if(target>=meta.duration()){callback.accept("ENDED");return;}
        Session session=new Session(++generation,meta.target().bvid(),target,x,y,z);session.key=key;session.metadata=meta;session.timeline=timeline;session.callback=callback;session.fromOpening=StartupPolicy.fromOpening(signedPosition);session.preparing=preparing;
        LOG.info("startup session={} stage=received nano={} signedPosition={} decodeTarget={} opening={}",session.generation,System.nanoTime(),signedPosition,target,session.fromOpening);
        sessions.put(session.generation,session);keyed.put(key,session);current=session;
        try{session.job=workers.submit(()->run(session));}catch(RejectedExecutionException e){terminate(session,"FAILED","queue_rejected");}
    }
    public boolean prepared(String key){Session s=keyed.get(key);return s!=null&&!s.terminal&&s.buffered&&s.reservation!=null&&!s.reservation.isStopped();}
    /** The normal SoundEngine play path claims exactly this session's preallocated channel. */
    public CompletableFuture<ChannelAccess.ChannelHandle> claimReservation(SoundInstance sound){
        if(!(sound instanceof PositionSound ps)||!ps.session.preparing)return null;
        Session s=ps.session;if(!live(s)||s.reservationClaimed||s.reservation==null||s.reservation.isStopped())return CompletableFuture.completedFuture(null);
        s.reservationClaimed=true;return CompletableFuture.completedFuture(s.reservation);
    }
    public double position(String key){Session s=keyed.get(key);if(s==null||!s.started||!Double.isFinite(s.observedPosition)||System.nanoTime()-s.observedAt>100_000_000L)return Double.NaN;return s.observedPosition+(s.observedPlaying?(System.nanoTime()-s.observedAt)/1_000_000_000.0:0);}
    public String status() {
        Session s=current;if(s==null)return "IDLE；generation="+generation;
        boolean active=s.sound!=null&&Minecraft.getInstance().getSoundManager().isActive(s.sound);
        return "generation="+s.generation+" state="+s.state+" active="+active+" cid="+s.cid+" queued="+s.pipe.queuedBytes()
                +" actualFirstPts="+s.pipe.firstPts+" consumedSeconds="+(s.pipe.sampleRate==0?0:s.pipe.consumedFrames/(double)s.pipe.sampleRate)
                +" processedSeconds="+s.playedSeconds+" silenceFrames="+s.pipe.silenceFrames+" "+s.channel;
    }
    public void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(!sessions.isEmpty()&&mc.level!=world){stop("world_changed");}
        ClientRequests.tick();
        for(Session s:java.util.List.copyOf(sessions.values())){
            if(s.buffered&&!s.started&&s.timeline!=null){
                if(s.preparing){
                    if(s.reservation==null)continue;
                    if(s.reservation.isStopped()){terminate(s,"FAILED","reservation_invalidated");continue;}
                }
                double position=s.timeline.getAsDouble();
                if(!Double.isFinite(position))continue;
                if(position-s.pipe.firstPts>.15&&!StartupPolicy.align(s.pipe,s.fromOpening,position)){terminate(s,"FAILED","stale_prebuffer");continue;}
                if(StartupPolicy.canOutput(position,s.pipe.firstPts))output(s);
            }
            if(!s.key.equals("dev")&&(mc.level==null||!mc.level.hasChunkAt(net.minecraft.core.BlockPos.containing(s.x,s.y,s.z)))){terminate(s,"FAILED","chunk_unload");continue;}
            if(s.started&&s.sound!=null&&!mc.getSoundManager().isActive(s.sound)){
                if(++s.missingTicks>=10)terminate(s,"ENDED","natural_end");
            }else s.missingTicks=0;
        }
        if(++ticks%100==0&&current!=null)LOG.debug("status {}",status());

    }
    public void stopKey(String key,String reason){
        Minecraft mc=Minecraft.getInstance();if(!mc.isSameThread()){mc.execute(()->stopKey(key,reason));return;}
        Session old=keyed.get(key);if(old!=null)terminate(old,null,reason);
    }
    /** One render-thread terminal transition; compare identity before releasing registrations. */
    private void terminate(Session old,String event,String reason){
        Minecraft mc=Minecraft.getInstance();if(!mc.isSameThread()){mc.execute(()->terminate(old,event,reason));return;}
        if(old.terminal)return;old.terminal=true;old.state=event==null?"CANCELLED":event;
        sessions.remove(old.generation,old);keyed.remove(old.key,old);
        if(current==old)current=sessions.values().stream().findFirst().orElse(null);
        old.cancellation.close();old.pipe.close();
        if(old.job!=null){old.job.cancel(true);if(old.job instanceof Runnable queued)workers.remove(queued);}
        if(old.sound!=null)mc.getSoundManager().stop(old.sound);
        if(old.reservation!=null&&!old.reservationClaimed&&!old.reservation.isStopped())old.reservation.execute(Channel::stop);
        workers.purge();
        LOG.info("session={} key={} terminal={} reason={} consumedFrames={} processedSeconds={} silenceFrames={}",old.generation,old.key,event==null?"CANCELLED":event,reason,old.pipe.consumedFrames,old.playedSeconds,old.pipe.silenceFrames);
        // Explicit cancellation is also a single terminal observation. The network
        // callback checks its playback UUID, so a replaced session cannot end its successor.
        notifySession(old,event==null?"ENDED":event);
    }
    private static void notifySession(Session s,String event){try{s.callback.accept(event);}catch(RuntimeException e){LOG.warn("session={} observer_failure={}",s.generation,e.getClass().getSimpleName());}}
    public void stop(String reason){Minecraft mc=Minecraft.getInstance();if(!mc.isSameThread()){mc.execute(()->stop(reason));return;}for(Session s:java.util.List.copyOf(sessions.values()))terminate(s,null,reason);generation++;}
    public void close(){stop("client_close");ClientRequests.cancel();workers.shutdownNow();}
    private static void message(String text){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("[Stream Disc] "+text));}
    public CompletableFuture<AudioStream> stream(Identifier path) {
        Session s=sessions.values().stream().filter(v->path.getPath().equals("sounds/session/"+v.generation+".ogg")).findFirst().orElse(null);
        if(!path.getNamespace().equals("stream_discs")||!path.getPath().startsWith("sounds/session/"))return null;
        if(s==null||!path.getPath().equals("sounds/session/"+s.generation+".ogg")||s.cancellation.cancelled())return CompletableFuture.failedFuture(new IOException("CANCELLED"));
        return CompletableFuture.completedFuture(new TrackedStream(){
            @Override public AudioFormat getFormat(){return new AudioFormat(s.pipe.sampleRate,16,1,true,false);}
            @Override public ByteBuffer read(int bytes){return s.pipe.read(bytes);}
            @Override public void close(){s.pipe.close();s.cancellation.close();}
            @Override public void processed(double seconds){s.playedSeconds+=seconds;}
            @Override public void offset(double seconds,boolean playing){s.observedPosition=s.pipe.positionAt(s.playedSeconds+seconds);s.observedAt=System.nanoTime();s.observedPlaying=playing;}
            @Override public void snapshot(float gain,float x,float y,float z){s.channel="alGain="+gain+" alPosition="+x+","+y+","+z;}
        });
    }
    private static final class PositionSound extends AbstractSoundInstance implements TickableSoundInstance {
        final Session session;
        PositionSound(Session s){
            super(Identifier.fromNamespaceAndPath("stream_discs","stream"),SoundSource.RECORDS,SoundInstance.createUnseededRandom());
            session=s;x=s.x;y=s.y;z=s.z;volume=1;pitch=1;relative=false;looping=false;attenuation=Attenuation.LINEAR;
            sound=new Sound(Identifier.fromNamespaceAndPath("stream_discs","session/"+s.generation),ConstantFloat.of(1),ConstantFloat.of(1),1,Sound.Type.FILE,true,false,64);
        }
        @Override public WeighedSoundEvents resolve(SoundManager manager){WeighedSoundEvents event=new WeighedSoundEvents(identifier,null);event.addSound(sound);return event;}
        @Override public boolean isStopped(){return session.cancellation.cancelled();}
        @Override public boolean canStartSilent(){return true;}
        @Override public void tick(){}
    }
}
