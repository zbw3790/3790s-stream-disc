package com.zbw3790.streamdiscs.mixin;

import com.mojang.blaze3d.audio.Channel;
import com.zbw3790.streamdiscs.client.TrackedStream;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    @Shadow @Final private int source;
    @Shadow private AudioStream stream;
    @Unique private int streamDiscsTicks;
    @Redirect(method="removeProcessedBuffers",at=@At(value="INVOKE",target="Lorg/lwjgl/openal/AL10;alSourceUnqueueBuffers(I[I)V"))
    private void streamDiscsProcessed(int sourceId,int[] buffers){
        AL10.alSourceUnqueueBuffers(sourceId,buffers);
        if(stream instanceof TrackedStream tracked)for(int buffer:buffers){
            int bytes=AL10.alGetBufferi(buffer,AL10.AL_SIZE),bits=AL10.alGetBufferi(buffer,AL10.AL_BITS),channels=AL10.alGetBufferi(buffer,AL10.AL_CHANNELS),rate=AL10.alGetBufferi(buffer,AL10.AL_FREQUENCY);
            if(bits>0&&channels>0&&rate>0)tracked.processed(bytes/(double)(bits/8*channels*rate));
        }
    }
    @Inject(method="updateStream",at=@At("TAIL"))
    private void streamDiscsSnapshot(CallbackInfo ci){
        if(stream instanceof TrackedStream tracked){
            tracked.offset(AL10.alGetSourcef(source,org.lwjgl.openal.AL11.AL_SEC_OFFSET),AL10.alGetSourcei(source,AL10.AL_SOURCE_STATE)==AL10.AL_PLAYING);
            if(++streamDiscsTicks%20!=0)return;
            float[] p=new float[3];AL10.alGetSourcefv(source,AL10.AL_POSITION,p);tracked.snapshot(AL10.alGetSourcef(source,AL10.AL_GAIN),p[0],p[1],p[2]);
        }
    }
}
