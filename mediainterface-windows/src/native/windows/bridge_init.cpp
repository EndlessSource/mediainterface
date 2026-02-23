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
    if (g_apartmentInitializedByBridge && g_apartmentInitThread == std::this_thread::get_id()) {
        trace_native(env, "nativeShutdown calling uninit_apartment");
        uninit_apartment();
    } else {
        trace_native(env, "nativeShutdown skipping uninit_apartment (not owned/current thread)");
    }
    g_apartmentInitializedByBridge = false;
    g_apartmentInitThread = std::thread::id{};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsEventDrivenEnabled(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, std::string("nativeIsEventDrivenEnabled -> ") + (g_eventDriven ? "true" : "false"));
    return g_eventDriven ? JNI_TRUE : JNI_FALSE;
}
