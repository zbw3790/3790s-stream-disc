package com.zbw3790.streamdiscs;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.gameevent.GameEvent;
import java.lang.ref.WeakReference;
import java.util.*;
import com.zbw3790.streamdiscs.core.PreparationBarrier;

/** Logical-server authority. Only pickup commits requests; observed containers are bounded. */
public final class ServerState {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("stream_discs");
    private record Request(UUID owner,Binding initial,long deadline){}
    private static final Map<UUID,Request> requests=new LinkedHashMap<>();
    private static final Map<UUID,Binding> results=new LinkedHashMap<>();
    private static final Set<WeakReference<RandomizableContainerBlockEntity>> boxes=new HashSet<>();
    private static final Set<WeakReference<ItemEntity>> drops=new HashSet<>();
    private static final Map<String,Playback> playing=new HashMap<>();
    private static long ticks,revision;
    private static UUID epoch=UUID.randomUUID();
    private static final Map<UUID,Peer> peers=new HashMap<>();
    private static long lastReconcile;
    private static final class Peer {
        final ServerPlayer player;final UUID connection=UUID.randomUUID();boolean compatible;long serial,window,offers;int count;
        final Set<String> subscriptions=new HashSet<>();
        Peer(ServerPlayer p){player=p;}
    }
    private static long now(){return System.nanoTime();}
    public static void join(ServerPlayer p){if(peers.size()<64){Peer peer=new Peer(p);peers.put(p.getUUID(),peer);welcome(peer);}}
    private static void welcome(Peer p){if(ServerPlayNetworking.canSend(p.player,Wire.TYPE)){JsonObject j=Wire.message("welcome",p.connection.toString());envelope(p,j);ServerPlayNetworking.send(p.player,Wire.of(j));p.offers=now();}}
    private static void envelope(Peer p,JsonObject j){j.addProperty("epoch",epoch.toString());j.addProperty("connection",p.connection.toString());j.addProperty("serial",++p.serial);}

