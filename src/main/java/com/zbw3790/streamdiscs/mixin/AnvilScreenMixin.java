package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.Binding;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AnvilScreen.class)
public class AnvilScreenMixin {
    @Shadow private EditBox name;
    @Shadow @Final private Player player;
    @Inject(method="subInit",at=@At("TAIL"))
    private void sd$initialLimit(CallbackInfo ci){name.setMaxLength(Binding.record(player.containerMenu.getSlot(0).getItem())?2048:50);}
    @Inject(method="slotChanged",at=@At("HEAD"))
    private void sd$limit(AbstractContainerMenu menu,int slot,ItemStack stack,CallbackInfo ci){if(slot==0&&name!=null&&Binding.record(stack))name.setMaxLength(2048);}
    @Inject(method="slotChanged",at=@At("TAIL"))
    private void sd$restoreLimit(AbstractContainerMenu menu,int slot,ItemStack stack,CallbackInfo ci){if(slot==0&&name!=null&&!Binding.record(stack))name.setMaxLength(50);}
}
