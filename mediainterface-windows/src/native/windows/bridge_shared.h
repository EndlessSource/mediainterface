#pragma once

#include <jni.h>
#include <winerror.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>

#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Media::Control;
using namespace Windows::Security::Cryptography;
using namespace Windows::Storage::Streams;

extern bool g_eventDriven;
extern std::mutex g_initMutex;
extern int g_initRefCount;
extern bool g_apartmentInitializedByBridge;
extern std::thread::id g_apartmentInitThread;

extern std::mutex g_traceMutex;
extern jclass g_bridgeClassGlobal;
extern jmethodID g_traceMethod;

std::string to_utf8(jstring value, JNIEnv* env);
jstring to_jstring(JNIEnv* env, const std::string& value);
jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values);
void throw_illegal_state(JNIEnv* env, const std::string& message);
void ensure_trace_bridge(JNIEnv* env, jclass bridgeClass);
void trace_native(JNIEnv* env, const std::string& message);
void trace_hresult(JNIEnv* env, const char* context, const hresult_error& e);
int64_t ticks_to_millis(int64_t ticks);
int64_t millis_to_ticks(int64_t millis);
std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId, JNIEnv* env = nullptr);
