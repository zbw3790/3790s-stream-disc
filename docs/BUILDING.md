# Building

Use JDK **25**. The trusted Gradle Wrapper downloads Gradle **9.5.1**; its distribution SHA-256 is pinned. Fabric Loom is **1.17.21**. Run:

```sh
./gradlew test build
```

On Windows use `gradlew.bat test build`. Dependencies come from Maven Central, Fabric Maven and the official Minecraft distribution. The first build needs network access. No additional media executable, private helper file or local launch configuration is required. Java auto-download is disabled; install/select JDK25 yourself if unavailable.

The installable output is `build/libs/3790s-stream-disc-0.1.0-mc26.2-fabric.jar`. A sources JAR may also be generated; it is not the installable Mod. The build uses the root MIT LICENSE, versioned JCodec license and original licensed nested dependencies. Wrapper files are standard Gradle build tooling, retain their original headers and are distributed under Apache-2.0; the Apache license text is available with the other bundled license texts.

Unit tests run in isolated memory/loopback fixtures and do not create Minecraft worlds. Ordinary game installation needs Minecraft26.2 and Fabric Loader0.20.1; tests do not replace real integration or audio validation. Do not commit generated builds, caches, logs, recordings, local settings or worlds.
