package com.zbw3790.streamdiscs.client;
import com.zbw3790.streamdiscs.core.*;
import java.io.IOException;

/** Acquisition is client-only. A package-local seam permits isolated deterministic tests. */
public final class ClientMediaAccess {
    public interface Provider {
        BilibiliSource.Metadata metadata(MediaTarget target,Cancellation token)throws IOException;
        BilibiliSource.Resolved audio(BilibiliSource.Metadata metadata,Cancellation token)throws IOException;
        SafeHttp http();
    }
    static Provider provider=new Provider(){
        public SafeHttp http(){return new SafeHttp(s->org.slf4j.LoggerFactory.getLogger("stream_discs").info(s));}
        public BilibiliSource.Metadata metadata(MediaTarget t,Cancellation c)throws IOException{return new BilibiliSource(new SafeHttp(s->{},1)).metadata(t,c);}
        public BilibiliSource.Resolved audio(BilibiliSource.Metadata m,Cancellation c)throws IOException{return new BilibiliSource(http()).audio(m,c);}
    };
    private ClientMediaAccess(){}
}
