package com.zbw3790.streamdiscs.core;

import java.io.IOException;
import java.nio.*;
import java.util.function.Consumer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

public final class AudioDecoder {
    private AudioDecoder() {}
    public static void decode(RangeChannel channel,double target,PcmPipe pipe,Cancellation token,Runnable ready,Consumer<String> log) throws IOException {
        long start=System.nanoTime();
        Mp4Audio media=new Mp4Audio(channel,target);
        Decoder decoder=new Decoder(media.config);
        SampleBuffer decoded=new SampleBuffer();
        boolean started=false,notified=false;
        long frames=0;
        Mp4Audio.Sample packet;
        while((packet=media.next())!=null) {
            token.check(); decoder.decodeFrame(packet.data(),decoded); decoded.setBigEndian(false);
            int channels=decoded.getChannels(), rate=decoded.getSampleRate();
            if(channels<1 || channels>2 || rate<8000 || rate>48000 || decoded.getBitsPerSample()!=16 || decoded.getData().length==0)
                throw new IOException("FORMAT: unsupported PCM output");
            if(pipe.sampleRate!=0 && pipe.sampleRate!=rate)throw new IOException("FORMAT: sample rate changed");
            pipe.sampleRate=rate;
            int samples=decoded.getData().length/(2*channels);
            int skip=(int)Math.max(0,Math.min(samples,Math.ceil((target-packet.pts())*rate)));
            if(skip>=samples) {
                if(channel.bytesRead>8L*1024*1024 && !started) throw new IOException("FORMAT: seek byte budget exceeded");
                continue;
            }
            byte[] mono=downmix(decoded.getData(),channels,skip);
            if(!started){
                started=true;pipe.firstPts=packet.pts()+skip/(double)rate;
                log.accept("seek target="+target+" actualFirstPts="+pipe.firstPts+" errorMs="+((pipe.firstPts-target)*1000)
                        +" bytesBeforePcm="+channel.bytesRead+" requests="+channel.requests+" elapsedMs="+(System.nanoTime()-start)/1_000_000+" fragmented="+media.fragmented+" inputChannels="+channels+" outputChannels=1 sampleRate="+rate);
            }
            pipe.put(mono,0,mono.length,token);frames+=mono.length/2;
            if(!notified&&frames>=rate*4L){notified=true;ready.run();}
        }
        if(!started)throw new IOException("FORMAT: no decoded audio");
        pipe.finish();if(!notified)ready.run();log.accept("decode_end frames="+frames+" compressedBytes="+channel.bytesRead);
    }
    public static void tone(double target,PcmPipe pipe,Cancellation token,Runnable ready) throws IOException {
        pipe.sampleRate=48000;pipe.firstPts=Math.ceil(target*48000)/48000;
        long from=(long)Math.ceil(target*48000),end=650L*48000;
        if(from>=end)throw new IOException("INPUT: progress exceeds tone duration");
        for(long frame=from;frame<end;frame+=960){
            token.check();int count=(int)Math.min(960,end-frame);byte[] stereo=new byte[count*4];ByteBuffer b=ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<count;i++){
                int marker=(int)((frame+i)/48000)%3;
                b.putShort(marker==1?0:(short)(Math.sin(2*Math.PI*(frame+i)*440/48000)*10000));
                b.putShort(marker==0?0:(short)(Math.sin(2*Math.PI*(frame+i)*880/48000)*10000));
            }
            byte[] data=downmix(stereo,2,0);
            pipe.put(data,0,data.length,token);if(frame-from<192000&&frame-from+data.length/2>=192000)ready.run();
        }
        pipe.finish();if(end-from<192000)ready.run();
    }
    public static byte[] downmix(byte[] pcm,int channels,int skip) throws IOException {
        if(channels<1||channels>2||pcm.length%(channels*2)!=0||skip<0||skip>pcm.length/(channels*2))throw new IOException("FORMAT: invalid PCM geometry");
        int samples=pcm.length/(channels*2);byte[] mono=new byte[(samples-skip)*2];
        ByteBuffer input=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN),output=ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN);
        input.position(skip*channels*2);
        for(int i=skip;i<samples;i++){int value=input.getShort();if(channels==2)value=(value+input.getShort())/2;output.putShort((short)value);}
        return mono;
    }
}
