package com.zbw3790.streamdiscs.core;
import com.zbw3790.streamdiscs.Wire;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
class PreparationWireTest {
 @Test void actualWireRoundTripAndOldEnvelopeRejected(){
  UUID epoch=UUID.randomUUID(),connection=UUID.randomUUID(),session=UUID.randomUUID(),token=UUID.randomUUID(),preparation=UUID.randomUUID();String key="minecraft:overworld/123";
  JsonObject j=Wire.message("preparation_status",session.toString());j.addProperty("epoch",epoch.toString());j.addProperty("connection",connection.toString());j.addProperty("key",key);j.addProperty("token",token.toString());j.addProperty("preparation",preparation.toString());j.addProperty("prepareRevision",9);
  var decoded=Wire.of(j).data();assertTrue(Wire.envelopeMatches(decoded,epoch,connection));assertTrue(Wire.preparationMatches(decoded,key,session,token,preparation,9));
  assertFalse(Wire.envelopeMatches(decoded,UUID.randomUUID(),connection));assertFalse(Wire.envelopeMatches(decoded,epoch,UUID.randomUUID()));assertFalse(Wire.preparationMatches(decoded,key,UUID.randomUUID(),token,preparation,9));assertFalse(Wire.preparationMatches(decoded,key,session,UUID.randomUUID(),preparation,9));assertFalse(Wire.preparationMatches(decoded,key,session,token,UUID.randomUUID(),9));assertFalse(Wire.preparationMatches(decoded,key,session,token,preparation,10));assertFalse(Wire.preparationMatches(decoded,"minecraft:the_nether/123",session,token,preparation,9));decoded.addProperty("v",2);assertFalse(Wire.supported(decoded));assertFalse(Wire.envelopeMatches(decoded,epoch,connection));
 }
}
