package com.zbw3790.streamdiscs.mixin;
import com.zbw3790.streamdiscs.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
@Mixin(ItemStack.class)
public class DiscTooltipMixin {
    @Inject(method="getTooltipLines",at=@At("RETURN"),cancellable=true)
    private void sd$tooltip(CallbackInfoReturnable<List<Component>> ci){
        ItemStack stack=(ItemStack)(Object)this;Binding b=Binding.get(stack);
        if(b==null){if(StreamDiscItem.is(stack)){List<Component> empty=new ArrayList<>(ci.getReturnValue());empty.add(Component.literal(DiscConversion.hasBindingData(stack)?"[Stream Disc] 绑定结构无效；保留数据，无法播放":"[Stream Disc] 未绑定；在铁砧输入BV并领取"));ci.setReturnValue(empty);}return;}
        List<Component> lines=new ArrayList<>(ci.getReturnValue());
        lines.add(Component.literal("[Stream Disc] "+b.state()+" · "+b.target().bvid()+" · P"+b.target().page()));
        if(b.state().equals("READY")){lines.add(Component.literal(b.title()));int seconds=(int)b.duration();lines.add(Component.literal("时长 "+seconds/60+":"+String.format(java.util.Locale.ROOT,"%02d",seconds%60)));}
        else if(b.pending())lines.add(Component.literal("异步读取 "+Math.max(1,b.attempt())+"/3；可移动、存入箱子"));
        else lines.add(Component.literal(b.error()+"；在铁砧领取以重试"));
        ci.setReturnValue(lines);
    }
}
