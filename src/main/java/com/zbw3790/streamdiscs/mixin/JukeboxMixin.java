package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxMixin {
    @Shadow private ItemStack item;
    @Shadow @Final private JukeboxSongPlayer jukeboxSongPlayer;
    @Shadow protected abstract void notifyItemChangedInJukebox(boolean inserted);
    @Inject(method="setTheItem",at=@At("HEAD"),cancellable=true)
    private void sd$insert(ItemStack stack,CallbackInfo ci){
        JukeboxBlockEntity b=(JukeboxBlockEntity)(Object)this;
        if(!StreamDiscItem.is(stack)&&Binding.get(stack)==null&&!StreamDiscItem.is(item)&&Binding.get(item)==null)return;
        ServerState.stop(b,"removed_or_replaced");
        if(!StreamDiscItem.is(stack)&&Binding.get(stack)==null)return; // Vanilla handles a normal record.
        if(b.getLevel()!=null)jukeboxSongPlayer.stop(b.getLevel(),b.getBlockState());
        item=stack;notifyItemChangedInJukebox(!stack.isEmpty());b.setChanged();ServerState.insert(b);ci.cancel();
    }
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private static void sd$tick(Level l,BlockPos pos,BlockState state,JukeboxBlockEntity b,CallbackInfo ci){if(StreamDiscItem.is(b.getTheItem())||Binding.get(b.getTheItem())!=null){ServerState.tickBox(b);ci.cancel();}}
    @Inject(method="canPlaceItem",at=@At("HEAD"),cancellable=true)
    private void sd$hopper(int slot,ItemStack stack,CallbackInfoReturnable<Boolean> ci){if(StreamDiscItem.is(stack))ci.setReturnValue(item.isEmpty()&&Binding.get(stack)!=null);}
    @Inject(method="getComparatorOutput",at=@At("HEAD"),cancellable=true)
    private void sd$comparator(CallbackInfoReturnable<Integer> ci){if(StreamDiscItem.is(item))ci.setReturnValue(DiscConversion.comparator(item));}
    @Inject(method="setRemoved",at=@At("HEAD")) private void sd$unload(CallbackInfo ci){ServerState.stop((JukeboxBlockEntity)(Object)this,"unload");}
}
