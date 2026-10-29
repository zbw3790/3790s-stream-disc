package com.zbw3790.streamdiscs;

import com.google.gson.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Small bounded protocol; no inventory slot indices or arbitrary destinations. */
public record Wire(String json) implements CustomPacketPayload {
    public static final int VERSION=3;
    public static final Type<Wire> TYPE=new Type<>(Identifier.fromNamespaceAndPath("stream_discs","media_v3"));
    public static final StreamCodec<RegistryFriendlyByteBuf,Wire> CODEC=new StreamCodec<>() {
        public Wire decode(RegistryFriendlyByteBuf b){return new Wire(b.readUtf(4096));}
        public void encode(RegistryFriendlyByteBuf b,Wire m){b.writeUtf(m.json,4096);}
    };
    public Type<Wire> type(){return TYPE;}
    public JsonObject data(){return JsonParser.parseString(json).getAsJsonObject();}
    public static boolean supported(JsonObject j){return j.has("v")&&j.get("v").getAsInt()==VERSION;}
    public static boolean envelopeMatches(JsonObject j,java.util.UUID epoch,java.util.UUID connection){return supported(j)&&epoch!=null&&connection!=null&&epoch.toString().equals(j.get("epoch").getAsString())&&connection.toString().equals(j.get("connection").getAsString());}
    public static boolean preparationMatches(JsonObject j,String key,java.util.UUID session,java.util.UUID token,java.util.UUID preparation,long generation){return key.equals(j.get("key").getAsString())&&session.toString().equals(j.get("id").getAsString())&&token.toString().equals(j.get("token").getAsString())&&preparation.toString().equals(j.get("preparation").getAsString())&&generation==j.get("prepareRevision").getAsLong();}
    public static JsonObject message(String kind,String id){JsonObject j=new JsonObject();j.addProperty("v",VERSION);j.addProperty("kind",kind);j.addProperty("id",id);return j;}
    public static Wire of(JsonObject j){return new Wire(j.toString());}
}
