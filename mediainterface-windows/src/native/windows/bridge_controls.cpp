#include "bridge_shared.h"

namespace {
    jboolean session_command(JNIEnv* env, jclass clazz, jstring sessionId, const char* name, auto&& invoker) {
        ensure_trace_bridge(env, clazz);
        trace_native(env, std::string(name) + " enter");
        try {
            auto id = to_utf8(sessionId, env);
            auto session = find_session(id, env);
            jboolean result = (session.has_value() && invoker(session.value())) ? JNI_TRUE : JNI_FALSE;
            trace_native(env, std::string(name) + (result == JNI_TRUE ? " success" : " false"));
            return result;
        } catch (const hresult_error& e) {
            trace_hresult(env, name, e);
            return JNI_FALSE;
        } catch (...) {
            trace_native(env, std::string(name) + " unknown exception");
            return JNI_FALSE;
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativePlay(JNIEnv* env, jclass clazz, jstring sessionId) {
    return session_command(env, clazz, sessionId, "nativePlay", [](auto const& s) { return s.TryPlayAsync().get(); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativePause(JNIEnv* env, jclass clazz, jstring sessionId) {
    return session_command(env, clazz, sessionId, "nativePause", [](auto const& s) { return s.TryPauseAsync().get(); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeTogglePlayPause(JNIEnv* env, jclass clazz, jstring sessionId) {
    return session_command(env, clazz, sessionId, "nativeTogglePlayPause", [](auto const& s) { return s.TryTogglePlayPauseAsync().get(); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeNext(JNIEnv* env, jclass clazz, jstring sessionId) {
    return session_command(env, clazz, sessionId, "nativeNext", [](auto const& s) { return s.TrySkipNextAsync().get(); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativePrevious(JNIEnv* env, jclass clazz, jstring sessionId) {
    return session_command(env, clazz, sessionId, "nativePrevious", [](auto const& s) { return s.TrySkipPreviousAsync().get(); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeStop(JNIEnv* env, jclass clazz, jstring sessionId) {
    return session_command(env, clazz, sessionId, "nativeStop", [](auto const& s) { return s.TryStopAsync().get(); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeSeek(JNIEnv* env, jclass clazz, jstring sessionId, jlong positionMillis) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeSeek enter");
    if (positionMillis < 0) {
        trace_native(env, "nativeSeek rejected negative position");
        return JNI_FALSE;
    }
    try {
        auto id = to_utf8(sessionId, env);
        auto session = find_session(id, env);
        if (!session.has_value()) {
            trace_native(env, "nativeSeek session missing");
            return JNI_FALSE;
        }
        uint64_t requested = static_cast<uint64_t>(millis_to_ticks(positionMillis));
        bool ok = session.value().TryChangePlaybackPositionAsync(requested).get();
        trace_native(env, std::string("nativeSeek result=") + (ok ? "true" : "false"));
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeSeek", e);
        return JNI_FALSE;
    } catch (...) {
        trace_native(env, "nativeSeek unknown exception");
        return JNI_FALSE;
    }
}
