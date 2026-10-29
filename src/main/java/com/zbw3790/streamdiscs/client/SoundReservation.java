package com.zbw3790.streamdiscs.client;
import net.minecraft.client.sounds.ChannelAccess;
import java.util.concurrent.CompletableFuture;
public interface SoundReservation {CompletableFuture<ChannelAccess.ChannelHandle> streamDiscs$reserve();}
