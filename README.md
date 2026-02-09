# Java Media Interface

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

| Feature                                             | Linux | Windows | macOS   |
|-----------------------------------------------------|-------|---------|---------|
| Session discovery (all sessions)                    | Yes   | Yes     | No      |
| Control/Query multiple sessions at once             | Yes   | Yes     | No      |
| Active session selection                            | Yes   | Yes     | Yes     |
| Session lookup by app name                          | Yes   | Yes     | Partial |
| Playback state read                                 | Yes   | Yes     | Yes     |
| Now playing: name/album/artist/duration             | Yes   | Yes     | Yes     |
| Now playing: artwork                                | Yes   | No      | No      |
| Now playing: livestream detection                   |       |         | Yes     |
| Now playing: additional metadata                    | Yes   | Yes     | No      |
| Now playing: position                               | Yes   | Yes     | Yes     |
| Now playing: virtualized position progression       | Yes   | Yes     | Yes     |
| Playback controls: play/pause/toggle/next/prev/stop | Yes   | Yes     | Yes     |
| Playback controls: seek                             | No    | Yes     | Yes     |
| Polling: supported                                  | Yes   | Yes     | Yes     |
| Event driven: supported                             | Yes   | Yes     | Yes     |
| Event driven: process system events                 | No    | No      | No      |
| Polling: virtualized position progression           | Yes   | Yes     | Yes     |
| Event driven: virtualized position progression      | Yes   | Yes     | Yes     |
| Event driven: `onPlaybackStateChanged`              | Yes   | Yes     | Yes     |
| Event driven: `onSessionAdded/Removed`              | Yes   | Yes     | No      |
| Event driven: `onNowPlayingChanged`                 | Yes   | Yes     | Yes     |
| Event driven: `onSessionActiveChanged`              | No    | Yes     | Yes     |
| Configurable poll/update intervals                  | Yes   | Yes     | Yes     |

| Platform | Supported architecture | Native backend               |
|----------|------------------------|------------------------------|
| Linux    | N/A                    | Java DBUS/MPRIS2             |
| Windows  | x64, ARM64             | JNI (`mediainterface_winrt`) |
| macOS    | Intel, Apple Silicon   | Perl + MediaRemoteAdapter    |