    private static final class Playback {
        final JukeboxBlockEntity box;final UUID token;final UUID session=UUID.randomUUID();final String key;
        String state="WAITING";long rev=++revision;com.zbw3790.streamdiscs.core.MediaTimeline timeline;
        Binding binding;
        final UUID preparation=UUID.randomUUID();long prepareRevision;PreparationBarrier barrier;
        Playback(JukeboxBlockEntity b,Binding binding,String key){box=b;token=binding.token();this.binding=binding;this.key=key;}
    }
    public static void clear(){requests.clear();results.clear();boxes.clear();drops.clear();playing.clear();peers.clear();ticks=revision=lastReconcile=0;epoch=UUID.randomUUID();}
    public static void commit(ServerPlayer player,Binding binding){
        if(requests.containsKey(binding.token())||results.containsKey(binding.token()))return;
        if(requests.size()>=64){finish(binding.status("FAILED",0,0,"",0,"BUSY：请稍后重命名重试"));return;}
        requests.put(binding.token(),new Request(player.getUUID(),binding,ticks+2400));
        storeResult(binding);
        JsonObject j=Wire.message("metadata",binding.token().toString());j.addProperty("bvid",binding.target().bvid());j.addProperty("page",binding.target().page());
        if(peers.containsKey(player.getUUID())&&peers.get(player.getUUID()).compatible)send(player,j);
        else finish(binding.status("FAILED",0,0,"",0,"客户端不支持媒体读取"));
        LOG.info("metadata_commit token={} target={}",binding.token(),binding.target().identity());
    }
    /** Every write obeys the same hard cap; active authorization records are never evicted. */
    private static void storeResult(Binding b){
        while(!results.containsKey(b.token())&&results.size()>=256){
            Iterator<UUID> entries=results.keySet().iterator();boolean removed=false;
            while(entries.hasNext()){if(!requests.containsKey(entries.next())){entries.remove();removed=true;break;}}
            if(!removed)throw new IllegalStateException("Active request bound exceeds result capacity");
        }
        results.put(b.token(),b);
    }
    private static void finish(Binding b){requests.remove(b.token());storeResult(b);}
    public static boolean refresh(ItemStack stack){
        Binding b=Binding.get(stack);if(b==null||!b.pending())return false;
        Binding newer=results.get(b.token());
        if(newer==null&&!requests.containsKey(b.token()))newer=b.status("INTERRUPTED",b.attempt(),0,"",0,"读取已中断；在铁砧领取重试");
        if(newer!=null&&!newer.equals(b)&&newer.target().equals(b.target())){newer.put(stack);return true;}return false;
    }
    public static void observe(RandomizableContainerBlockEntity box){
        if(box.getLevel() instanceof ServerLevel&&boxes.size()<128&&boxes.stream().noneMatch(r->r.get()==box))boxes.add(new WeakReference<>(box));
    }
    public static void observe(ItemEntity e){if(e.level() instanceof ServerLevel&&Binding.get(e.getItem())!=null){if(refresh(e.getItem()))e.setItem(e.getItem().copy());if(drops.size()<128&&drops.stream().noneMatch(r->r.get()==e))drops.add(new WeakReference<>(e));}}
    public static void receive(ServerPlayer p,Wire wire){
        long received=now();
        try {
            JsonObject j=wire.data();Peer peer=peers.get(p.getUUID());
            if(peer==null||peer.player!=p||!Wire.envelopeMatches(j,epoch,peer.connection))return;
            if(received-peer.window>=1_000_000_000L){peer.window=received;peer.count=0;}if(++peer.count>128)return;
            String kind=j.get("kind").getAsString();UUID id=UUID.fromString(j.get("id").getAsString());
            if(kind.equals("hello")){peer.compatible=true;return;}
            if(!peer.compatible)return;
            if(kind.equals("clock")){
                JsonObject out=Wire.message("clock",id.toString());out.addProperty("t1",j.get("t1").getAsLong());out.addProperty("t2",received);out.addProperty("t3",now());send(p,out);return;
            }
            if(kind.equals("metadata_result")){
                Request r=requests.get(id);if(r==null||!r.owner.equals(p.getUUID()))return;
                String state=j.get("state").getAsString();int attempt=j.get("attempt").getAsInt();
                if(attempt<1||attempt>3||!Set.of("RETRYING","READY","FAILED").contains(state))return;
                Binding old=results.get(id);if(old!=null&&attempt<old.attempt())return;
                long cid=state.equals("READY")?j.get("cid").getAsLong():0;double duration=state.equals("READY")?j.get("duration").getAsDouble():0;
                if(state.equals("READY")&&(cid<=0||!Double.isFinite(duration)||duration<=0||duration>86400))return;
                Binding b=r.initial.status(state,attempt,cid,state.equals("READY")?j.get("title").getAsString():"",duration,state.equals("FAILED")?j.get("error").getAsString():"");
                if(b.pending())storeResult(b);else finish(b);
                LOG.info("metadata_result token={} state={} attempt={}",id,state,attempt);
            }else if(kind.equals("preparation_status")){
                String key=j.get("key").getAsString();Playback play=playing.get(key);
                if(key.length()>200||play==null||play.barrier==null||!peer.subscriptions.contains(key)||!eligible(peer,play,80)||!Wire.preparationMatches(j,key,play.session,play.token,play.preparation,play.prepareRevision))return;
                var status=PreparationBarrier.Status.valueOf(j.get("status").getAsString());
                if(play.barrier.acknowledge(peer.connection,j.get("attempt").getAsInt(),j.get("sequence").getAsLong(),status)){
                    LOG.info("preparation_status session={} connection={} status={} attempt={} sequence={} nano={}",id,peer.connection,status,j.get("attempt"),j.get("sequence"),received);commitStart(play,received);
                }
            }else if(kind.equals("playback")){
                String key=j.get("key").getAsString();Playback play=playing.get(key);
                if(play==null||!peer.subscriptions.contains(key)||!play.session.equals(id)||j.get("revision").getAsLong()<play.prepareRevision||j.get("revision").getAsLong()>play.rev||p.level()!=play.box.getLevel())return;
                String event=j.get("event").getAsString();if(!Set.of("STARTED","BUFFERING","FAILED","ENDED").contains(event))return;
                LOG.info("listener_observation player={} session={} event={}",p.getUUID(),id,event);
            }
        }catch(RuntimeException e){LOG.warn("Rejected malformed media-v3 message");}
    }
    public static void disconnect(ServerPlayer p){
        Peer peer=peers.get(p.getUUID());if(peer!=null&&peer.player==p)peers.remove(p.getUUID());
        for(UUID id:new ArrayList<>(requests.keySet())){Request r=requests.get(id);if(r.owner.equals(p.getUUID()))finish(r.initial.status("INTERRUPTED",0,0,"",0,"读取已中断"));}
    }
    public static void tick(MinecraftServer server){
        ticks++;
        long n=now();
        for(ServerPlayer p:server.getPlayerList().getPlayers()){
            if(!peers.containsKey(p.getUUID()))join(p);
            Peer peer=peers.get(p.getUUID());if(peer!=null&&!peer.compatible&&n-peer.offers>2_000_000_000L)welcome(peer);
        }
        for(Playback play:new ArrayList<>(playing.values())){
            if(play.box.isRemoved()||!(play.box.getLevel() instanceof ServerLevel l)||!l.hasChunkAt(play.box.getBlockPos())){stop(play.box,"unload");continue;}
            if(play.barrier!=null&&play.timeline==null&&!play.state.equals("ENDED")){
                if(play.barrier.retain(peers.values().stream().filter(peer->eligible(peer,play,80)).map(peer->peer.connection).toList()))LOG.info("preparation_members session={} remaining={} nano={}",play.session,play.barrier.members(),n);
                commitStart(play,n);
            }
            if(play.timeline!=null&&play.timeline.ended(n)&&!play.state.equals("ENDED")){end(play,"duration_end");}
            else if(play.timeline!=null&&play.timeline.active(n)&&play.state.equals("SCHEDULED")){play.state="PLAYING";play.rev=++revision;changed(play);}
        }
        if(n-lastReconcile>=1_000_000_000L){lastReconcile=n;reconcile(n);}

        for(UUID id:new ArrayList<>(requests.keySet())){Request r=requests.get(id);if(ticks>r.deadline){finish(r.initial.status("FAILED",3,0,"",0,"读取超时；铁砧领取重试"));ServerPlayer p=server.getPlayerList().getPlayer(r.owner);if(p!=null)send(p,Wire.message("cancel",id.toString()));}}
        if(ticks%5!=0)return;
        for(ServerPlayer p:server.getPlayerList().getPlayers()){
            boolean dirty=false;for(int i=0;i<p.getInventory().getContainerSize();i++)dirty|=refresh(p.getInventory().getItem(i));
            dirty|=refresh(p.containerMenu.getCarried());
            if(dirty){p.getInventory().setChanged();p.containerMenu.broadcastChanges();}
        }
        boxes.removeIf(ref->{RandomizableContainerBlockEntity b=ref.get();if(b==null||b.isRemoved()||!(b.getLevel() instanceof ServerLevel l)||!l.hasChunkAt(b.getBlockPos()))return true;
            boolean dirty=false;for(int i=0;i<b.getContainerSize();i++)dirty|=refresh(b.getItem(i));if(dirty)b.setChanged();return false;});
        drops.removeIf(ref->{ItemEntity e=ref.get();if(e==null||e.isRemoved())return true;ItemStack s=e.getItem();if(refresh(s))e.setItem(s.copy());return false;});
    }
    private static void send(ServerPlayer p,JsonObject j){Peer peer=peers.get(p.getUUID());if(peer!=null&&peer.compatible&&peer.player==p&&ServerPlayNetworking.canSend(p,Wire.TYPE)){envelope(peer,j);ServerPlayNetworking.send(p,Wire.of(j));}}
    private static JsonObject stateMessage(Playback p,String kind){JsonObject j=Wire.message(kind,p.session.toString());j.addProperty("key",p.key);j.addProperty("revision",p.rev);j.addProperty("token",p.token.toString());return j;}
    private static void snapshot(Peer peer,Playback p){
        if(p.barrier==null||p.state.equals("ENDED"))return;
        JsonObject j=stateMessage(p,"snapshot");Binding b=p.binding;
        j.addProperty("source","bilibili");j.addProperty("bvid",b.target().bvid());j.addProperty("page",b.target().page());j.addProperty("cid",b.cid());j.addProperty("title",b.title());j.addProperty("duration",b.duration());j.addProperty("pos",p.box.getBlockPos().asLong());
        j.addProperty("state",p.state);j.addProperty("preparation",p.preparation.toString());j.addProperty("prepareRevision",p.prepareRevision);j.addProperty("preparedAt",p.barrier.preparedAt());j.addProperty("deadline",p.barrier.deadline());
        if(p.timeline!=null){j.addProperty("anchor",p.timeline.anchorNanos());j.addProperty("mediaStart",p.timeline.mediaStart());}j.addProperty("rate",1);send(peer.player,j);
    }
    private static void reconcile(long n){
        for(Peer peer:peers.values()){
            if(!peer.compatible)continue;ServerPlayer player=peer.player;
            for(String key:new ArrayList<>(peer.subscriptions)){
                Playback p=playing.get(key);
                if(p==null||p.state.equals("ENDED")||player.level()!=p.box.getLevel()||!player.level().hasChunkAt(p.box.getBlockPos())||player.distanceToSqr(p.box.getBlockPos().getX()+.5,p.box.getBlockPos().getY()+.5,p.box.getBlockPos().getZ()+.5)>80*80){
                    peer.subscriptions.remove(key);if(p!=null)send(player,stateMessage(p,"forget"));
                }
            }
            for(Playback p:playing.values()){
                if(p.barrier==null||p.state.equals("ENDED")||player.level()!=p.box.getLevel()||!player.level().hasChunkAt(p.box.getBlockPos()))continue;
                boolean subscribed=peer.subscriptions.contains(p.key);
                if(!subscribed&&player.distanceToSqr(p.box.getBlockPos().getX()+.5,p.box.getBlockPos().getY()+.5,p.box.getBlockPos().getZ()+.5)<=64*64){peer.subscriptions.add(p.key);snapshot(peer,p);}
                else if(subscribed&&ticks%40<20)snapshot(peer,p);
            }
        }
    }
    private static boolean eligible(Peer peer,Playback p,int radius){return peer.compatible&&peer.player.level()==p.box.getLevel()&&peer.player.level().hasChunkAt(p.box.getBlockPos())&&peer.player.distanceToSqr(p.box.getBlockPos().getX()+.5,p.box.getBlockPos().getY()+.5,p.box.getBlockPos().getZ()+.5)<=radius*radius;}
    private static void commitStart(Playback p,long n){
        if(p.state.equals("ENDED")||!p.barrier.commit(n))return;
        p.timeline=new com.zbw3790.streamdiscs.core.MediaTimeline(p.barrier.anchor(),0,p.binding.duration());p.state="SCHEDULED";p.rev=++revision;changed(p);
        LOG.info("preparation_commit session={} preparation={} preparedAt={} deadline={} anchor={} reason={} ready={} members={} gap={} lateNanos={} nano={}",p.session,p.preparation,p.barrier.preparedAt(),p.barrier.deadline(),p.timeline.anchorNanos(),p.barrier.reason(),p.barrier.readyCount(),p.barrier.members(),PreparationBarrier.GAP_NANOS,Math.max(0,n-(p.barrier.deadline()-PreparationBarrier.GAP_NANOS)),n);
        for(Peer peer:peers.values())if(peer.subscriptions.contains(p.key))snapshot(peer,p);
    }
    public static String key(JukeboxBlockEntity b){return b.getLevel().dimension().identifier()+"/"+b.getBlockPos().asLong();}
    public static void insert(JukeboxBlockEntity b){
        if(!(b.getLevel() instanceof ServerLevel))return;
        stop(b,"replace");Binding binding=Binding.get(b.getTheItem());if(binding!=null&&playing.size()<128)playing.put(key(b),new Playback(b,binding,key(b)));
    }
    public static boolean active(JukeboxBlockEntity b){Playback p=playing.get(key(b));return p!=null&&p.timeline!=null&&p.timeline.active(now())&&!p.state.equals("ENDED");}
    private static void changed(Playback p){if(p.box.getLevel() instanceof ServerLevel l){l.updateNeighborsAt(p.box.getBlockPos(),p.box.getBlockState().getBlock());p.box.setChanged();}}
    private static void end(Playback p,String reason){
        p.state="ENDED";p.rev=++revision;
        for(Peer peer:peers.values())if(peer.subscriptions.remove(p.key))send(peer.player,stateMessage(p,"stop"));
        changed(p);LOG.info("jukebox_stop key={} session={} revision={} reason={}",p.key,p.session,p.rev,reason);
    }
    public static void stop(JukeboxBlockEntity b,String reason){
        if(!(b.getLevel() instanceof ServerLevel))return;Playback p=playing.remove(key(b));if(p!=null)end(p,reason);
    }
    public static void tickBox(JukeboxBlockEntity b){
        if(!(b.getLevel() instanceof ServerLevel l))return;
        refresh(b.getTheItem());Binding binding=Binding.get(b.getTheItem());Playback p=playing.get(key(b));
        if(p==null||binding==null)return;
        if(!binding.token().equals(p.token)){stop(b,"identity_changed");return;}
        if(p.state.equals("WAITING")&&binding.state().equals("READY")){
            p.binding=binding;p.state="PREPARING";p.rev=++revision;p.prepareRevision=p.rev;long n=now();
            var members=peers.values().stream().filter(peer->eligible(peer,p,peer.subscriptions.contains(p.key)?80:64)).map(peer->peer.connection).toList();
            p.barrier=new PreparationBarrier(n,members);changed(p);
            LOG.info("preparation_begin session={} preparation={} preparedAt={} deadline={} members={}",p.session,p.preparation,n,p.barrier.deadline(),members);reconcile(n);commitStart(p,n);
        }
        if(p.state.equals("WAITING")&&!binding.pending()&&!binding.state().equals("READY"))end(p,"metadata_failed");
        if(active(b)&&ticks%20==0)l.gameEvent(GameEvent.JUKEBOX_PLAY,b.getBlockPos(),GameEvent.Context.of(b.getBlockState()));
    }
}
