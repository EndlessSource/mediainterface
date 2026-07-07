#include "bridge_shared.h"

// Returns null (not an empty array) on transient failure so the Java side
// keeps its session state; an empty array means a genuine zero-session set.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionIds(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetSessionIds enter");
    try {
        std::optional<std::vector<std::string>> ids = list_session_ids(env);
        if (!ids.has_value()) {
            trace_native(env, "nativeGetSessionIds transient failure, returning null");
            return nullptr;
        }
        trace_native(env, std::string("nativeGetSessionIds count=") + std::to_string(ids.value().size()));
        return new_string_array(env, ids.value());
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetSessionIds", e);
        return nullptr;
    } catch (...) {
        trace_native(env, "nativeGetSessionIds unknown exception");
        return nullptr;
    }
}

// Pull-based snapshot used by the polling mode; event-driven mode receives
// the same payload pushed through WinRtBridge.onNativeSessionState instead.
//
// Snapshot payload layout (all values as strings, empty when absent):
// [0] appName, [1] active, [2] playbackState code, [3] capabilities as six
// '0'/'1' chars (play,pause,next,prev,stop,seek), [4] title, [5] artist,
// [6] album, [7] artwork base64, [8] durationMs, [9] positionMs, [10] isLive,
// [11] metadataPairs (key=value lines).
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionSnapshot(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    std::vector<std::string> payload(12, "");
    payload[2] = "3";  // UNKNOWN
    try {
        auto id = to_utf8(sessionId, env);
        auto session = find_session(id, env);
        if (!session.has_value()) {
            trace_native(env, "nativeGetSessionSnapshot no session");
            return new_string_array(env, payload);
        }
        payload = build_session_payload(session.value(), id, env);
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetSessionSnapshot", e);
    } catch (...) {
        trace_native(env, "nativeGetSessionSnapshot unknown exception");
    }
    return new_string_array(env, payload);
}
