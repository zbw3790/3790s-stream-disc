package com.zbw3790.streamdiscs;

import com.zbw3790.streamdiscs.core.MediaTarget;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import java.io.*;
import java.util.*;

/** Pure preview/transfer; no requests, inventory mutation or payment occurs here. */
public final class DiscConversion {
    public static final String ORIGIN="stream_discs:origin";
    public static final int MAX_BYTES=16384,MAX_DEPTH=16,MAX_NODES=2048;
    private static final Set<DataComponentType<?>> CARRY=Set.of(DataComponents.CUSTOM_DATA,DataComponents.CUSTOM_NAME,DataComponents.REPAIR_COST,DataComponents.LORE,DataComponents.ENCHANTMENTS,DataComponents.STORED_ENCHANTMENTS,DataComponents.TOOLTIP_DISPLAY,DataComponents.TOOLTIP_STYLE,DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    public record Preview(ItemStack stack,Binding submit,int cost){}
    public static Preview empty(){return new Preview(ItemStack.EMPTY,null,0);}
    public static boolean hasBindingData(ItemStack s){return s.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains(Binding.KEY);}
    public static Preview prepare(ItemStack input,String name,HolderLookup.Provider registries){
        if(!Binding.record(input)||name==null)return null;
        if(input.getCount()!=1)return empty();
        Binding old=Binding.get(input);if(old==null&&hasBindingData(input))throw new IllegalArgumentException("绑定结构损坏或版本未知；保留输入");
        MediaTarget target=null;try{target=MediaTarget.parse(name);}catch(IllegalArgumentException ignored){}
        if(target==null){String lower=name.toLowerCase(Locale.ROOT);if(lower.contains("://")||lower.startsWith("www.")||lower.startsWith("b23.")||lower.startsWith("http")||lower.startsWith("bv")||name.length()>50)return empty();if(old==null)return null;
            if(StreamDiscItem.is(input)&&name.equals(input.getHoverName().getString()))return empty();
        }
        boolean retry=old!=null&&(old.state().equals("FAILED")||old.state().equals("INTERRUPTED"));
        Binding next=target==null?old:old!=null&&old.target().equals(target)&&!retry?old:Binding.reading(target);
        if(target==null&&retry)next=Binding.reading(old.target());
        ItemStack result=convert(input,registries);
        if(target==null){if(name.isBlank())result.remove(DataComponents.CUSTOM_NAME);else result.set(DataComponents.CUSTOM_NAME,Component.literal(name));}
        else if(result.has(DataComponents.CUSTOM_NAME)){
            try{MediaTarget.parse(result.getCustomName().getString());result.remove(DataComponents.CUSTOM_NAME);}catch(IllegalArgumentException ignored){}
        }
        next.put(result);bounded(result.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag());return new Preview(result,next.token().equals(old==null?null:old.token())?null:next,(int)Math.min(39L,1L+input.getOrDefault(DataComponents.REPAIR_COST,0)));
    }
    public static ItemStack convert(ItemStack input,HolderLookup.Provider registries){
        if(input.getCount()!=1||!Binding.record(input))throw new IllegalArgumentException("仅支持单张合法唱片；保留输入");
        checkComponents(input);
        CompoundTag data=input.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();bounded(data);
        CompoundTag origin=null;
        if(StreamDiscItem.is(input)){
            if(data.contains(ORIGIN)){origin=data.getCompoundOrEmpty(ORIGIN);validateOrigin(origin,registries);}
        }else{
            if(data.contains(ORIGIN))throw new IllegalArgumentException("原唱片带未知来源记录；保留输入");
            Tag source=ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE,registries),input).getOrThrow();
            origin=new CompoundTag();origin.putInt("version",1);origin.put("source",source);origin.putInt("comparator",JukeboxSong.fromStack(input).map(s->s.value().comparatorOutput()).orElse(0));bounded(origin);
        }
        ItemStack result=new ItemStack(StreamDiscItem.ITEM);
        for(var type:CARRY)copy(result,type,input);
        if(origin!=null){CompoundTag saved=origin.copy();CustomData.update(DataComponents.CUSTOM_DATA,result,root->root.put(ORIGIN,saved));}
        return result;
    }
    private static <T> void copy(ItemStack out,DataComponentType<T> type,ItemStack input){if(input.has(type))out.set(type,input.get(type));}
    private static void checkComponents(ItemStack input){
        for(var entry:input.getComponentsPatch().entrySet()){
            var type=entry.getKey();var id=BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if(id==null||!id.getNamespace().equals("minecraft")||type.codec()==null||type==DataComponents.CONTAINER||type==DataComponents.BUNDLE_CONTENTS||type==DataComponents.CONTAINER_LOOT||type==DataComponents.CHARGED_PROJECTILES)throw new IllegalArgumentException("不支持复杂或第三方组件；保留输入");
        }
    }
    public static void validateOrigin(CompoundTag origin,HolderLookup.Provider registries){
        bounded(origin);if(origin.getIntOr("version",0)!=1||!origin.contains("source"))throw new IllegalArgumentException("来源结构无效；保留输入");
        ItemStack original=ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE,registries),origin.get("source")).getOrThrow();
        if(original.getCount()!=1||StreamDiscItem.is(original)||!Binding.record(original)||original.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains(ORIGIN))throw new IllegalArgumentException("来源不能递归嵌套；保留输入");
        int signal=JukeboxSong.fromStack(original).map(s->s.value().comparatorOutput()).orElse(0);if(origin.getIntOr("comparator",-1)!=signal)throw new IllegalArgumentException("来源信号无效；保留输入");
    }
    public static int comparator(ItemStack stack){
        if(!StreamDiscItem.is(stack))return JukeboxSong.fromStack(stack).map(s->s.value().comparatorOutput()).orElse(0);
        if(Binding.get(stack)==null)return 0;var o=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompoundOrEmpty(ORIGIN);int n=o.getIntOr("comparator",0);return o.getIntOr("version",0)==1&&o.contains("source")&&n>=0&&n<=15?n:0;
    }
    public static void bounded(Tag tag){
        walk(tag,0,new int[]{0});
        try{NbtIo.writeAnyTag(tag,new DataOutputStream(new OutputStream(){int bytes;public void write(int n)throws IOException{if(++bytes>MAX_BYTES)throw new IOException("source too large");}public void write(byte[] b,int off,int count)throws IOException{bytes+=count;if(bytes>MAX_BYTES)throw new IOException("source too large");}}));}
        catch(IOException e){throw new IllegalArgumentException("来源数据超过16KiB；保留输入");}
    }
    private static void walk(Tag tag,int depth,int[] nodes){if(depth>MAX_DEPTH||++nodes[0]>MAX_NODES)throw new IllegalArgumentException("来源数据过深或过多；保留输入");if(tag instanceof CompoundTag c)for(Tag child:c.values())walk(child,depth+1,nodes);else if(tag instanceof ListTag list)for(Tag child:list)walk(child,depth+1,nodes);}
    private DiscConversion(){}
}
