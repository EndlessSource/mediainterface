#include "bridge_shared.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <sstream>
#include <thread>
#include <unordered_map>
#include <unordered_set>

bool g_eventDriven = true;
std::mutex g_initMutex;
int g_initRefCount = 0;
bool g_apartmentInitializedByBridge = false;
std::thread::id g_apartmentInitThread;

std::mutex g_traceMutex;
jclass g_bridgeClassGlobal = nullptr;
jmethodID g_traceMethod = nullptr;
jmethodID g_sessionListMethod = nullptr;
jmethodID g_sessionStateMethod = nullptr;
JavaVM* g_jvm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

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
        jstring element = to_jstring(env, values[i]);
        env->SetObjectArrayElement(array, i, element);
        // Release eagerly: push callers run on natively-attached WinRT threads
        // that never return to Java, so locals are not freed automatically.
        if (element != nullptr) {
            env->DeleteLocalRef(element);
        }
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
    if (g_bridgeClassGlobal == nullptr) {
        g_bridgeClassGlobal = static_cast<jclass>(env->NewGlobalRef(bridgeClass));
        if (g_bridgeClassGlobal == nullptr) {
            return;
        }
    }
    if (g_traceMethod == nullptr) {
        g_traceMethod = env->GetStaticMethodID(g_bridgeClassGlobal, "traceFromNative", "(Ljava/lang/String;)V");
        if (g_traceMethod == nullptr && env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    if (g_sessionListMethod == nullptr) {
        g_sessionListMethod = env->GetStaticMethodID(g_bridgeClassGlobal, "onNativeSessionList", "([Ljava/lang/String;)V");
        if (g_sessionListMethod == nullptr && env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    if (g_sessionStateMethod == nullptr) {
        g_sessionStateMethod = env->GetStaticMethodID(
            g_bridgeClassGlobal, "onNativeSessionState", "(Ljava/lang/String;[Ljava/lang/String;)V");
        if (g_sessionStateMethod == nullptr && env->ExceptionCheck()) {
            env->ExceptionClear();
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

// Set to true after the first successful RequestAsync() call. Once warm,
// the factory stays accessible and we skip the thread-based slow path.
static std::atomic<bool> g_factory_warm{false};

std::optional<GlobalSystemMediaTransportControlsSessionManager> request_manager_safe(JNIEnv* env) {
    // Fast path: factory already warmed up, call directly (with timeout so a
    // wedged broker cannot block the calling thread indefinitely).
    if (g_factory_warm.load(std::memory_order_acquire)) {
        try {
            return await_with_timeout(GlobalSystemMediaTransportControlsSessionManager::RequestAsync());
        } catch (...) {
            return std::nullopt;
        }
    }

    // Cold path: run RequestAsync() on a dedicated Win32 thread so that
    // __try/__except can catch the cold-boot AV without JVM frames interfering.
    constexpr int kMaxAttempts = 3;
    constexpr auto kRetryDelay = std::chrono::milliseconds(750);
    for (int attempt = 0; attempt < kMaxAttempts; ++attempt) {
        if (attempt > 0) {
            trace_native(env, std::string("request_manager_safe: retry attempt=") + std::to_string(attempt));
            std::this_thread::sleep_for(kRetryDelay);
        }
        trace_native(env, std::string("request_manager_safe: cold attempt=") + std::to_string(attempt));
        GlobalSystemMediaTransportControlsSessionManager manager{nullptr};
        if (smtc_try_request_manager(&manager, env)) {
            g_factory_warm.store(true, std::memory_order_release);
            trace_native(env, "request_manager_safe: factory warmed, success");
            return manager;
        }
        trace_native(env, std::string("request_manager_safe: failed attempt=") + std::to_string(attempt));
    }
    trace_native(env, "request_manager_safe: all attempts failed, returning nullopt");
    return std::nullopt;
}

// --- Cached manager, session map, events and artwork cache ------------------

namespace {

struct SessionEntry {
    GlobalSystemMediaTransportControlsSession session{nullptr};
    winrt::event_token mediaToken{};
    winrt::event_token playbackToken{};
    winrt::event_token timelineToken{};
};

struct ArtworkEntry {
    std::string mediaKey;
    std::string base64;
};

std::mutex g_cacheMutex;
GlobalSystemMediaTransportControlsSessionManager g_manager{nullptr};
winrt::event_token g_sessionsChangedToken{};
std::unordered_map<std::string, SessionEntry> g_sessions;
std::unordered_map<std::string, ArtworkEntry> g_artwork;
std::unordered_map<std::string, MediaPropsEntry> g_mediaProps;

// Returns a JNIEnv for the current (possibly WinRT threadpool) thread,
// attaching it as a daemon thread if needed. Returns nullptr if the JVM is
// unavailable (e.g. during teardown). Attached threads are named so they are
// identifiable in logs and thread dumps; the OS thread pool recruits and
// retires them freely, so without a name each newly attached thread would
// surface as an anonymous ever-incrementing "Thread-N".
JNIEnv* attach_jni() {
    JavaVM* vm = g_jvm;
    if (vm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK && env != nullptr) {
        return env;
    }
    JavaVMAttachArgs args{};
    args.version = JNI_VERSION_1_8;
    args.name = const_cast<char*>("mediainterface-winrt-event");
    args.group = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) == JNI_OK) {
        return env;
    }
    return nullptr;
}

void invalidate_media_cache(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    g_artwork.erase(sessionId);
    g_mediaProps.erase(sessionId);
}

void register_session_events(SessionEntry& entry, const std::string& sessionId) {
    try {
        entry.mediaToken = entry.session.MediaPropertiesChanged(
            [sessionId](auto const&, auto const&) {
                invalidate_media_cache(sessionId);
                if (g_eventDriven) {
                    push_session_state(sessionId);
                }
            });
        entry.playbackToken = entry.session.PlaybackInfoChanged(
            [sessionId](auto const&, auto const&) {
                if (g_eventDriven) {
                    push_session_state(sessionId);
                }
            });
        entry.timelineToken = entry.session.TimelinePropertiesChanged(
            [sessionId](auto const&, auto const&) {
                if (g_eventDriven) {
                    push_session_state(sessionId);
                }
            });
    } catch (...) {
        // In polling mode reconciliation still works without events; in
        // event-driven mode this session will only refresh on start/rebuild
        // pushes (get_cached_media_props also refuses the cache without a
        // media hook).
    }
}

void revoke_session_events(SessionEntry& entry) {
    try {
        if (entry.mediaToken) {
            entry.session.MediaPropertiesChanged(entry.mediaToken);
        }
        if (entry.playbackToken) {
            entry.session.PlaybackInfoChanged(entry.playbackToken);
        }
        if (entry.timelineToken) {
            entry.session.TimelinePropertiesChanged(entry.timelineToken);
        }
    } catch (...) {
    }
    entry.mediaToken = {};
    entry.playbackToken = {};
    entry.timelineToken = {};
}

// Rebuilds the session map from GetSessions(). Sessions are re-registered
// wholesale because a returned session object for the same AUMID may be a
// new underlying instance whose events must be hooked afresh.
// Must be called with g_cacheMutex held.
void rebuild_sessions_locked() {
    if (!g_manager) {
        return;
    }
    std::unordered_map<std::string, SessionEntry> next;
    try {
        for (auto const& session : g_manager.GetSessions()) {
            std::string id = to_string(session.SourceAppUserModelId());
            if (id.empty() || next.count(id) != 0) {
                continue;
            }
            SessionEntry entry;
            entry.session = session;
            register_session_events(entry, id);
            next.emplace(std::move(id), std::move(entry));
        }
    } catch (...) {
        // Keep the previous cache on enumeration failure.
        for (auto& pair : next) {
            revoke_session_events(pair.second);
        }
        return;
    }

    for (auto& pair : g_sessions) {
        revoke_session_events(pair.second);
        if (next.count(pair.first) == 0) {
            g_artwork.erase(pair.first);
        }
    }
    g_sessions.swap(next);
    // A retained AUMID may now be backed by a new session instance whose
    // metadata changed while no hook was attached; artwork is safe (keyed by
    // title/artist/album) but cached media properties must be refetched.
    g_mediaProps.clear();
}

// Drops the cached manager and session objects so the next call re-requests
// them. Used when enumeration starts throwing, which indicates the cached
// manager has gone stale (e.g. the SMTC broker was restarted).
// Must be called with g_cacheMutex held.
void invalidate_manager_locked() {
    for (auto& pair : g_sessions) {
        revoke_session_events(pair.second);
    }
    g_sessions.clear();
    g_artwork.clear();
    g_mediaProps.clear();
    if (g_manager) {
        try {
            if (g_sessionsChangedToken) {
                g_manager.SessionsChanged(g_sessionsChangedToken);
            }
        } catch (...) {
        }
        g_sessionsChangedToken = {};
        g_manager = nullptr;
    }
}

}  // namespace

bool ensure_manager(JNIEnv* env) {
    {
        std::lock_guard<std::mutex> lock(g_cacheMutex);
        if (g_manager) {
            return true;
        }
    }

    // Slow path outside the lock: the request may take seconds on cold boot.
    auto manager = request_manager_safe(env);
    if (!manager.has_value()) {
        return false;
    }

    std::lock_guard<std::mutex> lock(g_cacheMutex);
    if (g_manager) {
        return true;
    }
    g_manager = manager.value();
    try {
        g_sessionsChangedToken = g_manager.SessionsChanged(
            [](auto const&, auto const&) {
                std::vector<std::string> ids;
                {
                    std::lock_guard<std::mutex> lock(g_cacheMutex);
                    rebuild_sessions_locked();
                    ids.reserve(g_sessions.size());
                    for (auto const& pair : g_sessions) {
                        ids.push_back(pair.first);
                    }
                }
                if (!g_eventDriven) {
                    return;
                }
                // Push the new list, then each session's state so newly added
                // sessions arrive warm without any Java-side read-back.
                push_session_list();
                for (auto const& id : ids) {
                    push_session_state(id);
                }
            });
    } catch (...) {
        trace_native(env, "ensure_manager: SessionsChanged registration failed");
    }
    rebuild_sessions_locked();
    return true;
}

std::optional<std::vector<std::string>> list_session_ids(JNIEnv* env) {
    if (!ensure_manager(env)) {
        // Transient failure: signal "state unknown" so the Java side keeps
        // its sessions instead of tearing them all down.
        return std::nullopt;
    }
    std::vector<std::string> ids;
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    std::unordered_set<std::string> seen;
    try {
        for (auto const& session : g_manager.GetSessions()) {
            std::string id = to_string(session.SourceAppUserModelId());
            if (!id.empty() && seen.insert(id).second) {
                ids.push_back(std::move(id));
            }
        }
    } catch (...) {
        // Enumeration throwing means the cached manager has likely gone
        // stale; drop it so the next call re-requests a fresh one.
        invalidate_manager_locked();
        return std::nullopt;
    }

    // Only rebuild (which re-registers events) when the set actually changed.
    bool changed = seen.size() != g_sessions.size();
    if (!changed) {
        for (auto const& pair : g_sessions) {
            if (seen.count(pair.first) == 0) {
                changed = true;
                break;
            }
        }
    }
    if (changed) {
        rebuild_sessions_locked();
    }
    return ids;
}

std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId, JNIEnv* env) {
    if (!ensure_manager(env)) {
        trace_native(env, "find_session: manager unavailable");
        return std::nullopt;
    }
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    auto it = g_sessions.find(sessionId);
    if (it == g_sessions.end()) {
        // Session may be new and its SessionsChanged event not yet processed.
        rebuild_sessions_locked();
        it = g_sessions.find(sessionId);
    }
    if (it == g_sessions.end()) {
        return std::nullopt;
    }
    return it->second.session;
}

std::optional<std::string> get_cached_artwork(const std::string& sessionId, const std::string& mediaKey) {
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    auto it = g_artwork.find(sessionId);
    // An empty cached value is not trusted: some apps publish metadata before
    // the thumbnail, so re-fetch until artwork appears or the media changes.
    if (it != g_artwork.end() && it->second.mediaKey == mediaKey && !it->second.base64.empty()) {
        return it->second.base64;
    }
    return std::nullopt;
}

void store_artwork(const std::string& sessionId, const std::string& mediaKey, const std::string& base64) {
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    g_artwork[sessionId] = ArtworkEntry{mediaKey, base64};
}

std::optional<MediaPropsEntry> get_cached_media_props(const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    auto sessionIt = g_sessions.find(sessionId);
    if (sessionIt == g_sessions.end() || !sessionIt->second.mediaToken) {
        // Without a live MediaPropertiesChanged hook there is no invalidation
        // signal, so serving the cache could pin stale metadata forever.
        return std::nullopt;
    }
    auto it = g_mediaProps.find(sessionId);
    if (it == g_mediaProps.end()
            || std::chrono::steady_clock::now() - it->second.fetchedAt > kMediaPropsTtl) {
        return std::nullopt;
    }
    return it->second;
}

void store_media_props(const std::string& sessionId, MediaPropsEntry entry) {
    entry.fetchedAt = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    g_mediaProps[sessionId] = std::move(entry);
}

// --- Payload building and push delivery --------------------------------------

namespace {

int playback_state_code(GlobalSystemMediaTransportControlsSessionPlaybackStatus status) {
    if (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing) {
        return 0;
    }
    if (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused) {
        return 1;
    }
    if (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Stopped) {
        return 2;
    }
    return 3;
}

// Fetches the thumbnail as base64, using the per-session artwork cache keyed
// by title/artist/album so the (potentially multi-megabyte) encode only runs
// when the media actually changed. The cache entry is also invalidated by the
// MediaPropertiesChanged event.
std::string fetch_artwork(JNIEnv* env,
                          const std::string& sessionId,
                          const std::string& mediaKey,
                          GlobalSystemMediaTransportControlsSessionMediaProperties const& mediaProps) {
    std::string artwork;
    try {
        auto thumbnail = mediaProps.Thumbnail();
        if (thumbnail) {
            trace_native(env, "snapshot: reading thumbnail");
            auto stream = await_with_timeout(thumbnail.OpenReadAsync());
            uint32_t size = static_cast<uint32_t>(std::min<uint64_t>(stream.Size(), static_cast<uint64_t>(5 * 1024 * 1024)));
            if (size > 0) {
                auto buffer = await_with_timeout(stream.ReadAsync(Buffer(size), size, InputStreamOptions::None));
                artwork = to_string(CryptographicBuffer::EncodeToBase64String(buffer));
            }
        }
    } catch (...) {
        trace_native(env, "snapshot: thumbnail fetch failed");
    }
    store_artwork(sessionId, mediaKey, artwork);
    return artwork;
}

}  // namespace

std::vector<std::string> build_session_payload(GlobalSystemMediaTransportControlsSession const& session,
                                               const std::string& sessionId,
                                               JNIEnv* env) {
    std::vector<std::string> payload(12, "");
    payload[0] = to_string(session.SourceAppUserModelId());

    auto playbackInfo = session.GetPlaybackInfo();
    auto playbackStatus = playbackInfo.PlaybackStatus();
    payload[2] = std::to_string(playback_state_code(playbackStatus));
    bool active = playbackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing ||
                  playbackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused;
    payload[1] = active ? "true" : "false";

    auto controls = playbackInfo.Controls();
    std::string caps;
    caps.push_back(controls.IsPlayEnabled() ? '1' : '0');
    caps.push_back(controls.IsPauseEnabled() ? '1' : '0');
    caps.push_back(controls.IsNextEnabled() ? '1' : '0');
    caps.push_back(controls.IsPreviousEnabled() ? '1' : '0');
    caps.push_back(controls.IsStopEnabled() ? '1' : '0');
    caps.push_back(controls.IsPlaybackPositionEnabled() ? '1' : '0');
    payload[3] = caps;

    // Media properties are served from the event-invalidated cache so the
    // steady state involves no cross-process TryGetMediaPropertiesAsync.
    GlobalSystemMediaTransportControlsSessionMediaProperties mediaProps{nullptr};
    MediaPropsEntry props;
    auto cachedProps = get_cached_media_props(sessionId);
    if (cachedProps.has_value()) {
        props = std::move(cachedProps.value());
    } else {
        mediaProps = await_with_timeout(session.TryGetMediaPropertiesAsync());
        props.title = to_string(mediaProps.Title());
        props.artist = to_string(mediaProps.Artist());
        props.album = to_string(mediaProps.AlbumTitle());

        std::ostringstream prefix;
        std::string albumArtist = to_string(mediaProps.AlbumArtist());
        if (!albumArtist.empty()) {
            prefix << "albumArtist=" << albumArtist << "\n";
        }
        if (mediaProps.TrackNumber() > 0) {
            prefix << "trackNumber=" << mediaProps.TrackNumber() << "\n";
        }
        std::vector<std::string> genres;
        for (auto const& genre : mediaProps.Genres()) {
            std::string value = to_string(genre);
            if (!value.empty()) {
                genres.push_back(value);
            }
        }
        if (!genres.empty()) {
            prefix << "genre=";
            for (size_t i = 0; i < genres.size(); ++i) {
                if (i > 0) {
                    prefix << ", ";
                }
                prefix << genres[i];
            }
            prefix << "\n";
        }
        props.metadataPrefix = prefix.str();
        store_media_props(sessionId, props);
    }
    payload[4] = props.title;
    payload[5] = props.artist;
    payload[6] = props.album;

    std::string mediaKey = props.title + "\x1f" + props.artist + "\x1f" + props.album;
    auto cachedArt = get_cached_artwork(sessionId, mediaKey);
    if (cachedArt.has_value()) {
        payload[7] = cachedArt.value();
    } else {
        // Thumbnail missing or not yet published: the live properties object
        // is needed to (re)try the fetch.
        if (!mediaProps) {
            mediaProps = await_with_timeout(session.TryGetMediaPropertiesAsync());
        }
        payload[7] = fetch_artwork(env, sessionId, mediaKey, mediaProps);
    }

    auto timeline = session.GetTimelineProperties();
    int64_t startTicks = timeline.StartTime().count();
    int64_t endTicks = timeline.EndTime().count();
    int64_t minSeekTicks = timeline.MinSeekTime().count();
    int64_t maxSeekTicks = timeline.MaxSeekTime().count();
    int64_t rawPositionTicks = timeline.Position().count();
    auto playbackRateRef = playbackInfo.PlaybackRate();
    double playbackRate = playbackRateRef ? playbackRateRef.Value() : 1.0;

    int64_t lastUpdatedTicks = timeline.LastUpdatedTime().time_since_epoch().count();
    int64_t nowTicks = winrt::clock::now().time_since_epoch().count();
    int64_t positionTicks = rawPositionTicks;
    if (playbackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing
            && playbackRate != 0.0
            && nowTicks > lastUpdatedTicks) {
        double deltaTicks = static_cast<double>(nowTicks - lastUpdatedTicks) * playbackRate;
        positionTicks += static_cast<int64_t>(deltaTicks);
    }
    if (positionTicks < 0) {
        positionTicks = 0;
    }
    if (endTicks > 0 && positionTicks > endTicks) {
        positionTicks = endTicks;
    }

    int64_t positionMs = ticks_to_millis(positionTicks);
    int64_t durationMs = ticks_to_millis(endTicks);
    payload[8] = durationMs > 0 ? std::to_string(durationMs) : "";
    payload[9] = positionMs >= 0 ? std::to_string(positionMs) : "";
    payload[10] = durationMs <= 0 ? "true" : "false";

    std::ostringstream metadata;
    metadata << props.metadataPrefix;
    metadata << "timelineStartMs=" << ticks_to_millis(startTicks) << "\n";
    metadata << "timelineEndMs=" << ticks_to_millis(endTicks) << "\n";
    metadata << "timelineMinSeekMs=" << ticks_to_millis(minSeekTicks) << "\n";
    metadata << "timelineMaxSeekMs=" << ticks_to_millis(maxSeekTicks) << "\n";
    metadata << "timelineRawPositionMs=" << ticks_to_millis(rawPositionTicks) << "\n";
    metadata << "timelineLastUpdatedTicks=" << lastUpdatedTicks << "\n";
    metadata << "timelineNowTicks=" << nowTicks << "\n";
    if (playbackRateRef) {
        metadata << "playbackRate=" << playbackRateRef.Value() << "\n";
    } else {
        metadata << "playbackRate=null\n";
    }
    metadata << "playbackStatus=" << playback_state_code(playbackStatus) << "\n";
    payload[11] = metadata.str();
    return payload;
}

void push_session_list() {
    jclass bridgeClass = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_traceMutex);
        bridgeClass = g_bridgeClassGlobal;
        method = g_sessionListMethod;
    }
    if (bridgeClass == nullptr || method == nullptr) {
        return;
    }
    std::vector<std::string> ids;
    {
        std::lock_guard<std::mutex> lock(g_cacheMutex);
        ids.reserve(g_sessions.size());
        for (auto const& pair : g_sessions) {
            ids.push_back(pair.first);
        }
    }
    JNIEnv* env = attach_jni();
    if (env == nullptr) {
        return;
    }
    jobjectArray array = new_string_array(env, ids);
    if (array == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }
    env->CallStaticVoidMethod(bridgeClass, method, array);
    env->DeleteLocalRef(array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void push_session_state(const std::string& sessionId) {
    jclass bridgeClass = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_traceMutex);
        bridgeClass = g_bridgeClassGlobal;
        method = g_sessionStateMethod;
    }
    if (bridgeClass == nullptr || method == nullptr) {
        return;
    }
    GlobalSystemMediaTransportControlsSession session{nullptr};
    {
        std::lock_guard<std::mutex> lock(g_cacheMutex);
        auto it = g_sessions.find(sessionId);
        if (it == g_sessions.end()) {
            return;
        }
        session = it->second.session;
    }
    JNIEnv* env = attach_jni();
    if (env == nullptr) {
        return;
    }
    std::vector<std::string> payload;
    try {
        payload = build_session_payload(session, sessionId, env);
    } catch (const hresult_error& e) {
        trace_hresult(env, "push_session_state", e);
        return;
    } catch (...) {
        trace_native(env, "push_session_state unknown exception");
        return;
    }
    jstring id = to_jstring(env, sessionId);
    jobjectArray array = new_string_array(env, payload);
    if (id == nullptr || array == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (id != nullptr) {
            env->DeleteLocalRef(id);
        }
        if (array != nullptr) {
            env->DeleteLocalRef(array);
        }
        return;
    }
    env->CallStaticVoidMethod(bridgeClass, method, id, array);
    env->DeleteLocalRef(id);
    env->DeleteLocalRef(array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void shutdown_session_cache() {
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    for (auto& pair : g_sessions) {
        revoke_session_events(pair.second);
    }
    g_sessions.clear();
    g_artwork.clear();
    g_mediaProps.clear();
    if (g_manager) {
        try {
            if (g_sessionsChangedToken) {
                g_manager.SessionsChanged(g_sessionsChangedToken);
            }
        } catch (...) {
        }
        g_sessionsChangedToken = {};
        g_manager = nullptr;
    }
}
