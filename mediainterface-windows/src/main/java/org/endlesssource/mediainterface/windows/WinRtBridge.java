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
    private static final boolean nativeTraceEnabled =
            Boolean.parseBoolean(System.getProperty("mediainterface.windows.nativeTrace", "false"));
    private static volatile boolean loaded;

    /**
     * Receiver for state pushed from WinRT events. The native side builds the
     * full payload inside its change handlers and delivers it here, so the
     * Java side never reads WinRT state back. Callbacks arrive on native
     * (WinRT thread pool) threads and must not block; implementations should
     * update in-memory caches and defer user listener callbacks to an
     * executor.
     */
    interface NativeEventSink {
        /** The current full set of session ids after a session-list change. */
        void onSessionListChanged(String[] sessionIds);

        /**
         * A session's full state after one of its WinRT properties changed.
         *
         * @param payload same layout as {@link #nativeGetSessionSnapshot}
         */
        void onSessionStateChanged(String sessionId, String[] payload);
    }

    private static volatile NativeEventSink eventSink;

    static void setEventSink(NativeEventSink sink) {
        eventSink = sink;
    }

    static void clearEventSink(NativeEventSink sink) {
        if (eventSink == sink) {
            eventSink = null;
        }
    }

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

    /**
     * Subscribes to WinRT change events and synchronously delivers the current
     * session list and each session's state through the event sink before
     * returning. All later updates arrive as pushes on WinRT threads.
     *
     * @return false when the WinRT session manager is unavailable; retry later
     */
    static native boolean nativeStartEventStream();

    static native void nativeShutdown();

    static native boolean nativeIsEventDrivenEnabled();

    static native String[] nativeGetSessionIds();

    /**
     * @return Combined snapshot payload:
     * [appName, active, playbackStateCode, capabilities (six '0'/'1' chars:
     * play,pause,next,prev,stop,seek), title, artist, album, artwork,
     * durationMs, positionMs, isLive, metadataPairs] where metadataPairs is
     * encoded as key=value lines. Playback state codes:
     * 0=PLAYING, 1=PAUSED, 2=STOPPED, 3=UNKNOWN.
     */
    static native String[] nativeGetSessionSnapshot(String sessionId);

    static native boolean nativePlay(String sessionId);

    static native boolean nativePause(String sessionId);

    static native boolean nativeTogglePlayPause(String sessionId);

    static native boolean nativeNext(String sessionId);

    static native boolean nativePrevious(String sessionId);

    static native boolean nativeStop(String sessionId);

    static native boolean nativeSeek(String sessionId, long positionMillis);

    static void traceFromNative(String message) {
        if (!nativeTraceEnabled) {
            return;
        }
        logger.info("[native] {}", message == null ? "<null>" : message);
    }

    /**
     * Called from native code with the full session-id list after the WinRT
     * session list changed.
     */
    static void onNativeSessionList(String[] sessionIds) {
        NativeEventSink sink = eventSink;
        if (sink == null || sessionIds == null) {
            return;
        }
        try {
            sink.onSessionListChanged(sessionIds);
        } catch (Throwable t) {
            logger.debug("Session-list event sink failed: {}", t.getMessage());
        }
    }

    /**
     * Called from native code with a session's full state payload after one
     * of its media/playback/timeline properties changed.
     */
    static void onNativeSessionState(String sessionId, String[] payload) {
        NativeEventSink sink = eventSink;
        if (sink == null || sessionId == null || payload == null) {
            return;
        }
        try {
            sink.onSessionStateChanged(sessionId, payload);
        } catch (Throwable t) {
            logger.debug("Session state event sink failed: {}", t.getMessage());
        }
    }

    private WinRtBridge() {
    }
}
