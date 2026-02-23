#include "bridge_shared.h"

#include <algorithm>
#include <sstream>
#include <unordered_set>

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionIds(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetSessionIds enter");
    try {
        trace_native(env, "nativeGetSessionIds requesting manager");
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
        trace_native(env, std::string("nativeGetSessionIds count=") + std::to_string(ids.size()));
        return new_string_array(env, ids);
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetSessionIds", e);
        return new_string_array(env, {});
    } catch (...) {
        trace_native(env, "nativeGetSessionIds unknown exception");
        return new_string_array(env, {});
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionAppName(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetSessionAppName enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            trace_native(env, "nativeGetSessionAppName no session");
            return nullptr;
        }
        std::string appId = to_string(session.value().SourceAppUserModelId());
        trace_native(env, std::string("nativeGetSessionAppName len=") + std::to_string(appId.size()));
        return to_jstring(env, appId);
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetSessionAppName", e);
        return nullptr;
    } catch (...) {
        trace_native(env, "nativeGetSessionAppName unknown exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsSessionActive(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeIsSessionActive enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            return JNI_FALSE;
        }
        auto status = session.value().GetPlaybackInfo().PlaybackStatus();
        bool active = status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing ||
                      status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused;
        trace_native(env, std::string("nativeIsSessionActive -> ") + (active ? "true" : "false"));
        return active ? JNI_TRUE : JNI_FALSE;
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeIsSessionActive", e);
        return JNI_FALSE;
    } catch (...) {
        trace_native(env, "nativeIsSessionActive unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetPlaybackState(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetPlaybackState enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
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
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetPlaybackState", e);
        return 3;
    } catch (...) {
        trace_native(env, "nativeGetPlaybackState unknown exception");
        return 3;
    }
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetCapabilities(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetCapabilities enter");
    jboolean defaults[6] = {JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE};
    jbooleanArray array = env->NewBooleanArray(6);
    if (array == nullptr) {
        trace_native(env, "nativeGetCapabilities NewBooleanArray failed");
        return nullptr;
    }
    env->SetBooleanArrayRegion(array, 0, 6, defaults);

    try {
        auto session = find_session(to_utf8(sessionId, env), env);
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
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetCapabilities", e);
    } catch (...) {
        trace_native(env, "nativeGetCapabilities unknown exception");
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetNowPlaying(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetNowPlaying enter");
    std::vector<std::string> payload(8, "");
    try {
        auto id = to_utf8(sessionId, env);
        auto session = find_session(id, env);
        if (!session.has_value()) {
            trace_native(env, "nativeGetNowPlaying no session");
            return new_string_array(env, payload);
        }

        trace_native(env, "nativeGetNowPlaying fetching media properties");
        auto mediaProps = session.value().TryGetMediaPropertiesAsync().get();
        payload[0] = to_string(mediaProps.Title());
        payload[1] = to_string(mediaProps.Artist());
        payload[2] = to_string(mediaProps.AlbumTitle());
        trace_native(env, std::string("nativeGetNowPlaying strings lens t/a/al=")
                + std::to_string(payload[0].size()) + "/"
                + std::to_string(payload[1].size()) + "/"
                + std::to_string(payload[2].size()));

        auto thumbnail = mediaProps.Thumbnail();
        if (thumbnail) {
            trace_native(env, "nativeGetNowPlaying thumbnail present");
            auto stream = thumbnail.OpenReadAsync().get();
            uint32_t size = static_cast<uint32_t>(std::min<uint64_t>(stream.Size(), static_cast<uint64_t>(5 * 1024 * 1024)));
            if (size > 0) {
                trace_native(env, std::string("nativeGetNowPlaying reading thumbnail size=") + std::to_string(size));
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

        trace_native(env, "nativeGetNowPlaying fetching timeline");
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
            metadata << "playbackRate=null\n";
        }
        metadata << "playbackStatus=" << static_cast<int>(playbackStatus) << "\n";
        payload[7] = metadata.str();
        trace_native(env, "nativeGetNowPlaying payload assembled");
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetNowPlaying", e);
    } catch (...) {
        trace_native(env, "nativeGetNowPlaying unknown exception");
    }
    trace_native(env, "nativeGetNowPlaying returning payload");
    return new_string_array(env, payload);
}
