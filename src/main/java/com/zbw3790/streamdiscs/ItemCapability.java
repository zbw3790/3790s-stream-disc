package com.zbw3790.streamdiscs;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ConfigurationTask;
import java.util.*;import java.nio.file.*;import java.security.MessageDigest;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;

/** Configuration-only item compatibility gate; media_v3 carries no new semantics. */
public record ItemCapability(UUID nonce,String version,String hash,int rawId) implements CustomPacketPayload {
    public static final Type<ItemCapability> TYPE=new Type<>(Identifier.fromNamespaceAndPath("stream_discs","item_v1"));
    public static final ConfigurationTask.Type TASK=new ConfigurationTask.Type("stream_discs:item_v1");
    public static final Component REASON=Component.literal("[Stream Disc] 服务器与客户端须安装同一候选JAR及物品注册；不支持旧版混装");
    public static final StreamCodec<FriendlyByteBuf,ItemCapability> CODEC=new StreamCodec<>(){
        public ItemCapability decode(FriendlyByteBuf b){return new ItemCapability(b.readUUID(),b.readUtf(64),b.readUtf(64),b.readVarInt());}
        public void encode(FriendlyByteBuf b,ItemCapability p){b.writeUUID(p.nonce);b.writeUtf(p.version,64);b.writeUtf(p.hash,64);b.writeVarInt(p.rawId);}
    };
    public Type<ItemCapability> type(){return TYPE;}
    private static final class Identity {
        static final String VERSION=FabricLoader.getInstance().getModContainer("stream_discs").orElseThrow().getMetadata().getVersion().getFriendlyString();
        static final String HASH=digest();
        static String digest(){try{var paths=FabricLoader.getInstance().getModContainer("stream_discs").orElseThrow().getOrigin().getPaths();if(paths.size()!=1||!Files.isRegularFile(paths.getFirst()))throw new IllegalStateException("Packaged single JAR required for multiplayer");var md=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(paths.getFirst())){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}catch(Exception e){throw new IllegalStateException("Cannot identify product JAR",e);}}
    }
    public static ItemCapability local(UUID nonce){return new ItemCapability(nonce,Identity.VERSION,Identity.HASH,BuiltInRegistries.ITEM.getId(StreamDiscItem.ITEM));}
    public boolean matches(ItemCapability other){return hash.length()==64&&rawId>=0&&equals(other);}
    public static void init(){
        PayloadTypeRegistry.clientboundConfiguration().register(TYPE,CODEC);PayloadTypeRegistry.serverboundConfiguration().register(TYPE,CODEC);
        ServerConfigurationConnectionEvents.CONFIGURE.register((listener,server)->{
            if(!ServerConfigurationNetworking.canSend(listener,TYPE)){listener.disconnect(REASON);return;}
            ItemCapability offer=local(UUID.randomUUID());AtomicBoolean active=new AtomicBoolean(),finished=new AtomicBoolean();
            ServerConfigurationNetworking.registerReceiver(listener,TYPE,(ack,context)->{
                if(!active.get()||finished.get())return;
                if(!offer.matches(ack)){finished.set(true);listener.disconnect(REASON);return;}
                if(finished.compareAndSet(false,true))((FabricServerConfigurationPacketListenerImpl)listener).completeTask(TASK);
            });
            ((FabricServerConfigurationPacketListenerImpl)listener).addTask(new ConfigurationTask(){
                public Type type(){return TASK;}
                public void start(java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> sender){active.set(true);sender.accept(ServerConfigurationNetworking.createClientboundPacket(offer));CompletableFuture.delayedExecutor(10,TimeUnit.SECONDS).execute(()->{if(finished.compareAndSet(false,true))listener.disconnect(REASON);});}
            });
        });
    }
}
