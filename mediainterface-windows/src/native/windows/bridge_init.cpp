#include "bridge_shared.h"

#include <sstream>

extern "C" JNIEXPORT void JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeInit(JNIEnv* env, jclass clazz, jboolean eventDriven) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeInit enter");
    g_eventDriven = (eventDriven == JNI_TRUE);
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initRefCount > 0) {
        ++g_initRefCount;
        trace_native(env, "nativeInit reused existing apartment ref");
        return;
    }
    try {
        trace_native(env, "nativeInit calling init_apartment(MTA)");
        init_apartment(apartment_type::multi_threaded);
        g_apartmentInitializedByBridge = true;
        g_apartmentInitThread = std::this_thread::get_id();
        g_initRefCount = 1;
        trace_native(env, "nativeInit success initialized bridge apartment");
    } catch (const hresult_error& e) {
        if (e.code() == hresult(RPC_E_CHANGED_MODE)) {
            g_apartmentInitializedByBridge = false;
            g_apartmentInitThread = std::thread::id{};
            g_initRefCount = 1;
            trace_native(env, "nativeInit RPC_E_CHANGED_MODE reusing existing COM apartment");
            return;
        }
        trace_hresult(env, "nativeInit", e);
        std::ostringstream message;
        message << "Failed to initialize WinRT apartment (HRESULT 0x"
                << std::hex << static_cast<uint32_t>(e.code().value)
                << ")";
        throw_illegal_state(env, message.str());
    } catch (...) {
        trace_native(env, "nativeInit failed with unknown exception");
        throw_illegal_state(env, "Failed to initialize WinRT apartment");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeShutdown(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeShutdown enter");
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initRefCount == 0) {
        trace_native(env, "nativeShutdown no-op refCount=0");
        return;
    }
    --g_initRefCount;
    if (g_initRefCount > 0) {
        trace_native(env, "nativeShutdown decremented but retained apartment");
        return;
    }
    // Drop cached WinRT objects and event registrations before the apartment
    // is torn down.
    shutdown_session_cache();
    if (g_apartmentInitializedByBridge && g_apartmentInitThread == std::this_thread::get_id()) {
        trace_native(env, "nativeShutdown calling uninit_apartment");
        uninit_apartment();
    } else {
        trace_native(env, "nativeShutdown skipping uninit_apartment (not owned/current thread)");
    }
    g_apartmentInitializedByBridge = false;
    g_apartmentInitThread = std::thread::id{};
}

// Subscribes to WinRT change events and synchronously delivers the current
// session list plus every session's state through the push callbacks before
// returning, so the Java side starts warm without ever pulling state.
// Returns false when the WinRT session manager is unavailable (caller should
// retry later); subsequent updates arrive purely through events.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeStartEventStream(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeStartEventStream enter");
    try {
        if (!ensure_manager(env)) {
            trace_native(env, "nativeStartEventStream manager unavailable");
            return JNI_FALSE;
        }
        std::optional<std::vector<std::string>> ids = list_session_ids(env);
        if (!ids.has_value()) {
            trace_native(env, "nativeStartEventStream enumeration failed");
            return JNI_FALSE;
        }
        push_session_list();
        for (auto const& id : ids.value()) {
            push_session_state(id);
        }
        trace_native(env, "nativeStartEventStream delivered initial state");
        return JNI_TRUE;
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeStartEventStream", e);
        return JNI_FALSE;
    } catch (...) {
        trace_native(env, "nativeStartEventStream unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsEventDrivenEnabled(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, std::string("nativeIsEventDrivenEnabled -> ") + (g_eventDriven ? "true" : "false"));
    return g_eventDriven ? JNI_TRUE : JNI_FALSE;
}
