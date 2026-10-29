package com.zbw3790.streamdiscs;

import com.zbw3790.streamdiscs.core.MediaTarget;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.UUID;

/** Persisted identity only. Temporary playback URLs never enter an item. */
public record Binding(UUID token, MediaTarget target, String state, int attempt, long cid, String title, double duration, String error) {
    public static final String KEY="stream_discs:binding";
    public static boolean record(ItemStack stack){return !stack.isEmpty()&&(StreamDiscItem.is(stack)||stack.has(DataComponents.JUKEBOX_PLAYABLE));}
    public static String text(String value,int max){if(value==null)return "";return value.replaceAll("[\\p{Cntrl}§]", " ").substring(0,Math.min(max,value.replaceAll("[\\p{Cntrl}§]", " ").length()));}
    public static Binding reading(MediaTarget t){return new Binding(UUID.randomUUID(),t,"READING",0,0,"",0,"");}
    public boolean pending(){return state.equals("READING")||state.equals("RETRYING");}
    public Binding status(String s,int a,long c,String t,double d,String e){return new Binding(token,target,s,a,c,text(t,256),d,text(e,96));}
    public static Binding get(ItemStack stack){
        if(!record(stack))return null;
        try {
            CompoundTag n=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompoundOrEmpty(KEY);
            if(n.getIntOr("version",0)!=1)return null;
            String state=n.getStringOr("state","");
            if(!java.util.Set.of("READING","RETRYING","READY","FAILED","INTERRUPTED").contains(state))return null;
            double duration=n.getDoubleOr("duration",0);if(!Double.isFinite(duration)||duration<0||duration>86400)return null;
            int attempt=n.getIntOr("attempt",0);if(attempt<0||attempt>3||state.equals("READY")&&(duration<=0||n.getLongOr("cid",0)<=0))return null;
            return new Binding(UUID.fromString(n.getStringOr("token","")),new MediaTarget(n.getStringOr("bvid",""),n.getIntOr("page",0)),state,n.getIntOr("attempt",0),n.getLongOr("cid",0),text(n.getStringOr("title",""),256),duration,text(n.getStringOr("error",""),96));
        }catch(IllegalArgumentException e){return null;}
    }
    public void put(ItemStack stack){
        CompoundTag n=new CompoundTag();n.putInt("version",1);n.putString("token",token.toString());n.putString("bvid",target.bvid());n.putInt("page",target.page());
        n.putString("state",state);n.putInt("attempt",attempt);n.putLong("cid",cid);n.putString("title",text(title,256));n.putDouble("duration",duration);n.putString("error",text(error,96));
        CustomData.update(DataComponents.CUSTOM_DATA,stack,root->root.put(KEY,n));
    }
}
