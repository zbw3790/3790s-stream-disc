## 3790‘s Stream Disc v0.1.0

Bind a single vanilla music disc to a Bilibili BV identifier or supported standard HTTPS video link using an anvil, then play its available AAC-LC audio in a vanilla jukebox. Supports asynchronous metadata, bounded retries, spatial playback and a server-owned timeline for small multiplayer sessions.

### Install

- Verified combination: Minecraft **26.2**, Fabric Loader **0.20.1**, Java **25**, Windows x64.
- Download **3790s-stream-disc-0.1.0-mc26.2-fabric.jar** and **SHA256SUMS.txt**. Check the hash and put the JAR in `mods` on the server and all participating clients; use identical bytes and do not keep old and new JARs installed together.
- One JAR includes the required minimal libraries. No separate Fabric API, FFmpeg or external player is needed. See the repository README for normal gameplay and build instructions.

### Scope and known limitations

- Ordinary Bilibili on-demand video/parts with available separate AAC-LC audio only; not every video is accessible. No short links, login/paid/restricted-media bypass or account credentials.
- Readiness is at most four logical seconds after metadata, with early start and independent catch-up. It does not guarantee each listener hears sound within four seconds. At most two sources per client; arbitrary public-network/modpack precision is not guaranteed.
- **Back up worlds before upgrading.** Existing bindings remain readable/playable and upgrade on a valid anvil pickup. No world-wide migration; uninstall/downgrade safety for worlds with the registered item is not guaranteed.
- Cancelled reads can occasionally be logged as FORMAT; that classification issue remains. Other platforms, long runs and physical-output timing are not comprehensively verified.

The author’s code and authorized product assets use **MIT**. JCodec/JAAD and minimal Fabric modules retain their BSD/Apache/public-domain notices as applicable; see LICENSE and THIRD_PARTY_NOTICES.md. No streamed media is distributed here. Unofficial project, not affiliated with or endorsed by Bilibili, Mojang or Microsoft; no platform API authorization is claimed.

简体中文：铁砧实际领取完成绑定，放入唱片机播放，取出／破坏／退出停止；读取失败最多三次并可重新领取重试。各端与服务端安装同字节单JAR。升级前备份，慢端可追赶但不保证任意环境同步。MIT仅用于作者有权授权的内容，第三方与视频音轨权利不因此改变。
