package com.zbw3790.streamdiscs.mixin;
import com.mojang.blaze3d.audio.Library;
import com.zbw3790.streamdiscs.client.*;
import net.minecraft.client.sounds.*;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.concurrent.CompletableFuture;
@Mixin(SoundEngine.class)
public abstract class SoundEngineReservationMixin implements SoundReservation {
    @Shadow private boolean loaded;
    @Shadow @Final private ChannelAccess channelAccess;
    public CompletableFuture<ChannelAccess.ChannelHandle> streamDiscs$reserve(){return loaded?channelAccess.createHandle(Library.Pool.STREAMING):CompletableFuture.completedFuture(null);}
    @Redirect(method="play",at=@At(value="INVOKE",target="Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChannelAccess.ChannelHandle> streamDiscs$claim(ChannelAccess access,Library.Pool pool,SoundInstance instance){
        var reserved=StreamDiscsClient.INSTANCE.claimReservation(instance);return reserved!=null?reserved:access.createHandle(pool);
    }
}
