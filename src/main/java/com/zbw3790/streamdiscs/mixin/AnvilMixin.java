package com.zbw3790.streamdiscs.mixin;

import com.zbw3790.streamdiscs.*;
import com.zbw3790.streamdiscs.core.MediaTarget;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(AnvilMenu.class)
public abstract class AnvilMixin extends ItemCombinerMenu {
    @Shadow private String itemName;
    @Shadow @Final private DataSlot cost;
    @Shadow private boolean onlyRenaming;
    @Shadow private int repairItemCountCost;
    @Unique private Binding sd$pending;
    @Unique private ItemStack sd$source=ItemStack.EMPTY;
    public AnvilMixin(MenuType<?> t,int i,Inventory inv,ContainerLevelAccess a,ItemCombinerMenuSlotDefinition d){super(t,i,inv,a,d);}
    @Inject(method="setItemName",at=@At("HEAD"),cancellable=true)
    private void sd$name(String name,CallbackInfoReturnable<Boolean> ci){
        if(!Binding.record(inputSlots.getItem(0)))return;
        String filtered=StringUtil.filterText(name);
        if(filtered.length()>2048){sd$pending=null;resultSlots.setItem(0,ItemStack.EMPTY);cost.set(0);broadcastChanges();ci.setReturnValue(false);return;}
        boolean changed=!filtered.equals(itemName);itemName=filtered;
        // Same-target retries must also recreate a result.
        createResult();
        if(changed&&player instanceof ServerPlayer sp&&resultSlots.getItem(0).isEmpty()&&(filtered.contains("://")||filtered.startsWith("http")||filtered.startsWith("b23.")||filtered.startsWith("BV")||filtered.length()>50))sp.sendSystemMessage(Component.literal("[Stream Disc] 输入无效或数据无法安全转换：仅支持单张唱片与标准BV/链接；未发起网络请求、保留输入"));
        ci.setReturnValue(changed);
    }
    @Inject(method="createResult",at=@At("HEAD"),cancellable=true)
    private void sd$preview(CallbackInfo ci){
        sd$pending=null;sd$source=ItemStack.EMPTY;ItemStack input=inputSlots.getItem(0);
        if(!Binding.record(input))return;
        DiscConversion.Preview preview;
        try{preview=!inputSlots.getItem(1).isEmpty()||input.getCount()!=1?DiscConversion.empty():DiscConversion.prepare(input,itemName,player.level().registryAccess());}
        catch(RuntimeException rejected){preview=DiscConversion.empty();}
        if(preview==null)return;
        sd$pending=preview.submit();if(!preview.stack().isEmpty())sd$source=input.copy();repairItemCountCost=0;onlyRenaming=true;cost.set(preview.cost());resultSlots.setItem(0,preview.stack());broadcastChanges();ci.cancel();
    }
    @Inject(method="onTake",at=@At("HEAD"))
    private void sd$commit(Player p,ItemStack result,CallbackInfo ci){
        Binding pending=sd$pending;ItemStack source=sd$source;sd$pending=null;sd$source=ItemStack.EMPTY;
        // QUICK_MOVE passes the depleted result stack. Authorize against the server's preview input,
        // not that depleted argument; vanilla has already checked the result slot and payment.
        if(p instanceof ServerPlayer sp&&p==player&&pending!=null&&inputSlots.getItem(1).isEmpty()&&inputSlots.getItem(0).getCount()==1&&ItemStack.isSameItemSameComponents(source,inputSlots.getItem(0)))ServerState.commit(sp,pending);
    }
}
