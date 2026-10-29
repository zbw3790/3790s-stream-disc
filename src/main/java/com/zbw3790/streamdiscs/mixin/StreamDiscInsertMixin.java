package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(JukeboxBlock.class)
public class StreamDiscInsertMixin {
    @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
    private void sd$insert(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> ci){
        if(!StreamDiscItem.is(stack))return;
        if(state.getValue(JukeboxBlock.HAS_RECORD))return; // Empty-hand vanilla pop-out path.
        if(Binding.get(stack)==null){ci.setReturnValue(InteractionResult.FAIL);return;}
        if(!level.isClientSide()&&level.getBlockEntity(pos) instanceof JukeboxBlockEntity box){box.setTheItem(stack.copyWithCount(1));stack.consume(1,player);}
        ci.setReturnValue(InteractionResult.SUCCESS);
    }
}
