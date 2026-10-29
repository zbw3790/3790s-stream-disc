package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.ServerState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemEntity.class)
public class DropMixin {
    @Inject(method="tick",at=@At("HEAD")) private void sd$watch(CallbackInfo ci){ServerState.observe((ItemEntity)(Object)this);}
}
