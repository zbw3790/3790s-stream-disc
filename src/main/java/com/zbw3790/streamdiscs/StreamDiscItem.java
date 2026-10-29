package com.zbw3790.streamdiscs;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;

/** Common-side identity. A bare item intentionally has no vanilla song component. */
public final class StreamDiscItem {
    public static final Identifier ID=Identifier.fromNamespaceAndPath("stream_discs","stream_disc");
    public static final Item ITEM=Registry.register(BuiltInRegistries.ITEM,ID,new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM,ID)).stacksTo(1)));
    public static void init(){}
    public static boolean is(ItemStack stack){return !stack.isEmpty()&&stack.is(ITEM);}
    private StreamDiscItem(){}
}
