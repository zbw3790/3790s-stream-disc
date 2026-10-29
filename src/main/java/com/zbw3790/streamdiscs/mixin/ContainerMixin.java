package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(RandomizableContainerBlockEntity.class)
public class ContainerMixin {
    @Inject(method="setItem",at=@At("TAIL"))
    private void sd$watch(int slot,ItemStack stack,CallbackInfo ci){if(Binding.get(stack)!=null)ServerState.observe((RandomizableContainerBlockEntity)(Object)this);}
    @Inject(method="getItem",at=@At("RETURN"))
    private void sd$refresh(int slot,CallbackInfoReturnable<ItemStack> ci){var b=(RandomizableContainerBlockEntity)(Object)this;if(b.getLevel() instanceof ServerLevel&&ServerState.refresh(ci.getReturnValue()))b.setChanged();}
}
