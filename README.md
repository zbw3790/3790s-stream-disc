# 3790‘s Stream Disc

Turn a vanilla music disc into a **Stream Disc** with an anvil, then play a Bilibili video’s audio in a vanilla jukebox. Made by **3790**. Version **0.1.0**.

![Mod logo](src/main/resources/assets/stream_discs/icon.png)

## Requirements and installation

Verified on **Minecraft 26.2**, **Fabric Loader 0.20.1**, **Java 25**, Windows x64. Other versions, operating systems and modpacks are not verified. This is a Fabric mod, not a NeoForge build.

Get `3790s-stream-disc-0.1.0-mc26.2-fabric.jar` and `SHA256SUMS.txt` from [Releases](https://github.com/zbw3790/3790s-stream-disc/releases). Put the JAR in your Fabric instance’s `mods` directory. Install the **same JAR bytes on the server and every participating client**. Remove the old installed JAR before replacing it; do not install both versions together.

One Mod JAR includes the required JCodec/JAAD and five minimal Fabric API modules. No separate Fabric API, FFmpeg, yt-dlp, extension or external player is required. Use Minecraft’s ordinary output device, master volume and jukebox/record volume controls.

## Play

1. Put **one** vanilla music disc in the first anvil slot. Rename it to a BV identifier or a supported standard HTTPS URL, for example `https://www.bilibili.com/video/BV13s411v7LA/?p=1`.
2. **Take the output** to create `stream_discs:stream_disc` and start asynchronous metadata loading. Merely previewing an output does not request media or consume the input. Hover for its BV, part, state, title and duration.
3. Insert it into a vanilla jukebox. Ejecting, breaking, unloading or leaving stops the relevant playback. You can eject a disc while it is loading. Natural completion also stops playback.
4. Requests retry at most three times. A failed disc remains a Stream Disc; take it through the anvil again with the same target to retry. Use another valid BV to rebind it. An ordinary custom name takes precedence; clearing the name restores the default item name without clearing its binding.

Bare BV identifiers use part 1. Standard `https://www.bilibili.com/video/BV…/?p=2` links preserve the part. Only the implemented range of ordinary Bilibili on-demand videos with an available **AAC-LC separate audio track** is supported. Short links, login/paid/restricted media, unsupported codecs and video-only combined streams are not supported. Not every video is accessible; this mod does not bypass access controls or require your credentials.

## Small multiplayer sessions

The server owns the playback timeline; each client fetches and decodes its own audio. After metadata is available, the readiness window is **at most four logical seconds** and can finish earlier when its original listeners are ready. This does **not** guarantee audible output within four seconds on every computer. A slow or late listener independently retries and catches up, and may miss the opening. Each client supports at most two simultaneous sources. Accuracy across every public network or modpack is not guaranteed.

Audio is spatialized at the jukebox position and stereo is downmixed to mono; phase-opposed channels may cancel. Local `/sd status` shows this client’s state and `/sd stop` stops this client’s sources. Development play/tone/seek commands are not available in this build.

## Worlds and limitations

**Back up your world before upgrading.** Existing bound vanilla discs remain readable and playable. Taking one through a valid anvil operation upgrades it to the new registered item; there is no whole-world migration. A world containing the new item is not guaranteed safe to open with an older version or after uninstalling the mod. Restore an appropriate backup to downgrade.

No new recipe, unbind/restore button or playlist is included. Unsupported complex third-party components, oversized data and abnormal stacks are rejected while preserving the input; arbitrary mod compatibility is not promised. A cancelled decoder read can occasionally be logged as `FORMAT` even when cancellation caused it; this classification issue remains. Physical-output timing, long sessions, public networks and other system combinations are not comprehensively verified.

This is an **unofficial project, not affiliated with or endorsed by Bilibili, Mojang or Microsoft**. No claim of platform API authorization is made. The repository and release contain no Bilibili video/audio, media cache or credentials. MIT applies to the author’s own code and authorized product assets, not to streamed media or bundled third-party code. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Build instructions: [docs/BUILDING.md](docs/BUILDING.md).

## 简体中文

将单张原版唱片放入铁砧，输入BV或受支持的标准HTTPS视频链接，**实际领取**后变为独立流媒体唱片并异步读取。悬停显示状态和媒体信息；放入原版唱片机播放，取出／破坏／退出停止。最多三次读取，失败保留物品，经铁砧实际领取可重试；可普通改名或重绑其他BV。

实测组合为Minecraft26.2、Fabric Loader0.20.1、Java25、Windows x64。只安装一个JAR，服务器和各客户端必须同字节；不要并装新旧版，无需外部媒体工具或另装Fabric API。当前支持已实现范围内的Bilibili普通点播、分P和可用AAC-LC独立音轨，不支持短链接、受限媒体等，不保证全站或所有环境可用。普通声音设置由游戏控制。

媒体信息就绪后最多四秒**逻辑**准备、全员就绪可提前；慢端独立追赶，可能错过开头，每端最多两路，不保证任意公网精度。升级前备份世界，旧绑定下次有效铁砧领取升级，无全量迁移，不承诺旧版／卸载安全。取消读取偶尔记为FORMAT的已知分类问题保留。

本项目自有源码和有权授权的资源采用MIT；内嵌第三方各守原许可，不授权视频音轨再分发。本项目非Bilibili／Mojang／Microsoft官方项目，不索取凭据、不绕过访问控制。
