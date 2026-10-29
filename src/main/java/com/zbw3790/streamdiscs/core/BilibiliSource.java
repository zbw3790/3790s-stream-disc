package com.zbw3790.streamdiscs.core;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class BilibiliSource {
    public record Metadata(MediaTarget target, long cid, String title, double duration) {}
    public record Resolved(MediaTarget target, long cid, String title, double duration, URI audio, String codec) {}
    private final SafeHttp http;
    public BilibiliSource(SafeHttp http) { this.http = http; }
    private JsonObject data(String path, Cancellation token) throws IOException {
        byte[] bytes = http.get(URI.create("https://api.bilibili.com" + path), -1, 1024 * 1024, token).bytes();
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            int code = root.get("code").getAsInt();
            if (code != 0) throw new IOException("SOURCE: Bilibili code=" + code);
            return root.getAsJsonObject("data");
        } catch (JsonParseException | IllegalStateException | NullPointerException e) { throw new IOException("SOURCE: invalid metadata", e); }
    }
    public Metadata metadata(MediaTarget target, Cancellation token) throws IOException {
        try {
            JsonObject view = data("/x/web-interface/view?bvid=" + target.bvid(), token);
            if (!target.bvid().equals(view.get("bvid").getAsString())) throw new IOException("SOURCE: media identity mismatch");
            if (view.getAsJsonObject("rights").get("pay").getAsInt() != 0) throw new IOException("SOURCE: paid media unsupported");
            JsonArray pages = view.getAsJsonArray("pages");
            if (target.page() > pages.size()) throw new IOException("INPUT: page outside available range");
            JsonObject page = pages.get(target.page() - 1).getAsJsonObject();
            long cid = page.get("cid").getAsLong();
            double duration = page.get("duration").getAsDouble();
            if (duration <= 0 || duration > 86400) throw new IOException("SOURCE: unsupported duration");
            return new Metadata(target,cid,view.get("title").getAsString()+" / "+page.get("part").getAsString(),duration);
        } catch (IllegalStateException | NullPointerException | IllegalArgumentException e) { throw new IOException("SOURCE: malformed metadata", e); }
    }
    public Resolved resolve(MediaTarget target, Cancellation token) throws IOException { return audio(metadata(target,token),token); }
    public Resolved audio(Metadata meta, Cancellation token) throws IOException {
        try {
            MediaTarget target=meta.target();long cid=meta.cid();
            JsonObject play = data("/x/player/playurl?bvid=" + target.bvid() + "&cid=" + cid + "&fnval=16&fourk=0", token);
            JsonObject best = null;
            JsonObject dash = play.getAsJsonObject("dash");
            if (dash == null || !dash.has("audio")) throw new IOException("FORMAT: DASH AAC unavailable");
            for (JsonElement element : dash.getAsJsonArray("audio")) {
                JsonObject audio = element.getAsJsonObject();
                if (audio.get("codecs").getAsString().equals("mp4a.40.2") && (best == null || audio.get("bandwidth").getAsLong() < best.get("bandwidth").getAsLong())) best = audio;
            }
            if (best == null) throw new IOException("FORMAT: AAC-LC track unavailable");
            URI uri = URI.create(best.get("baseUrl").getAsString()); http.validate(uri);
            return new Resolved(target, cid, meta.title(), meta.duration(), uri, "AAC-LC");
        } catch (IllegalStateException | NullPointerException | IllegalArgumentException e) { throw new IOException("SOURCE: malformed metadata", e); }
    }
}
