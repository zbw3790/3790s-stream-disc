package com.zbw3790.streamdiscs.core;

import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.sourceforge.jaad.aac.*;

public final class CoreTest {
    @org.junit.jupiter.api.Test
    void isolatedCoreRegression() throws Exception { main(new String[0]); }
    static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void rejected(Callable<?> test,String name)throws Exception{try{test.call();throw new AssertionError(name);}catch(IOException|IllegalArgumentException expected){check(true,name);}}
    static void serve(HttpExchange e,byte[] data)throws IOException{
        String range=e.getRequestHeaders().getFirst("Range");
        if(range==null){e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();return;}
        String[] p=range.substring(6).split("-");int from=Integer.parseInt(p[0]),to=(int)Math.min(data.length-1,Long.parseLong(p[1]));
        if(from>=data.length){e.sendResponseHeaders(416,-1);e.close();return;}
        e.getResponseHeaders().add("Content-Range","bytes "+from+"-"+to+"/"+data.length);
        e.sendResponseHeaders(206,to-from+1);e.getResponseBody().write(data,from,to-from+1);e.close();
    }
    public static void main(String[] args)throws Exception{
        check(MediaTarget.parse("BV13s411v7LA").page()==1,"bare BV");
        check(MediaTarget.parse("https://www.bilibili.com/video/BV13s411v7LA/?p=2&share_source=copy_web").page()==2,"preserve p=2");
        for(String bad:new String[]{"","BV1","file:///x","https://127.0.0.1/video/BV13s411v7LA","https://evil.test/video/BV13s411v7LA","https://www.bilibili.com.evil.test/video/BV13s411v7LA","http://www.bilibili.com/video/BV13s411v7LA","https://www.bilibili.com:444/video/BV13s411v7LA","https://www.bilibili.com/video/BV13s411v7LA?p=0","https://www.bilibili.com/video/BV13s411v7LA?p=2&p=3","https://b23.tv/abc","https://user@www.bilibili.com/video/BV13s411v7LA"})rejected(()->MediaTarget.parse(bad),"input reject "+checks);
        for(String ip:new String[]{"127.0.0.1","10.1.2.3","192.168.1.1","169.254.169.254","100.64.0.1","::1","fc00::1","fe80::1"})check(!SafeHttp.publicAddress(InetAddress.getByName(ip)),"private reject "+ip);
        SafeHttp production=new SafeHttp(s->{});
        rejected(()->{production.validate(URI.create("http://127.0.0.1:38790/media"));return null;},"production loopback disabled");
        Decoder aac=new Decoder(new byte[]{0x11,(byte)0x88});SampleBuffer decoded=new SampleBuffer();aac.decodeFrame(Fixture.silence(),decoded);
        check(decoded.getSampleRate()==48000&&decoded.getChannels()==1&&decoded.getData().length==2048,"original AAC silence decode");
        ByteBuffer markers=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putShort((short)20000).putShort((short)0).putShort((short)0).putShort((short)12000).putShort((short)32767).putShort((short)-32767);
        ByteBuffer mixed=ByteBuffer.wrap(AudioDecoder.downmix(markers.array(),2,0)).order(ByteOrder.LITTLE_ENDIAN);
        check(mixed.getShort()==10000&&mixed.getShort()==6000&&mixed.getShort()==0,"left/right markers and phase cancellation downmix");
        byte[] media=Fixture.media();
        AtomicInteger retry=new AtomicInteger(),exhausted=new AtomicInteger(),redirects=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",38790),0);
        ExecutorService httpThreads=Executors.newFixedThreadPool(4);server.setExecutor(httpThreads);
        server.createContext("/media",e->serve(e,media));
        server.createContext("/corrupt",e->serve(e,new byte[256]));
        byte[] oversized=Fixture.box("moov",Fixture.box("stsz",Fixture.ints(0,0,Integer.MAX_VALUE)));
        byte[] aggregate=Fixture.box("moov",Fixture.box("stsz",Fixture.ints(0,0,250000)),Fixture.box("stsz",Fixture.ints(0,0,250000)),Fixture.box("stsz",Fixture.ints(0,0,250000)));
        server.createContext("/oversized",e->serve(e,oversized));
        server.createContext("/aggregate",e->serve(e,aggregate));
        server.createContext("/ignore",e->{e.sendResponseHeaders(200,4);e.getResponseBody().write(new byte[4]);e.close();});
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","http://169.254.169.254/");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/loop",e->{redirects.incrementAndGet();e.getResponseHeaders().add("Location","/loop");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/retry",e->{if(retry.incrementAndGet()<3){e.sendResponseHeaders(503,-1);e.close();}else serve(e,new byte[16]);});
        server.createContext("/fail",e->{exhausted.incrementAndGet();e.sendResponseHeaders(503,-1);e.close();});
        server.createContext("/delay",e->{try{Thread.sleep(1500);serve(e,new byte[16]);}catch(Exception ignored){e.close();}});
        server.createContext("/timeout",e->{try{Thread.sleep(9000);serve(e,new byte[16]);}catch(Exception ignored){e.close();}});
        server.start();SafeHttp http=new SafeHttp(38790,System.out::println);URI base=URI.create("http://127.0.0.1:38790");
        try {
            for(double target:new double[]{0,30,300}) {
                Cancellation token=new Cancellation();PcmPipe pipe=new PcmPipe();
                try(RangeChannel channel=new RangeChannel(http,base.resolve("/media"),token)) {
                    AtomicReference<Throwable> error=new AtomicReference<>();
                    Thread worker=Thread.ofPlatform().name("fixture-decode").start(()->{try{AudioDecoder.decode(channel,target,pipe,token,()->{},System.out::println);}catch(Throwable t){if(!token.cancelled())error.set(t);}});
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(pipe.queuedBytes()<8192&&error.get()==null&&System.nanoTime()<deadline)Thread.sleep(5);
                    if(error.get()!=null)throw new AssertionError("decode",error.get());
                    check(Double.isFinite(pipe.firstPts)&&Math.abs(pipe.firstPts-target)<=.5,"controlled seek "+target);
                    ByteBuffer pcm=pipe.read(8192);int peak=0;while(pcm.remaining()>=2)peak=Math.max(peak,Math.abs(pcm.getShort()));
                    check(pipe.consumedFrames>0&&peak==0,"decoded content matches original silence "+target);
                    check(channel.bytesRead<media.length/2,"bounded non-prefix fetch "+target);
                    System.out.println("CONTROLLED target="+target+" firstPts="+pipe.firstPts+" errorMs="+Math.abs(pipe.firstPts-target)*1000+" bytes="+channel.bytesRead+" mediaSize="+media.length);
                    token.close();pipe.close();worker.interrupt();worker.join(2000);check(!worker.isAlive(),"cancel decoder "+target);
                }
            }
            for(int i=0;i<10;i++) {
                Cancellation token=new Cancellation();PcmPipe pipe=new PcmPipe();AtomicInteger callbacks=new AtomicInteger();
                Thread worker=Thread.ofPlatform().start(()->{try{AudioDecoder.tone(300,pipe,token,callbacks::incrementAndGet);}catch(IOException ignored){}});
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(pipe.queuedBytes()<PcmPipe.CAPACITY-2048&&System.nanoTime()<deadline)Thread.sleep(2);
                check(pipe.queuedBytes()<=PcmPipe.CAPACITY,"bounded backpressure round "+i);
                token.close();pipe.close();worker.interrupt();worker.join(2000);check(!worker.isAlive()&&pipe.queuedBytes()==0,"stop/release round "+i);
                pipe.close();token.close();check(pipe.read(8192).remaining()==0,"closed session cannot resume "+i);
            }
            for(String path:new String[]{"/ignore","/redirect","/loop","/fail"})rejected(()->http.get(base.resolve(path),0,16,new Cancellation()),"http reject "+path);
            check(exhausted.get()==3,"retry exhausted exactly 3");check(redirects.get()==4,"redirect bounded 3 hops");
            check(http.get(base.resolve("/retry"),0,16,new Cancellation()).bytes().length==16&&retry.get()==3,"recover within retry cap");
            rejected(()->new Mp4Audio(new RangeChannel(http,base.resolve("/corrupt"),new Cancellation()),0),"corrupt format rejected");
            rejected(()->new Mp4Audio(new RangeChannel(http,base.resolve("/oversized"),new Cancellation()),0),"oversized sample table rejected before allocation");
            rejected(()->new Mp4Audio(new RangeChannel(http,base.resolve("/aggregate"),new Cancellation()),0),"aggregate sample tables rejected before allocation");
            Cancellation delayed=new Cancellation();AtomicBoolean returned=new AtomicBoolean();
            Thread old=Thread.ofPlatform().start(()->{try{http.get(base.resolve("/delay"),0,16,delayed);}catch(IOException expected){}returned.set(true);});
            Thread.sleep(100);delayed.close();old.interrupt();old.join(2500);check(returned.get(),"cancel delayed request");
            long start=System.nanoTime();rejected(()->http.get(base.resolve("/timeout"),0,16,new Cancellation()),"read timeout finite");
            check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<35,"timeout total bounded below 35s");
            PcmPipe empty=new PcmPipe();long ns=System.nanoTime();empty.read(16384);check(System.nanoTime()-ns<TimeUnit.MILLISECONDS.toNanos(50),"underrun read nonblocking");
            System.out.println("CORE_TEST_PASS checks="+checks);
        } finally {server.stop(0);httpThreads.shutdownNow();}
    }
}
