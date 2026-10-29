package com.zbw3790.streamdiscs.core;
import com.zbw3790.streamdiscs.ItemCapability;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
class ItemCapabilityTest {
    @Test void exactCandidateAndRegistrationRequired(){var id=UUID.randomUUID();var p=new ItemCapability(id,"0.1.0-m3d","a".repeat(64),1400);assertTrue(p.matches(p));assertFalse(p.matches(new ItemCapability(UUID.randomUUID(),p.version(),p.hash(),p.rawId())));assertFalse(p.matches(new ItemCapability(id,"old",p.hash(),p.rawId())));assertFalse(p.matches(new ItemCapability(id,p.version(),"b".repeat(64),p.rawId())));assertFalse(p.matches(new ItemCapability(id,p.version(),p.hash(),1401)));}
    @Test void actualConfigurationCodecRoundTripAndBounds(){var b=new FriendlyByteBuf(Unpooled.buffer());try{var p=new ItemCapability(UUID.randomUUID(),"0.1.0-m3d","a".repeat(64),1400);ItemCapability.CODEC.encode(b,p);assertEquals(p,ItemCapability.CODEC.decode(b));assertThrows(RuntimeException.class,()->ItemCapability.CODEC.encode(b,new ItemCapability(p.nonce(),"x".repeat(65),p.hash(),1)));}finally{b.release();}}
}
