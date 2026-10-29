package com.zbw3790.streamdiscs.core;

/** Release-local diagnostics only. All other commands belong to the server. */
public final class LocalCommand {
    public enum Action { NONE, HELP, STATUS, STOP }
    public static Action parse(String command) {
        return switch(command) {
            case "sd", "sd help" -> Action.HELP;
            case "sd status" -> Action.STATUS;
            case "sd stop" -> Action.STOP;
            default -> Action.NONE;
        };
    }
    private LocalCommand() {}
}
