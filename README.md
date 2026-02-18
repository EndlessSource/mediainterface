# Java Media Interface

[![CI](https://github.com/endlesssource/mediainterface/actions/workflows/ci.yml/badge.svg)](https://github.com/endlesssource/mediainterface/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.endlesssource.mediainterface/all.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.endlesssource.mediainterface/all)
[![Javadoc (core)](https://javadoc.io/badge2/org.endlesssource.mediainterface/core/javadoc.svg)](https://javadoc.io/doc/org.endlesssource.mediainterface/core)<br>
![Java](https://img.shields.io/badge/java-%23ED8B00.svg?logo=openjdk&logoColor=white)
![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?logo=kotlin&logoColor=white)<br>
![macOS](https://img.shields.io/badge/macOS-000000?logo=apple&logoColor=F0F0F0)
![Windows](https://img.shields.io/badge/Windows-0078D6?&logo=windows&logoColor=white)
![Linux](https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black)

Access the operating systems "Media Remote"/Now Playing interface from Java/Kotlin. Works on all modern operating systems.

## Features
- Get current system media sessions
- Support for multiple apps playing at the same time
- Query name, album, artist, artwork data, duration and additional metadata
- Get the play head position
- Control playback (play/pause/toggle/next/prev/stop)
- Seek support
- Cross-platform universal interface (one API for all platforms)

## Operating system support

| Feature                                             | Linux    | Windows  | macOS    |
|-----------------------------------------------------|----------|----------|----------|
| Session discovery (all sessions)                    | Yes      | Yes      | No       |
| Control/Query multiple sessions at once             | Yes      | Yes      | No       |
| Active session selection                            | Yes      | Yes      | Yes      |
| Session lookup by app name                          | Yes      | Yes      | Partial  |
| Playback state read                                 | Yes      | Yes      | Yes      |
| Now playing: name/album/artist/duration             | Yes      | Yes      | Yes      |
| Now playing: artwork                                | Yes      | Yes      | Yes      |
| Now playing: livestream detection                   | Untested | Untested | Untested |
| Now playing: additional metadata                    | Yes      | Yes      | No       |
| Now playing: position                               | Yes      | Yes      | Yes      |
| Now playing: computed position progression          | Yes      | Yes      | Yes      |
| Playback controls: play/pause/toggle/next/prev/stop | Yes      | Yes      | Yes      |
| Playback controls: seek                             | Yes      | Yes      | Yes      |
| Polling: supported                                  | Yes      | Yes      | Yes      |
| Event driven: supported                             | Yes      | Yes      | Yes      |
| Event driven: process system events                 | No       | No       | No       |
| Polling: computed position progression              | Yes      | Yes      | Yes      |
| Event driven: computed position progression         | Yes      | Yes      | Yes      |
| Event driven: `onPlaybackStateChanged`              | Yes      | Yes      | Yes      |
| Event driven: `onSessionAdded/Removed`              | Yes      | Yes      | No       |
| Event driven: `onNowPlayingChanged`                 | Yes      | Yes      | Yes      |
| Event driven: `onSessionActiveChanged`              | Yes      | Yes      | Yes      |
| Configurable poll/update intervals                  | Yes      | Yes      | Yes      |

| Platform | Supported architecture | Native backend               |
|----------|------------------------|------------------------------|
| Linux    | N/A                    | Java DBUS/MPRIS2             |
| Windows  | x64, ARM64             | JNI (`mediainterface_winrt`) |
| macOS    | Intel, Apple Silicon   | Perl + MediaRemoteAdapter    |

## Quickstart

Use `mediainterface-all` to get the core API + all platform providers in one dependency.

Replace VERSION_HERE with the Maven Central version shown in the badge above.

Javadocs:
- Core API: https://javadoc.io/doc/org.endlesssource.mediainterface/core
- Linux: https://javadoc.io/doc/org.endlesssource.mediainterface/linux
- Windows: https://javadoc.io/doc/org.endlesssource.mediainterface/windows
- macOS: https://javadoc.io/doc/org.endlesssource.mediainterface/macos

### Gradle (Kotlin)

```kotlin
dependencies {
    implementation("org.endlesssource.mediainterface:all:VERSION_HERE")
}
```

### Maven

```xml
<dependency>
  <groupId>org.endlesssource.mediainterface</groupId>
  <artifactId>all</artifactId>
  <version>VERSION_HERE</version>
</dependency>
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

## License

Java Media Interface is licensed under the Apache 2.0 License. (see `LICENSE`)

## Credits

- [@TimLohrer](https://github.com/TimLohrer) for the idea
- [mediaremote-adapter](https://github.com/ungive/mediaremote-adapter/) for the MediaRemote Perl workaround
