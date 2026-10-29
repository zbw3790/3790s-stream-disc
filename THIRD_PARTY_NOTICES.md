# 第三方组件与许可

3790有权许可的本项目源码和产品资源采用MIT License，见根LICENSE。以下内嵌第三方构件分别遵守其原许可，MIT不替换或覆盖这些条款。

| 内嵌构件 | 固定版本 | 原始来源 | 许可与原文位置 |
|---|---|---|---|
| org.jcodec:jcodec（含既有JAAD代码） | 0.2.5 | Maven Central作者发布构件 | BSD-2-Clause；外层JAR licenses/JCODEC-LICENSE.txt |
| fabric-api-base | 2.0.4+ece063239e | FabricMC Maven | Apache-2.0；嵌套JAR的LICENSE-fabric-api-base |
| fabric-networking-api-v1 | 6.3.3+72073ef09e | FabricMC Maven | Apache-2.0；嵌套JAR的LICENSE-fabric-networking-api-v1 |
| fabric-lifecycle-events-v1 | 4.1.3+4575b05f9e | FabricMC Maven | Apache-2.0；嵌套JAR的LICENSE-fabric-lifecycle-events-v1 |
| fabric-registry-sync-v0 | 7.1.0+c7bd5b8e9e | FabricMC Maven | Apache-2.0；嵌套JAR的LICENSE-fabric-registry-sync-v0 |
| fabric-resource-loader-v1 | 2.0.13+9edec1269e | FabricMC Maven | Apache-2.0；嵌套JAR的LICENSE-fabric-resource-loader-v1 |

嵌套JAR均在产品META-INF/jars/，原二进制及其中LICENSE／NOTICE不改写。文档同目录licenses也提供原许可文本副本；版权、条件和免责声明完整保留。JCodec内置JAAD源码声明Public Domain，JCodec修改以FreeBSD许可提供；不以此改写JCodec总体BSD-2-Clause原文。

只内嵌这五个Fabric API最小模块及JCodec，不内嵌完整Fabric API。模块依赖闭包使用既有base／networking等固定组件，未升级版本。玩家无需另装这些库或任何外部媒体工具。官方构件来源：[FabricMC Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/)、[JCodec 0.2.5](https://repo.maven.apache.org/maven2/org/jcodec/jcodec/0.2.5/)。

Minecraft、Fabric Loader、Mixin和LWJGL属于声明的基础运行环境，不打包进此Mod。本Mod没有自带FFmpeg、外部解码程序或新增原生库。不宣称全部平台兼容或获得Bilibili、Mojang或Microsoft的官方认可。
