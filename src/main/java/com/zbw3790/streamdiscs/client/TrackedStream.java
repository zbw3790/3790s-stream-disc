package com.zbw3790.streamdiscs.client;

import net.minecraft.client.sounds.AudioStream;

public interface TrackedStream extends AudioStream {
    void processed(double seconds);
    default void offset(double seconds,boolean playing){}
    void snapshot(float gain,float x,float y,float z);
}
