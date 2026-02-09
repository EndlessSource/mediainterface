package org.endlesssource.mediainterface.windows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WinRtBridge {
    private static final Logger logger = LoggerFactory.getLogger(WinRtBridge.class);
    private static volatile boolean loaded;

    static synchronized void load() {
        if (loaded) {
            return;
        }
        logger.debug("Loading WinRT bridge for arch={}", normalizedJvmArch());
        Path extracted = extractDll();
        System.load(extracted.toAbsolutePath().toString());
        loaded = true;
        logger.info("WinRT bridge loaded from {}", extracted);
    }

    static boolean hasBundledDll() {
        try (InputStream in = WinRtBridge.class.getResourceAsStream(dllResourcePathForCurrentArch())) {
            return in != null;
        } catch (IOException ignored) {
            logger.debug("Failed to probe bundled DLL resource", ignored);
            return false;
        }
    }

    private static Path extractDll() {
        String resource = dllResourcePathForCurrentArch();
        try (InputStream in = WinRtBridge.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing DLL resource: " + resource);
            }
            Path temp = Files.createTempFile("mediainterface_winrt", ".dll");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            return temp;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract WinRT DLL", e);
        }
    }

    private static String dllResourcePathForCurrentArch() {
        String arch = normalizedJvmArch();
        return "/native/windows/" + arch + "/mediainterface_winrt.dll";
    }

    private static String normalizedJvmArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            return "x64";
        }
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        throw new IllegalStateException("Unsupported Windows JVM architecture: " + arch);
    }

    static native void nativeInit(boolean eventDriven);

    static native void nativeShutdown();

    static native boolean nativeIsEventDrivenEnabled();

    static native String[] nativeGetSessionIds();

    static native String nativeGetSessionAppName(String sessionId);

    static native boolean nativeIsSessionActive(String sessionId);

    /**
     * @return Playback state code: 0=PLAYING, 1=PAUSED, 2=STOPPED, 3=UNKNOWN.
     */
    static native int nativeGetPlaybackState(String sessionId);

    /**
     * @return [canPlay, canPause, canNext, canPrevious, canStop, canSeek].
     */
    static native boolean[] nativeGetCapabilities(String sessionId);

    /**
     * @return Array payload:
     * [title, artist, album, artwork, durationMs, positionMs, isLive, metadataPairs]
     * where metadataPairs is encoded as key=value lines.
     */
    static native String[] nativeGetNowPlaying(String sessionId);

    static native boolean nativePlay(String sessionId);

    static native boolean nativePause(String sessionId);

    static native boolean nativeTogglePlayPause(String sessionId);

    static native boolean nativeNext(String sessionId);

    static native boolean nativePrevious(String sessionId);

    static native boolean nativeStop(String sessionId);

    static native boolean nativeSeek(String sessionId, long positionMillis);

    private WinRtBridge() {
    }
}
