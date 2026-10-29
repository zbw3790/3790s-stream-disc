package com.zbw3790.streamdiscs.mixin;
import net.minecraft.client.sounds.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {@Accessor("soundEngine") SoundEngine streamDiscs$engine();}
