package com.zbw3790.streamdiscs.core;

import org.jcodec.codecs.aac.AACUtils;
import org.jcodec.containers.mp4.*;
import org.jcodec.containers.mp4.boxes.*;
import org.jcodec.containers.mp4.demuxer.*;
import org.jcodec.common.model.Packet;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded AAC-only MP4 reader. Fragment seeking uses sidx and tfdt timestamps. */
public final class Mp4Audio {
    public record Sample(byte[] data, double pts, double duration) {}
    private record Atom(String type, long offset, long size, int header) {}
    private record Segment(long offset, long size, double start, double duration) {}
    private record Frame(long offset, int size, long pts, long duration) {}
    private final RangeChannel channel;
    private final ArrayList<Segment> segments = new ArrayList<>();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private AbstractMP4DemuxerTrack classic;
    private int timescale, trackId, defaultDuration, defaultSize, segment;
    public final byte[] config;
    public final boolean fragmented;
    public final double duration;

    public Mp4Audio(RangeChannel channel, double target) throws IOException {
        this.channel = channel;
        if (!Double.isFinite(target) || target < 0) throw new IOException("INPUT: invalid progress");
        try {
            Atom moov = null, sidx = null;
            long offset = 0;
            for (int count=0; count<2048 && offset<channel.size(); count++) {
                Atom atom = atom(offset);
                if (atom.type.equals("moov")) { bounded(atom, 2*1024*1024); moov=atom; }
                if (atom.type.equals("sidx")) { bounded(atom, 256*1024); sidx=atom; }
                if (atom.type.equals("moof") && moov != null) break;
                offset += atom.size;
            }
            if (moov == null) throw new IOException("FORMAT: missing moov");
            byte[] movieBytes = channel.at(moov.offset, (int)moov.size);
            validateTree(ByteBuffer.wrap(movieBytes), 0, new int[]{0,0});
            MovieBox movie = (MovieBox)BoxUtil.parseChildBox(ByteBuffer.wrap(movieBytes), BoxFactory.getDefault());
            if (movie.getTracks().length != 1 || movie.getAudioTracks().size() != 1) throw new IOException("FORMAT: audio-only MP4 required");
            TrakBox track = movie.getAudioTracks().getFirst();
            timescale=track.getTimescale(); trackId=track.getTrackHeader().getNo();
            if (timescale<8000 || timescale>192000 || track.getSampleEntries().length != 1 || !track.getSampleEntries()[0].getFourcc().equals("mp4a"))
                throw new IOException("FORMAT: unsupported AAC track");
            ByteBuffer extra=AACUtils.getCodecPrivate(track.getSampleEntries()[0]);
            if(extra==null || extra.remaining()<2 || extra.remaining()>64) throw new IOException("FORMAT: missing AAC config");
            config=new byte[extra.remaining()]; extra.get(config);
            if ((config[0] & 0xf8) != 0x10) throw new IOException("FORMAT: only AAC-LC supported");
            int frequencyIndex=((config[0]&7)<<1)|((config[1]&0x80)>>>7), channels=(config[1]>>>3)&15;
            if(frequencyIndex<3||frequencyIndex>11||channels<1||channels>2)throw new IOException("FORMAT: unsupported AAC rate or channels");
            fragmented = sidx != null;
            if (fragmented) {
                parseSidx(sidx);
                readTrex(ByteBuffer.wrap(movieBytes));
                Segment last=segments.getLast(); duration=last.start+last.duration;
                if (target>=duration) throw new IOException("INPUT: progress exceeds duration");
                double preroll=Math.max(0,target-0.12);
                while(segment+1<segments.size() && segments.get(segment+1).start<=preroll) segment++;
                if(target-segments.get(segment).start>60) throw new IOException("FORMAT: excessive seek preroll");
            } else {
                MP4Demuxer demuxer=MP4Demuxer.createRawMP4Demuxer(channel);
                classic=(AbstractMP4DemuxerTrack)demuxer.getAudioTracks().getFirst();
                duration=classic.getDuration().scalar();
                if(target>=duration) throw new IOException("INPUT: progress exceeds duration");
                classic.seek(Math.max(0,target-0.12));
            }
        } catch (BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("FORMAT: malformed MP4", e);
        }
    }
    private void bounded(Atom atom,int max) throws IOException { if(atom.size>max) throw new IOException("FORMAT: oversized "+atom.type); }
    private Atom atom(long offset) throws IOException {
        ByteBuffer b=ByteBuffer.wrap(channel.at(offset, (int)Math.min(16,channel.size()-offset)));
        long size=Integer.toUnsignedLong(b.getInt()); byte[] type=new byte[4]; b.get(type); int header=8;
        if(size==1){size=b.getLong();header=16;}
        if(size==0) size=channel.size()-offset;
        if(size<header || size>channel.size()-offset) throw new IOException("FORMAT: invalid atom extent");
        return new Atom(new String(type,StandardCharsets.ISO_8859_1),offset,size,header);
    }
    private static void validateTree(ByteBuffer data,int depth,int[] count) throws IOException {
        if(depth>12) throw new IOException("FORMAT: excessive nesting");
        while(data.remaining()>=8) {
            int start=data.position(), size=data.getInt(); byte[] bytes=new byte[4];data.get(bytes);
            String type=new String(bytes,StandardCharsets.ISO_8859_1);
            if(size<8 || size>data.limit()-start || ++count[0]>4096) throw new IOException("FORMAT: invalid metadata box");
            ByteBuffer child=data.slice();child.limit(size-8);
            if(Set.of("moov","trak","mdia","minf","stbl","edts","mvex","udta").contains(type)) validateTree(child,depth+1,count);
            if(Set.of("stts","ctts","stsc","stco","co64","stss","elst","stsd","stsz").contains(type)) {
                if(child.remaining()<8) throw new IOException("FORMAT: truncated table");
                child.getInt();if(type.equals("stsz")) child.getInt();
                long entries=Integer.toUnsignedLong(child.getInt());
                if(entries>250000 || (type.equals("stsd") && entries>8)) throw new IOException("FORMAT: table limit");
                count[1]+=Math.toIntExact(entries);
                if(count[1]>500000)throw new IOException("FORMAT: aggregate table limit");
            }
            data.position(start+size);
        }
        if(data.hasRemaining()) throw new IOException("FORMAT: trailing metadata");
    }
    private void parseSidx(Atom atom) throws IOException {
        ByteBuffer b=ByteBuffer.wrap(channel.at(atom.offset+atom.header,(int)atom.size-atom.header));
        int version=b.getInt()>>>24; b.getInt(); long scale=Integer.toUnsignedLong(b.getInt());
        if(scale==0 || version>1) throw new IOException("FORMAT: invalid sidx");
        long time=version==0?Integer.toUnsignedLong(b.getInt()):b.getLong();
        long first=version==0?Integer.toUnsignedLong(b.getInt()):b.getLong();
        if(time<0 || first<0 || first>channel.size()-atom.offset-atom.size) throw new IOException("FORMAT: sidx overflow");
        b.getShort();int count=Short.toUnsignedInt(b.getShort());
        if(count<1 || count>10000 || count>b.remaining()/12) throw new IOException("FORMAT: sidx count");
        long offset=atom.offset+atom.size+first;
        for(int i=0;i<count;i++) {
            int ref=b.getInt();long ticks=Integer.toUnsignedLong(b.getInt()); b.getInt();
            long size=ref&0x7fffffffL;
            if(ref<0 || size<8 || size>16*1024*1024 || size>channel.size()-offset || ticks==0 || ticks/(double)scale>60)
                throw new IOException("FORMAT: unsupported segment");
            segments.add(new Segment(offset,size,time/(double)scale,ticks/(double)scale));offset+=size;time+=ticks;
        }
    }
    private void readTrex(ByteBuffer data) {
        // Find only validated box structures; trex is inside moov/mvex.
        while(data.remaining()>=8) {
            int start=data.position(), size=data.getInt(); int type=data.getInt();
            ByteBuffer child=data.slice();child.limit(size-8);
            if(type==0x6d6f6f76 || type==0x6d766578) readTrex(child);
            if(type==0x74726578 && child.remaining()>=24) {
                child.getInt();int id=child.getInt();child.getInt();
                if(id==trackId){defaultDuration=child.getInt();defaultSize=child.getInt();}
            }
            data.position(start+size);
        }
    }
    private void fragment(Segment segment) throws IOException {
        long offset=segment.offset, end=offset+segment.size;
        int boxes=0;
        while(offset<end) {
            if(++boxes>64) throw new IOException("FORMAT: fragment box limit");
            Atom atom=atom(offset);
            if(atom.offset+atom.size>end) throw new IOException("FORMAT: segment boundary");
            if(atom.type.equals("moof")) {
                bounded(atom,1024*1024);
                ByteBuffer b=ByteBuffer.wrap(channel.at(offset+atom.header,(int)atom.size-atom.header));
                while(b.remaining()>=8) {
                    int start=b.position(), size=b.getInt(), type=b.getInt();
                    if(size<8 || size>b.limit()-start) throw new IOException("FORMAT: moof child");
                    if(type==0x74726166){ByteBuffer traf=b.slice();traf.limit(size-8);parseTraf(traf,offset,end);}
                    b.position(start+size);
                }
            }
            offset+=atom.size;
        }
        if(frames.isEmpty()) throw new IOException("FORMAT: no AAC samples in fragment");
    }
    private void parseTraf(ByteBuffer traf,long moof,long segmentEnd) throws IOException {
        long base=moof, pts=-1, dataOffset=-1;
        int duration=defaultDuration,size=defaultSize,id=-1;
        ArrayList<ByteBuffer> runs=new ArrayList<>();
        while(traf.remaining()>=8) {
            int start=traf.position(), length=traf.getInt(), type=traf.getInt();
            if(length<8 || length>traf.limit()-start) throw new IOException("FORMAT: traf child");
            ByteBuffer b=traf.slice();b.limit(length-8);
            if(type==0x74666864) {
                int flags=b.getInt()&0xffffff;id=b.getInt();
                if((flags&1)!=0) base=b.getLong();
                if((flags&2)!=0) b.getInt();
                if((flags&8)!=0) duration=b.getInt();
                if((flags&16)!=0) size=b.getInt();
            } else if(type==0x74666474) {int version=b.getInt()>>>24;pts=version==1?b.getLong():Integer.toUnsignedLong(b.getInt());}
            else if(type==0x7472756e) runs.add(b);
            traf.position(start+length);
        }
        if(id!=trackId || pts<0) throw new IOException("FORMAT: invalid fragment identity or timestamp");
        for(ByteBuffer b:runs) {
            int vf=b.getInt(), flags=vf&0xffffff, version=vf>>>24;
            int count=b.getInt();
            if(count<0 || count>8192 || count+frames.size()>8192) throw new IOException("FORMAT: frame count limit");
            if((flags&1)!=0) dataOffset=base+b.getInt();
            if(dataOffset<moof) throw new IOException("FORMAT: missing data offset");
            if((flags&4)!=0)b.getInt();
            for(int i=0;i<count;i++) {
                long d=(flags&256)!=0?Integer.toUnsignedLong(b.getInt()):Integer.toUnsignedLong(duration);
                int n=(flags&512)!=0?b.getInt():size;
                if((flags&1024)!=0)b.getInt();
                long composition=0;
                if((flags&2048)!=0){int c=b.getInt();composition=version==1?c:Integer.toUnsignedLong(c);}
                if(n<1 || n>65536 || d<1 || d>timescale || dataOffset<moof || dataOffset>segmentEnd-n)
                    throw new IOException("FORMAT: invalid AAC sample extent");
                frames.add(new Frame(dataOffset,n,pts+composition,d));dataOffset+=n;pts+=d;
            }
        }
    }
    public Sample next() throws IOException {
        try {
            if(!fragmented) {
                Packet packet=classic.nextFrame();if(packet==null)return null;
                if(packet.data.remaining()>65536)throw new IOException("FORMAT: oversized AAC packet");
                byte[] bytes=new byte[packet.data.remaining()];packet.data.get(bytes);
                return new Sample(bytes,packet.getPtsD(),packet.getDurationD());
            }
            while(frames.isEmpty()) {if(segment>=segments.size())return null;fragment(segments.get(segment++));}
            Frame frame=frames.removeFirst();
            return new Sample(channel.at(frame.offset,frame.size),frame.pts/(double)timescale,frame.duration/(double)timescale);
        } catch (RuntimeException e) { throw new IOException("FORMAT: invalid fragment",e); }
    }
}
