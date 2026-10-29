package com.zbw3790.streamdiscs.mixin;

import com.zbw3790.streamdiscs.client.StreamDiscsClient;
import com.zbw3790.streamdiscs.client.ClientRequests;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void streamDiscsTick(CallbackInfo ci){StreamDiscsClient.INSTANCE.tick();}
    @Inject(method={"setLevel","disconnect"},at=@At("HEAD"))
    private void streamDiscsWorldReplacement(CallbackInfo ci){ClientRequests.leaveWorld();StreamDiscsClient.INSTANCE.stop("world_replaced");}
    @Inject(method="clearClientLevel",at=@At("HEAD"))
    private void streamDiscsDisconnect(CallbackInfo ci){StreamDiscsClient.INSTANCE.stop("disconnect");ClientRequests.cancel();}
    @Inject(method="stop",at=@At("HEAD"))
    private void streamDiscsStopRequested(CallbackInfo ci){
        Minecraft mc=(Minecraft)(Object)this;
        Runnable stop=()->{StreamDiscsClient.INSTANCE.stop("client_stop");ClientRequests.cancel();};
        if(mc.isSameThread())stop.run();else mc.execute(stop);
    }
    @Inject(method="close",at=@At("HEAD"))
    private void streamDiscsClose(CallbackInfo ci){StreamDiscsClient.INSTANCE.close();}
}
