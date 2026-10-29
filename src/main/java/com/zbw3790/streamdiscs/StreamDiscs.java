package com.zbw3790.streamdiscs;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class StreamDiscs implements ModInitializer {
    public void onInitialize(){
        StreamDiscItem.init();
        ItemCapability.init();
        PayloadTypeRegistry.clientboundPlay().register(Wire.TYPE,Wire.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Wire.TYPE,Wire.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Wire.TYPE,(m,c)->ServerState.receive(c.player(),m));
        ServerTickEvents.END_SERVER_TICK.register(ServerState::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s->ServerState.clear());
        ServerPlayConnectionEvents.JOIN.register((h,sender,s)->ServerState.join(h.player));
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->ServerState.disconnect(h.player));
    }
}
