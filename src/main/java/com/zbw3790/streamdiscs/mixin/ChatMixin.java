package com.zbw3790.streamdiscs.mixin;

import com.zbw3790.streamdiscs.client.StreamDiscsClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ChatMixin {
    @Inject(method="sendCommand",at=@At("HEAD"),cancellable=true)
    private void streamDiscsCommand(String command,CallbackInfo ci){if(StreamDiscsClient.INSTANCE.command(command))ci.cancel();}
}
