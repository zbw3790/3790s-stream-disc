package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(JukeboxBlock.class)
public class JukeboxSignalMixin {
    @Inject(method="ownSignal",at=@At("HEAD"),cancellable=true)
    private void sd$signal(BlockState state,BlockGetter level,BlockPos pos,CallbackInfoReturnable<Integer> ci){if(level.getBlockEntity(pos) instanceof JukeboxBlockEntity b&&Binding.get(b.getTheItem())!=null)ci.setReturnValue(ServerState.active(b)?15:0);}
}
