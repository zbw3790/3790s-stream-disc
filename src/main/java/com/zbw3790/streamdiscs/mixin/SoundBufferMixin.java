package com.zbw3790.streamdiscs.mixin;

import com.zbw3790.streamdiscs.client.StreamDiscsClient;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferMixin {
    @Inject(method="getStream",at=@At("HEAD"),cancellable=true)
    private void streamDiscsStream(Identifier path,boolean looping,CallbackInfoReturnable<CompletableFuture<AudioStream>> ci){
        CompletableFuture<AudioStream> stream=StreamDiscsClient.INSTANCE.stream(path);if(stream!=null)ci.setReturnValue(stream);
    }
}
