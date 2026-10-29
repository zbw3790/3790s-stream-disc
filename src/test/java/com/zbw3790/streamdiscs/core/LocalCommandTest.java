package com.zbw3790.streamdiscs.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class LocalCommandTest {
    @Test void onlyExactLocalDiagnosticsAreClaimed() {
        assertEquals(LocalCommand.Action.HELP,LocalCommand.parse("sd"));
        assertEquals(LocalCommand.Action.HELP,LocalCommand.parse("sd help"));
        assertEquals(LocalCommand.Action.STATUS,LocalCommand.parse("sd status"));
        assertEquals(LocalCommand.Action.STOP,LocalCommand.parse("sd stop"));
        for(String command:new String[]{"sd play BV1TNEyzYELu","sd tone 0","sd seek 1","sd status extra","sd stop extra","sd server-command","sda","say hello"})assertEquals(LocalCommand.Action.NONE,LocalCommand.parse(command),command);
    }
    @Test void oldValidationPropertyCannotEnableDevelopmentCommands() {
        String before=System.getProperty("streamdiscs.validation");
        try {System.setProperty("streamdiscs.validation","true");assertEquals(LocalCommand.Action.NONE,LocalCommand.parse("sd play BV1TNEyzYELu"));assertEquals(LocalCommand.Action.NONE,LocalCommand.parse("sd tone"));assertEquals(LocalCommand.Action.NONE,LocalCommand.parse("sd seek 0"));}
        finally {if(before==null)System.clearProperty("streamdiscs.validation");else System.setProperty("streamdiscs.validation",before);}
    }
}
