package com.zbw3790.streamdiscs.client;
import com.zbw3790.streamdiscs.ItemCapability;
import net.fabricmc.fabric.api.client.networking.v1.*;
import java.util.*;
public final class ItemCapabilityClient {
    public static void init(){
        Set<Object> pending=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
        ClientConfigurationConnectionEvents.INIT.register((listener,client)->pending.add(listener));
        ClientConfigurationNetworking.registerGlobalReceiver(ItemCapability.TYPE,(offer,context)->{pending.remove(context.packetListener());context.responseSender().sendPacket(ItemCapability.local(offer.nonce()));});
        ClientConfigurationConnectionEvents.COMPLETE.register((listener,client)->{if(pending.remove(listener))ClientConfigurationNetworking.getSender().disconnect(ItemCapability.REASON);});
        ClientConfigurationConnectionEvents.DISCONNECT.register((listener,client)->pending.remove(listener));
    }
}
