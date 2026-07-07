#pragma once

#include <jni.h>
#include <winerror.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>

#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Media::Control;
using namespace Windows::Security::Cryptography;
using namespace Windows::Storage::Streams;

inline constexpr std::chrono::milliseconds kAsyncTimeout{2000};

// A native .get() cannot be interrupted from Java (shutdownNow() is powerless
// against a frame blocked in native code), so a hung cross-process WinRT call
// (source app busy/suspended) would block the shared executor thread forever.
// On timeout: cancel and throw so the caller's catch turns it into an empty
// result and the next refresh retries.
template <typename TOp>
auto await_with_timeout(TOp const& op, std::chrono::milliseconds timeout = kAsyncTimeout) {
    if (op.wait_for(timeout) != winrt::Windows::Foundation::AsyncStatus::Completed) {
        op.Cancel();
        throw winrt::hresult_error(HRESULT_FROM_WIN32(ERROR_TIMEOUT), L"WinRT async operation timed out");
    }
    return op.GetResults();
}

extern bool g_eventDriven;
extern std::mutex g_initMutex;
extern int g_initRefCount;
extern bool g_apartmentInitializedByBridge;
extern std::thread::id g_apartmentInitThread;

extern std::mutex g_traceMutex;
extern jclass g_bridgeClassGlobal;
extern jmethodID g_traceMethod;
extern jmethodID g_sessionListMethod;
extern jmethodID g_sessionStateMethod;
extern JavaVM* g_jvm;

std::string to_utf8(jstring value, JNIEnv* env);
jstring to_jstring(JNIEnv* env, const std::string& value);
jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values);
void throw_illegal_state(JNIEnv* env, const std::string& message);
void ensure_trace_bridge(JNIEnv* env, jclass bridgeClass);
void trace_native(JNIEnv* env, const std::string& message);
void trace_hresult(JNIEnv* env, const char* context, const hresult_error& e);
int64_t ticks_to_millis(int64_t ticks);
int64_t millis_to_ticks(int64_t millis);

// Calls RequestAsync().get() on a dedicated Win32 thread with two-layer
// exception defence (C++ try/catch + SEH __try/__except).
// Returns true and fills *out on success, false on any exception or timeout.
// Heap-allocates context with refcount so a timeout cannot use-after-free.
bool smtc_try_request_manager(GlobalSystemMediaTransportControlsSessionManager* out, JNIEnv* env) noexcept;

// Wraps smtc_try_request_manager with up to 3 retries for cold-boot AVs.
std::optional<GlobalSystemMediaTransportControlsSessionManager> request_manager_safe(JNIEnv* env);

// --- Cached session manager -------------------------------------------------
// The manager is requested once and cached for the lifetime of the bridge;
// the session list is kept in a map keyed by AUMID and refreshed from the
// SessionsChanged event (with on-demand reconciliation as a fallback), so a
// session lookup is a hash lookup instead of a cross-process RequestAsync.

// Ensures the cached manager exists (requesting it on first use) and that
// change events are registered. Returns false if the manager is unavailable.
bool ensure_manager(JNIEnv* env);

// Snapshot of current session ids; reconciles the cache with GetSessions().
// Returns nullopt on transient failure (manager unavailable or enumeration
// threw) so the Java side keeps its session state instead of tearing every
// session down; the stale manager is dropped so the next call re-requests it.
std::optional<std::vector<std::string>> list_session_ids(JNIEnv* env);

// Cheap cached lookup by AUMID; reconciles once on miss.
std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId, JNIEnv* env = nullptr);

// Artwork cache: returns the cached base64 thumbnail for the session if it is
// still valid for the given media key (title/artist/album) and non-empty.
std::optional<std::string> get_cached_artwork(const std::string& sessionId, const std::string& mediaKey);
void store_artwork(const std::string& sessionId, const std::string& mediaKey, const std::string& base64);

// --- Media-properties cache --------------------------------------------------
// TryGetMediaPropertiesAsync is a cross-process call; its result only changes
// when the source app updates its metadata, which fires MediaPropertiesChanged.
// The last fetched properties are therefore cached per session and invalidated
// by that event, so steady-state snapshots stay in-process. The TTL bounds
// staleness if an event registration dies silently.

// How long a cached media-properties entry may be served before a fresh
// cross-process fetch is forced even without a MediaPropertiesChanged event.
inline constexpr std::chrono::seconds kMediaPropsTtl{30};

struct MediaPropsEntry {
    std::string title;
    std::string artist;
    std::string album;
    // Pre-rendered "albumArtist="/"trackNumber="/"genre=" metadata lines.
    std::string metadataPrefix;
    std::chrono::steady_clock::time_point fetchedAt{};
};

// Returns the cached media properties for the session, or nullopt when the
// cache must not be used: no entry, entry older than kMediaPropsTtl, or the
// session's MediaPropertiesChanged hook failed to register (without the event
// there is no invalidation signal, so the cache cannot be trusted).
std::optional<MediaPropsEntry> get_cached_media_props(const std::string& sessionId);
void store_media_props(const std::string& sessionId, MediaPropsEntry entry);

// --- Push-based state delivery -----------------------------------------------
// In event-driven mode the WinRT change handlers build the affected session's
// full state payload and push it into Java (WinRtBridge.onNativeSessionState /
// onNativeSessionList), so the Java side never has to read WinRT state back.

// Builds the 12-element snapshot payload for a session; layout is documented
// at nativeGetSessionSnapshot in bridge_query.cpp. Media properties are served
// from the event-invalidated cache (cross-process fetch only on a miss).
// WinRT failures propagate as hresult_error.
std::vector<std::string> build_session_payload(GlobalSystemMediaTransportControlsSession const& session,
                                               const std::string& sessionId,
                                               JNIEnv* env);

// Push the current session-id list / one session's state payload to Java.
// Safe to call from WinRT event-handler threads (attaches them to the JVM as
// daemons); no-ops when the JVM or the bridge class is unavailable.
void push_session_list();
void push_session_state(const std::string& sessionId);

// Revokes all event registrations and drops the cached manager/sessions.
// Must be called before uninit_apartment.
void shutdown_session_cache();
