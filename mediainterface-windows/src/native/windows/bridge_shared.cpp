#include "bridge_shared.h"

#include <sstream>
#include <unordered_set>

bool g_eventDriven = true;
std::mutex g_initMutex;
int g_initRefCount = 0;
bool g_apartmentInitializedByBridge = false;
std::thread::id g_apartmentInitThread;

std::mutex g_traceMutex;
jclass g_bridgeClassGlobal = nullptr;
jmethodID g_traceMethod = nullptr;

std::string to_utf8(jstring value, JNIEnv* env) {
    if (value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    hstring wide = to_hstring(value);
    auto* chars = reinterpret_cast<const jchar*>(wide.c_str());
    return env->NewString(chars, static_cast<jsize>(wide.size()));
}

jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (array == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
        env->SetObjectArrayElement(array, i, to_jstring(env, values[i]));
    }
    return array;
}

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    if (env == nullptr || env->ExceptionCheck()) {
        return;
    }
    jclass exClass = env->FindClass("java/lang/IllegalStateException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message.c_str());
    }
}

void ensure_trace_bridge(JNIEnv* env, jclass bridgeClass) {
    if (env == nullptr || bridgeClass == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_traceMutex);
    if (g_bridgeClassGlobal != nullptr && g_traceMethod != nullptr) {
        return;
    }

    if (g_bridgeClassGlobal == nullptr) {
        g_bridgeClassGlobal = static_cast<jclass>(env->NewGlobalRef(bridgeClass));
        if (g_bridgeClassGlobal == nullptr) {
            return;
        }
    }
    if (g_traceMethod == nullptr) {
        g_traceMethod = env->GetStaticMethodID(g_bridgeClassGlobal, "traceFromNative", "(Ljava/lang/String;)V");
        if (g_traceMethod == nullptr) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }
}

void trace_native(JNIEnv* env, const std::string& message) {
    if (env == nullptr) {
        return;
    }
    jclass bridgeClass = nullptr;
    jmethodID traceMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_traceMutex);
        bridgeClass = g_bridgeClassGlobal;
        traceMethod = g_traceMethod;
    }
    if (bridgeClass == nullptr || traceMethod == nullptr) {
        return;
    }
    jstring msg = to_jstring(env, message);
    if (msg == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }
    env->CallStaticVoidMethod(bridgeClass, traceMethod, msg);
    env->DeleteLocalRef(msg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void trace_hresult(JNIEnv* env, const char* context, const hresult_error& e) {
    std::ostringstream out;
    out << context << " failed HRESULT=0x" << std::hex << static_cast<uint32_t>(e.code().value);
    trace_native(env, out.str());
}

int64_t ticks_to_millis(int64_t ticks) {
    return ticks / 10000;
}

int64_t millis_to_ticks(int64_t millis) {
    return millis * 10000;
}

std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId, JNIEnv* env) {
    trace_native(env, std::string("find_session request id=") + sessionId);
    auto manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
    for (auto const& session : manager.GetSessions()) {
        if (to_string(session.SourceAppUserModelId()) == sessionId) {
            trace_native(env, std::string("find_session hit id=") + sessionId);
            return session;
        }
    }
    trace_native(env, std::string("find_session miss id=") + sessionId);
    return std::nullopt;
}
