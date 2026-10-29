package com.zbw3790.streamdiscs.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.jcodec.containers.mp4.*;
import org.jcodec.containers.mp4.boxes.*;

/** Original synthetic AAC-LC silence: zero scalefactor bands, SCE + END. */
final class Fixture {
    static byte[] silence(){
        String bits="000"+"0000"+"01100100"+"0"+"00"+"0"+"000000"+"0"+"0"+"0"+"0"+"111";
        byte[] out=new byte[(bits.length()+7)/8];
        for(int i=0;i<bits.length();i++)if(bits.charAt(i)=='1')out[i/8]|=(byte)(1<<(7-i%8));return java.util.Arrays.copyOf(out,256);
    }
    static byte[] box(String type,byte[]...parts)throws IOException{
        ByteArrayOutputStream data=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(data);
        int size=8;for(byte[] p:parts)size+=p.length;out.writeInt(size);out.write(type.getBytes(StandardCharsets.ISO_8859_1));for(byte[]p:parts)out.write(p);return data.toByteArray();
    }
    static byte[] ints(int...values){ByteBuffer b=ByteBuffer.allocate(values.length*4);for(int v:values)b.putInt(v);return b.array();}
    static byte[] bytes(ByteBuffer data){byte[] out=new byte[data.remaining()];data.get(out);return out;}
    static byte[] media()throws Exception{
        // JCodec creates only the init metadata. All media/sample timestamps are authored here.
        MovieBox movie=MovieBox.createMovieBox();
        movie.add(MovieHeaderBox.createMovieHeaderBox(48000,650L*48000,1,1,0,0,new int[]{65536,0,0,0,65536,0,0,0,1073741824},2));
        TrakBox track=TrakBox.createTrakBox();
        track.add(TrackHeaderBox.createTrackHeaderBox(1,650L*48000,0,0,0,0,1,(short)0,0,new int[]{65536,0,0,0,65536,0,0,0,1073741824}));
        MediaBox mdia=MediaBox.createMediaBox();
        mdia.add(MediaHeaderBox.createMediaHeaderBox(48000,650L*48000,0,0,0,0));
        mdia.add(HandlerBox.createHandlerBox("mhlr","soun","appl",0,0));
        MediaInfoBox minf=MediaInfoBox.createMediaInfoBox();
        NodeBox stbl=new NodeBox(new Header("stbl"));
        AudioSampleEntry entry=AudioSampleEntry.audioSampleEntry("mp4a",1,16,1,48000,java.nio.ByteOrder.LITTLE_ENDIAN);
        entry.add(org.jcodec.codecs.mpeg4.mp4.EsdsBox.fromADTS(org.jcodec.codecs.aac.ADTSParser.read(ByteBuffer.wrap(new byte[]{(byte)0xff,(byte)0xf1,0x4c,0x40,1,0x3f,(byte)0xfc}))));
        stbl.add(SampleDescriptionBox.createSampleDescriptionBox(new SampleEntry[]{entry}));
        minf.add(stbl);mdia.add(minf);track.add(mdia);movie.add(track);
        byte[] moov=bytes(MP4Util.writeBox(movie,16384));
        int perSegment=240,segments=128;byte[] aac=silence();
        byte[][] fragments=new byte[segments][];
        for(int i=0;i<segments;i++) {
            byte[] tfhd=box("tfhd",ints(0x020018,1,1024,aac.length));
            ByteBuffer tfdt=ByteBuffer.allocate(12).putInt(0x01000000).putLong((long)i*perSegment*1024);tfdt.flip();
            byte[] run=box("trun",ints(1,perSegment,0));
            byte[] moof=box("moof",box("mfhd",ints(0,i+1)),box("traf",tfhd,box("tfdt",bytes(tfdt)),run));
            run=box("trun",ints(1,perSegment,moof.length+8));
            moof=box("moof",box("mfhd",ints(0,i+1)),box("traf",tfhd,box("tfdt",ints(0,i*perSegment*1024)),run));
            // tfdt version 0 is smaller; recompute offset after final layout.
            run=box("trun",ints(1,perSegment,moof.length+8));
            moof=box("moof",box("mfhd",ints(0,i+1)),box("traf",tfhd,box("tfdt",ints(0,i*perSegment*1024)),run));
            ByteArrayOutputStream samples=new ByteArrayOutputStream();for(int n=0;n<perSegment;n++)samples.write(aac);
            fragments[i]=concat(moof,box("mdat",samples.toByteArray()));
        }
        ByteBuffer idx=ByteBuffer.allocate(24+segments*12);idx.putInt(0).putInt(1).putInt(48000).putInt(0).putInt(0).putShort((short)0).putShort((short)segments);
        for(byte[] f:fragments)idx.putInt(f.length).putInt(perSegment*1024).putInt(0x90000000);
        idx.flip();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(box("ftyp",ints(0x69736f36,0,0x69736f36)));out.write(moov);out.write(box("sidx",bytes(idx)));for(byte[]f:fragments)out.write(f);return out.toByteArray();
    }
    static byte[] concat(byte[]...parts)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[]p:parts)out.write(p);return out.toByteArray();}
}
