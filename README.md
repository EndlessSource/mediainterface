# Java Media Interface

[![CI](https://github.com/endlesssource/mediainterface/actions/workflows/ci.yml/badge.svg)](https://github.com/endlesssource/mediainterface/actions/workflows/ci.yml)
[![EndlessSource Nexus](https://img.shields.io/maven-metadata/v?metadataUrl=https://nexus.endlesssource.org/repository/maven-releases/org/endlesssource/mediainterface/all/maven-metadata.xml&label=EndlessSource)](https://nexus.endlesssource.org/repository/maven-releases/org/endlesssource/mediainterface/all/)
[![Maven Central](https://img.shields.io/maven-central/v/org.endlesssource.mediainterface/all.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.endlesssource.mediainterface/all)
[![Javadoc (core)](https://javadoc.io/badge2/org.endlesssource.mediainterface/core/javadoc.svg)](https://javadoc.io/doc/org.endlesssource.mediainterface/core)<br>
![Java](https://img.shields.io/badge/java-%23ED8B00.svg?logo=openjdk&logoColor=white)
![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?logo=kotlin&logoColor=white)<br>
![macOS](https://img.shields.io/badge/macOS-000000?logo=apple&logoColor=F0F0F0)
![Windows](https://img.shields.io/badge/Windows-0078D6?&logo=windows&logoColor=white)
![Linux](https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black)

Access the operating systems "Media Remote"/Now Playing interface from Java/Kotlin. Works on all modern operating systems.

## Features
- Event-driven with very low idle CPU on Windows and Linux, using OS notifications instead of polling (see [Event-driven pipeline](#event-driven-pipeline))
- Get current system media sessions
- Support for multiple apps playing at the same time
- Query name, album, artist, artwork data, duration and additional metadata
- Get the play head position
- Control playback (play/pause/toggle/next/prev/stop)
- Seek support
- Cross-platform universal interface (one API for all platforms)

## Quickstart

Requires Java 11 or newer.

Use `mediainterface-all` to get the core API + all platform providers in one dependency.

Replace `VERSION_HERE` with the latest version shown in the badge above.

Javadocs:
- Core API: https://javadoc.io/doc/org.endlesssource.mediainterface/core
- Linux: https://javadoc.io/doc/org.endlesssource.mediainterface/linux
- Windows: https://javadoc.io/doc/org.endlesssource.mediainterface/windows
- macOS: https://javadoc.io/doc/org.endlesssource.mediainterface/macos

### Gradle (Kotlin)

```kotlin
repositories {
    maven("https://maven.endlesssource.org/repository/maven-releases/")
}

dependencies {
    implementation("org.endlesssource.mediainterface:all:VERSION_HERE")
}
```

### Maven

```xml
<repositories>
  <repository>
    <id>endlesssource</id>
    <url>https://maven.endlesssource.org/repository/maven-releases/</url>
  </repository>
</repositories>

<dependency>
  <groupId>org.endlesssource.mediainterface</groupId>
  <artifactId>all</artifactId>
  <version>VERSION_HERE</version>
</dependency>
```

### Maven Central 

Also available on Maven Central (note that releases might be delayed):

**Gradle (Kotlin):**
```kotlin
dependencies {
    implementation("org.endlesssource.mediainterface:all:VERSION_HERE")
}
```

**Maven:**
```xml
<dependency>
  <groupId>org.endlesssource.mediainterface</groupId>
  <artifactId>all</artifactId>
  <version>VERSION_HERE</version>
</dependency>
```

### Snapshots

Snapshot builds are available from the EndlessSource Nexus snapshot repository:

**Gradle (Kotlin):**
```kotlin
repositories {
    maven("https://maven.endlesssource.org/repository/maven-snapshots/")
}
```

**Maven:**
```xml
<repositories>
  <repository>
    <id>endlesssource-snapshots</id>
    <url>https://maven.endlesssource.org/repository/maven-snapshots/</url>
  </repository>
</repositories>
```

### IMPORTANT FOR shadowJar USERS

If you use `shadowJar`, add this to your Gradle configuration to merge the included service file:

```kotlin
tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE // include all service files
    mergeServiceFiles { // always merge service files
        include("META-INF/services/org.endlesssource.mediainterface.spi.PlatformMediaProvider")
    }
}
```


### Minimal usage

```java
import org.endlesssource.mediainterface.SystemMediaFactory;
import org.endlesssource.mediainterface.api.SystemMediaInterface;

public class Main {
    public static void main(String[] args) {
        try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface()) {
            media.getActiveSession().ifPresent(session -> {
                System.out.println("App: " + session.getApplicationName());
                session.getNowPlaying().ifPresent(now -> {
                    String title = now.getTitle().orElse("Unknown title");
                    String artist = now.getArtist().orElse("Unknown artist");
                    System.out.println("Now playing: " + title + " - " + artist);
                });
            });
        }
    }
}
```

### More examples

See [`examples` module](https://github.com/EndlessSource/mediainterface/tree/main/examples/src/main/java/org/endlesssource/mediainterface/examples)

## Event-driven pipeline

On Windows and Linux, event-driven mode uses the operating system's own change notifications rather than polling on a timer. Because of this, the library only does work when something actually changes. While media is paused or nothing is happening, it stays close to 0% CPU.

On Windows, the native bridge subscribes to the WinRT System Media Transport Controls events (session list, media properties, playback info, and timeline changes). When one fires, it reads the affected session's state once and hands it up to Java, which keeps its own copy. Your reads are served from that cached copy, so the library doesn't call back into the OS on every access. On Linux, the same idea is built on D-Bus/MPRIS2 signals, which arrive as changes happen.

In practice this means your listener callbacks (`onNowPlayingChanged`, `onPlaybackStateChanged`, `onSessionActiveChanged`, `onSessionAdded`/`onSessionRemoved`) fire right when a real change occurs, and an idle system stays idle. Callbacks run on the library's own executor, so a slow listener won't hold up event delivery from the OS.

```java
SystemMediaOptions options = SystemMediaOptions.defaults()
        .withEventDrivenEnabled(true); // default

try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface(options)) {
    media.addSessionListener(new MediaSessionListener() {
        @Override
        public void onNowPlayingChanged(MediaSession session, Optional<NowPlaying> nowPlaying) {
            // fired only when the track/metadata actually changes
        }
    });
    // ... your app keeps running; no polling loop needed
}
```

One exception: if you want the play-head to advance smoothly between OS updates (for a progress bar, say), the library emits interpolated position updates on a light timer. That's the only periodic work in event-driven mode, and it does no OS or native calls, since the position is computed from the last known value. You can turn it off with `SystemMediaOptions.withPositionUpdatesEnabled(false)` if you'd rather have no periodic activity at all.

Polling mode (`withEventDrivenEnabled(false)`) is still available everywhere as a fallback, and macOS uses a polling-based backend.

## Platform support

| Platform | Architecture         | Backend                      |
|----------|----------------------|------------------------------|
| Linux    | any                  | Java D-Bus / MPRIS2          |
| Windows  | x64, ARM64           | JNI (`mediainterface_winrt`) |
| macOS    | Intel, Apple Silicon | Perl + MediaRemoteAdapter    |

<details>
<summary>Feature matrix</summary>

| Feature                                             | Linux    | Windows  | macOS    |
|-----------------------------------------------------|----------|----------|----------|
| Session discovery (all sessions)                    | Yes      | Yes      | No       |
| Control/Query multiple sessions at once             | Yes      | Yes      | No       |
| Active session selection                            | Yes      | Yes      | Yes      |
| Session lookup by app name                          | Yes      | Yes      | Partial  |
| Playback state                                      | Yes      | Yes      | Yes      |
| Now playing: title/album/artist/duration            | Yes      | Yes      | Yes      |
| Now playing: artwork                                | Yes      | Yes      | Yes      |
| Now playing: additional metadata                    | Yes      | Yes      | No       |
| Now playing: position                               | Yes      | Yes      | Yes      |
| Now playing: computed position progression          | Yes      | Yes      | Yes      |
| Now playing: livestream detection                   | Untested | Untested | Untested |
| Playback controls: play/pause/toggle/next/prev/stop | Yes      | Yes      | Yes      |
| Playback controls: seek                             | Yes      | Yes      | Yes      |
| Polling                                             | Yes      | Yes      | Yes      |
| Event-driven                                        | Yes      | Yes      | Yes      |
| Event-driven: `onPlaybackStateChanged`              | Yes      | Yes      | Yes      |
| Event-driven: `onNowPlayingChanged`                 | Yes      | Yes      | Yes      |
| Event-driven: `onSessionActiveChanged`              | Yes      | Yes      | Yes      |
| Event-driven: `onSessionAdded/Removed`              | Yes      | Yes      | No       |
| Configurable poll/update intervals                  | Yes      | Yes      | Yes      |

</details>

## License

Java Media Interface is licensed under the Apache 2.0 License. (see `LICENSE`)

## Credits

- [@TimLohrer](https://github.com/TimLohrer) for the idea
- [mediaremote-adapter](https://github.com/ungive/mediaremote-adapter/) for the MediaRemote Perl workaround
