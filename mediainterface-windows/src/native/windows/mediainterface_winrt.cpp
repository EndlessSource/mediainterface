#include <jni.h>
#include <winerror.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>

#include <algorithm>
#include <optional>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

using namespace winrt;
using namespace Windows::Media::Control;
using namespace Windows::Storage::Streams;
using namespace Windows::Security::Cryptography;

namespace {
    bool g_eventDriven = true;
    std::mutex g_initMutex;
    int g_initRefCount = 0;
    bool g_apartmentInitializedByBridge = false;
    std::thread::id g_apartmentInitThread;

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
        return env->NewStringUTF(value.c_str());
    }

    int64_t ticks_to_millis(int64_t ticks) {
        return ticks / 10000;
    }

    int64_t millis_to_ticks(int64_t millis) {
        return millis * 10000;
    }

    std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId) {
        auto manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
        for (auto const& session : manager.GetSessions()) {
            if (to_string(session.SourceAppUserModelId()) == sessionId) {
                return session;
            }
        }
        return std::nullopt;
    }

    jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values) {
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
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
}

extern "C" JNIEXPORT void JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeInit(JNIEnv* env, jclass, jboolean eventDriven) {
    g_eventDriven = (eventDriven == JNI_TRUE);
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initRefCount > 0) {
        ++g_initRefCount;
        return;
    }
    try {
        init_apartment(apartment_type::multi_threaded);
        g_apartmentInitializedByBridge = true;
        g_apartmentInitThread = std::this_thread::get_id();
        g_initRefCount = 1;
    } catch (const hresult_error& e) {
        if (e.code() == hresult(RPC_E_CHANGED_MODE)) {
            // The host already initialized COM on this thread (often STA). Reuse it.
            g_apartmentInitializedByBridge = false;
            g_apartmentInitThread = std::thread::id{};
            g_initRefCount = 1;
            return;
        }
        std::ostringstream message;
        message << "Failed to initialize WinRT apartment (HRESULT 0x"
                << std::hex << static_cast<uint32_t>(e.code().value)
                << ")";
        throw_illegal_state(env, message.str());
    } catch (...) {
        throw_illegal_state(env, "Failed to initialize WinRT apartment");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeShutdown(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initRefCount == 0) {
        return;
    }
    --g_initRefCount;
    if (g_initRefCount > 0) {
        return;
    }
    if (g_apartmentInitializedByBridge && g_apartmentInitThread == std::this_thread::get_id()) {
        uninit_apartment();
    }
    g_apartmentInitializedByBridge = false;
    g_apartmentInitThread = std::thread::id{};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsEventDrivenEnabled(JNIEnv*, jclass) {
    return g_eventDriven ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionIds(JNIEnv* env, jclass) {
    try {
        auto manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
        std::vector<std::string> ids;
        std::unordered_set<std::string> seen;
        for (auto const& session : manager.GetSessions()) {
            std::string id = to_string(session.SourceAppUserModelId());
            if (id.empty()) {
                continue;
            }
            if (seen.insert(id).second) {
                ids.push_back(id);
            }
        }
        return new_string_array(env, ids);
    } catch (...) {
        return new_string_array(env, {});
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionAppName(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        if (!session.has_value()) {
            return nullptr;
        }
        std::string appId = to_string(session.value().SourceAppUserModelId());
        return to_jstring(env, appId);
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsSessionActive(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        if (!session.has_value()) {
            return JNI_FALSE;
        }
        auto status = session.value().GetPlaybackInfo().PlaybackStatus();
        bool active = status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing ||
                      status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused;
        return active ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetPlaybackState(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        if (!session.has_value()) {
            return 3;
        }
        auto status = session.value().GetPlaybackInfo().PlaybackStatus();
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
    } catch (...) {
        return 3;
    }
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetCapabilities(JNIEnv* env, jclass, jstring sessionId) {
    jboolean defaults[6] = {JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE};
    jbooleanArray array = env->NewBooleanArray(6);
    if (array == nullptr) {
        return nullptr;
    }
    env->SetBooleanArrayRegion(array, 0, 6, defaults);

    try {
        auto session = find_session(to_utf8(sessionId, env));
        if (!session.has_value()) {
            return array;
        }
        auto controls = session.value().GetPlaybackInfo().Controls();
        jboolean values[6] = {
            static_cast<jboolean>(controls.IsPlayEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsPauseEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsNextEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsPreviousEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsStopEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsPlaybackPositionEnabled() ? JNI_TRUE : JNI_FALSE)
        };
        env->SetBooleanArrayRegion(array, 0, 6, values);
    } catch (...) {
        // Keep defaults.
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetNowPlaying(JNIEnv* env, jclass, jstring sessionId) {
    std::vector<std::string> payload(8, "");
    try {
        auto session = find_session(to_utf8(sessionId, env));
        if (!session.has_value()) {
            return new_string_array(env, payload);
        }

        auto mediaProps = session.value().TryGetMediaPropertiesAsync().get();
        payload[0] = to_string(mediaProps.Title());
        payload[1] = to_string(mediaProps.Artist());
        payload[2] = to_string(mediaProps.AlbumTitle());
        auto thumbnail = mediaProps.Thumbnail();
        if (thumbnail) {
            auto stream = thumbnail.OpenReadAsync().get();
            uint32_t size = static_cast<uint32_t>(std::min<uint64_t>(stream.Size(), static_cast<uint64_t>(5 * 1024 * 1024)));
            if (size > 0) {
                auto buffer = stream.ReadAsync(Buffer(size), size, InputStreamOptions::None).get();
                payload[3] = to_string(CryptographicBuffer::EncodeToBase64String(buffer));
            }
        }

        std::ostringstream metadata;
        std::string albumArtist = to_string(mediaProps.AlbumArtist());
        if (!albumArtist.empty()) {
            metadata << "albumArtist=" << albumArtist << "\n";
        }
        if (mediaProps.TrackNumber() > 0) {
            metadata << "trackNumber=" << mediaProps.TrackNumber() << "\n";
        }
        std::vector<std::string> genres;
        for (auto const& genre : mediaProps.Genres()) {
            std::string value = to_string(genre);
            if (!value.empty()) {
                genres.push_back(value);
            }
        }
        if (!genres.empty()) {
            metadata << "genre=";
            for (size_t i = 0; i < genres.size(); ++i) {
                if (i > 0) {
                    metadata << ", ";
                }
                metadata << genres[i];
            }
            metadata << "\n";
        }
        auto timeline = session.value().GetTimelineProperties();
        int64_t startTicks = timeline.StartTime().count();
        int64_t endTicks = timeline.EndTime().count();
        int64_t minSeekTicks = timeline.MinSeekTime().count();
        int64_t maxSeekTicks = timeline.MaxSeekTime().count();
        int64_t rawPositionTicks = timeline.Position().count();
        auto playbackInfo = session.value().GetPlaybackInfo();
        auto playbackStatus = playbackInfo.PlaybackStatus();
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

        // WinRT timeline Position/EndTime are already playhead and track-end times.
        int64_t positionMs = ticks_to_millis(positionTicks);
        int64_t durationMs = ticks_to_millis(endTicks);
        payload[4] = durationMs > 0 ? std::to_string(durationMs) : "";
        payload[5] = positionMs >= 0 ? std::to_string(positionMs) : "";
        payload[6] = durationMs <= 0 ? "true" : "false";

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
            metadata << "playbackRate=" << "null" << "\n";
        }
        metadata << "playbackStatus=" << static_cast<int>(playbackStatus) << "\n";
        payload[7] = metadata.str();
    } catch (...) {
        // Return best effort payload.
    }
    return new_string_array(env, payload);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativePlay(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        return (session.has_value() && session.value().TryPlayAsync().get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativePause(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        return (session.has_value() && session.value().TryPauseAsync().get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeTogglePlayPause(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        return (session.has_value() && session.value().TryTogglePlayPauseAsync().get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeNext(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        return (session.has_value() && session.value().TrySkipNextAsync().get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativePrevious(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        return (session.has_value() && session.value().TrySkipPreviousAsync().get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeStop(JNIEnv* env, jclass, jstring sessionId) {
    try {
        auto session = find_session(to_utf8(sessionId, env));
        return (session.has_value() && session.value().TryStopAsync().get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeSeek(JNIEnv* env, jclass, jstring sessionId, jlong positionMillis) {
    if (positionMillis < 0) {
        return JNI_FALSE;
    }
    try {
        auto session = find_session(to_utf8(sessionId, env));
        if (!session.has_value()) {
            return JNI_FALSE;
        }
        uint64_t requested = static_cast<uint64_t>(millis_to_ticks(positionMillis));
        return session.value().TryChangePlaybackPositionAsync(requested).get() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
