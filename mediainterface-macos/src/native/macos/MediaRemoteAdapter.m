#import "MediaRemoteAdapter.h"
#import <AppKit/AppKit.h>
#import <CoreFoundation/CoreFoundation.h>
#import <dispatch/dispatch.h>
#include <math.h>
#include <unistd.h>

typedef void (*MRGetNowPlayingInfoFn)(dispatch_queue_t, void (^)(CFDictionaryRef));
typedef void (*MRGetIsPlayingFn)(dispatch_queue_t, void (^)(Boolean));
typedef void (*MRGetNowPlayingAppPidFn)(dispatch_queue_t, void (^)(int));
typedef bool (*MRSendCommandFn)(int, CFDictionaryRef);
typedef bool (*MRSetElapsedTimeFn)(double);

static CFBundleRef gMediaRemoteBundle = NULL;
static MRGetNowPlayingInfoFn gGetNowPlayingInfo = NULL;
static MRGetIsPlayingFn gGetIsPlaying = NULL;
static MRGetNowPlayingAppPidFn gGetNowPlayingAppPid = NULL;
static MRSendCommandFn gSendCommand = NULL;
static MRSetElapsedTimeFn gSetElapsedTime = NULL;
static dispatch_queue_t gSerialDispatchQueue = NULL;
static const int WAIT_TIMEOUT_MILLIS = 2000;

static BOOL isDebugEnabled(void) {
    static dispatch_once_t onceToken;
    static BOOL enabled = NO;
    dispatch_once(&onceToken, ^{
        const char* raw = getenv("MEDIAINTERFACE_MACOS_NATIVE_DEBUG");
        if (!raw) {
            enabled = NO;
            return;
        }
        NSString* value = [[NSString stringWithUTF8String:raw] lowercaseString];
        enabled = [value isEqualToString:@"1"]
            || [value isEqualToString:@"true"]
            || [value isEqualToString:@"yes"]
            || [value isEqualToString:@"on"];
    });
    return enabled;
}

static void logLine(NSString* line) {
    if (!isDebugEnabled()) return;
    if (!line) return;
    fprintf(stderr, "[MediaRemoteAdapter] %s\n", [line UTF8String]);
    fflush(stderr);
}

static NSString* envValue(NSString* key) {
    const char* c = getenv([key UTF8String]);
    if (!c) return nil;
    return [NSString stringWithUTF8String:c];
}

static NSString* envFuncParam(NSString* func, int pos, NSString* name) {
    NSString* key = [NSString stringWithFormat:@"MEDIAREMOTEADAPTER_PARAM_%@_%d_%@", func, pos, name];
    return envValue(key);
}

static void* resolveFunctionPointer(CFStringRef symbolName) {
    if (!gMediaRemoteBundle) {
        return NULL;
    }
    return CFBundleGetFunctionPointerForName(gMediaRemoteBundle, symbolName);
}

static void ensureLoaded(void) {
    if (gMediaRemoteBundle) return;
    logLine(@"Loading /System/Library/PrivateFrameworks/MediaRemote.framework");
    CFURLRef bundleURL = (__bridge CFURLRef)[NSURL fileURLWithPath:@"/System/Library/PrivateFrameworks/MediaRemote.framework"];
    gMediaRemoteBundle = CFBundleCreate(kCFAllocatorDefault, bundleURL);
    if (!gMediaRemoteBundle) {
        logLine(@"CFBundleCreate failed for MediaRemote.framework");
        return;
    }
    gGetNowPlayingInfo = (MRGetNowPlayingInfoFn)resolveFunctionPointer(CFSTR("MRMediaRemoteGetNowPlayingInfo"));
    gGetIsPlaying = (MRGetIsPlayingFn)resolveFunctionPointer(CFSTR("MRMediaRemoteGetNowPlayingApplicationIsPlaying"));
    gGetNowPlayingAppPid = (MRGetNowPlayingAppPidFn)resolveFunctionPointer(CFSTR("MRMediaRemoteGetNowPlayingApplicationPID"));
    gSendCommand = (MRSendCommandFn)resolveFunctionPointer(CFSTR("MRMediaRemoteSendCommand"));
    gSetElapsedTime = (MRSetElapsedTimeFn)resolveFunctionPointer(CFSTR("MRMediaRemoteSetElapsedTime"));
    gSerialDispatchQueue = dispatch_queue_create("mediainterface.macos.serial", DISPATCH_QUEUE_SERIAL);
    logLine([NSString stringWithFormat:@"Symbols: getInfo=%d getIsPlaying=%d getPid=%d send=%d seek=%d",
             gGetNowPlayingInfo != NULL, gGetIsPlaying != NULL, gGetNowPlayingAppPid != NULL, gSendCommand != NULL, gSetElapsedTime != NULL]);
}

static NSString* escapeJson(NSString* s) {
    if (!s) return @"";
    NSMutableString* m = [s mutableCopy];
    [m replaceOccurrencesOfString:@"\\" withString:@"\\\\" options:0 range:NSMakeRange(0, m.length)];
    [m replaceOccurrencesOfString:@"\"" withString:@"\\\"" options:0 range:NSMakeRange(0, m.length)];
    [m replaceOccurrencesOfString:@"\n" withString:@" " options:0 range:NSMakeRange(0, m.length)];
    [m replaceOccurrencesOfString:@"\r" withString:@" " options:0 range:NSMakeRange(0, m.length)];
    return m;
}

static NSString* summarizeForLog(NSString* value, NSUInteger maxLen) {
    if (!value) return @"<null>";
    if (value.length <= maxLen) return value;
    return [[value substringToIndex:maxLen] stringByAppendingString:@"..."];
}

static NSDictionary* fetchNowPlayingInfo(void) {
    ensureLoaded();
    if (!gGetNowPlayingInfo) {
        logLine(@"MRMediaRemoteGetNowPlayingInfo unavailable");
        return nil;
    }
    dispatch_semaphore_t sema = dispatch_semaphore_create(0);
    __block NSDictionary* info = nil;
    gGetNowPlayingInfo(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(CFDictionaryRef dictRef) {
      if (dictRef) info = [(__bridge NSDictionary*)dictRef copy];
      dispatch_semaphore_signal(sema);
    });
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1500 * NSEC_PER_MSEC));
    if (dispatch_semaphore_wait(sema, timeout) != 0) {
        logLine(@"Timeout waiting for MRMediaRemoteGetNowPlayingInfo callback");
        return nil;
    }
    if (!info) {
        logLine(@"NowPlaying callback delivered nil dictionary");
    } else {
        logLine([NSString stringWithFormat:@"NowPlaying dictionary keys: %@", [[info allKeys] componentsJoinedByString:@","]]);
    }
    return info;
}

static NSNumber* fetchIsPlaying(void) {
    ensureLoaded();
    if (!gGetIsPlaying) {
        logLine(@"MRMediaRemoteGetNowPlayingApplicationIsPlaying unavailable");
        return nil;
    }
    dispatch_semaphore_t sema = dispatch_semaphore_create(0);
    __block BOOL got = NO;
    __block BOOL playing = NO;
    gGetIsPlaying(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(Boolean isPlaying) {
      got = YES;
      playing = isPlaying;
      dispatch_semaphore_signal(sema);
    });
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1500 * NSEC_PER_MSEC));
    if (dispatch_semaphore_wait(sema, timeout) != 0 || !got) {
        logLine(@"Timeout waiting for isPlaying callback");
        return nil;
    }
    logLine([NSString stringWithFormat:@"isPlaying callback: %@", playing ? @"true" : @"false"]);
    return @(playing);
}

static NSString* extractBundleIdentifierFromInfo(NSDictionary* info) {
    if (!info) return nil;

    id direct = info[@"kMRMediaRemoteNowPlayingInfoBundleIdentifier"];
    if ([direct isKindOfClass:[NSString class]] && [((NSString*)direct) length] > 0) {
        return (NSString*)direct;
    }

    id appBundle = info[@"kMRMediaRemoteNowPlayingInfoApplicationBundleIdentifier"];
    if ([appBundle isKindOfClass:[NSString class]] && [((NSString*)appBundle) length] > 0) {
        return (NSString*)appBundle;
    }

    return nil;
}

static NSString* fetchNowPlayingBundleIdentifier(NSDictionary* info) {
    NSString* fromInfo = extractBundleIdentifierFromInfo(info);
    if (fromInfo && fromInfo.length > 0) {
        return fromInfo;
    }

    ensureLoaded();
    if (!gGetNowPlayingAppPid) {
        return nil;
    }

    dispatch_semaphore_t sema = dispatch_semaphore_create(0);
    __block BOOL got = NO;
    __block int pid = -1;
    gGetNowPlayingAppPid(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(int appPid) {
        got = YES;
        pid = appPid;
        dispatch_semaphore_signal(sema);
    });
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1500 * NSEC_PER_MSEC));
    if (dispatch_semaphore_wait(sema, timeout) != 0 || !got || pid <= 0) {
        return nil;
    }

    NSRunningApplication* app = [NSRunningApplication runningApplicationWithProcessIdentifier:(pid_t)pid];
    if (!app) {
        return nil;
    }
    return app.bundleIdentifier;
}

static double nowInUnixSeconds(void) {
    return [[NSDate date] timeIntervalSince1970];
}

static NSNumber* extractTimestampEpochSeconds(id timestampValue) {
    if (!timestampValue) {
        return nil;
    }
    if ([timestampValue isKindOfClass:[NSDate class]]) {
        return @([(NSDate*)timestampValue timeIntervalSince1970]);
    }
    if ([timestampValue respondsToSelector:@selector(doubleValue)]) {
        double raw = [timestampValue doubleValue];
        // Heuristic: large values are likely Unix epoch seconds already.
        if (raw > 1000000000.0) {
            return @(raw);
        }
        // Otherwise assume Cocoa reference-date seconds.
        return @(raw + 978307200.0);
    }
    return nil;
}

static NSNumber* extractElapsedSeconds(NSDictionary* info) {
    if (!info) {
        return nil;
    }
    id e = info[@"kMRMediaRemoteNowPlayingInfoElapsedTime"];
    if ([e respondsToSelector:@selector(doubleValue)]) {
        return @([e doubleValue]);
    }
    return nil;
}

static BOOL seekAppearsApplied(double targetSeconds) {
    const int attempts = 6;
    const useconds_t sleepMicros = 120000;
    for (int i = 0; i < attempts; i++) {
        NSDictionary* info = fetchNowPlayingInfo();
        NSNumber* elapsed = extractElapsedSeconds(info);
        if (elapsed) {
            double delta = fabs([elapsed doubleValue] - targetSeconds);
            if (delta <= 2.5) {
                return YES;
            }
        }
        usleep(sleepMicros);
    }
    return NO;
}

static NSNumber* estimateElapsedNow(NSNumber* elapsed, NSNumber* timestampEpoch, NSNumber* duration, NSNumber* playbackRate) {
    if (!elapsed) {
        return nil;
    }
    double elapsedSeconds = [elapsed doubleValue];
    if (!timestampEpoch) {
        return @(elapsedSeconds);
    }

    double rate = 0.0;
    if (playbackRate) {
        rate = [playbackRate doubleValue];
    }
    if (rate <= 0.0) {
        return @(elapsedSeconds);
    }

    double delta = nowInUnixSeconds() - [timestampEpoch doubleValue];
    if (delta < 0.0) {
        delta = 0.0;
    }
    if (elapsedSeconds <= 0.001 && delta > 5.0) {
        // Some players briefly report stale timestamps at track start.
        return @(0.0);
    }

    double estimated = elapsedSeconds + (delta * rate);

    if (duration) {
        double dur = [duration doubleValue];
        if (dur > 0.0 && estimated > dur) {
            estimated = dur;
        }
    }
    return @(estimated);
}

static NSNumber* effectivePlaybackRate(NSNumber* playbackRate, NSNumber* defaultPlaybackRate, NSNumber* playing) {
    if (playbackRate && [playbackRate doubleValue] > 0.0) {
        return playbackRate;
    }
    if (defaultPlaybackRate && [defaultPlaybackRate doubleValue] > 0.0) {
        return defaultPlaybackRate;
    }
    if (playing && [playing boolValue]) {
        // Some players report playbackRate=0 while actually playing.
        return @(1.0);
    }
    return @(0.0);
}

static void waitForCommandCompletion(void) {
    ensureLoaded();
    if (!gGetNowPlayingAppPid || !gSerialDispatchQueue) {
        return;
    }
    dispatch_semaphore_t sema = dispatch_semaphore_create(0);
    gGetNowPlayingAppPid(gSerialDispatchQueue, ^(int pid) {
        (void)pid;
        dispatch_semaphore_signal(sema);
    });
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(WAIT_TIMEOUT_MILLIS * NSEC_PER_MSEC));
    dispatch_semaphore_wait(sema, timeout);
}

static NSString* jsonOutput(NSDictionary* info, NSNumber* playing, NSString* bundleIdentifier) {
    NSString* title = @"";
    NSString* artist = @"";
    NSString* album = @"";
    NSString* artworkBase64 = @"";
    NSString* artworkMimeType = @"";
    NSNumber* duration = nil;
    NSNumber* elapsed = nil;
    NSNumber* timestampEpoch = nil;
    NSNumber* playbackRate = nil;
    NSNumber* defaultPlaybackRate = nil;

    if (info) {
        id t = info[@"kMRMediaRemoteNowPlayingInfoTitle"];
        if ([t isKindOfClass:[NSString class]]) title = t;
        id a = info[@"kMRMediaRemoteNowPlayingInfoArtist"];
        if ([a isKindOfClass:[NSString class]]) artist = a;
        id al = info[@"kMRMediaRemoteNowPlayingInfoAlbum"];
        if ([al isKindOfClass:[NSString class]]) album = al;
        id artwork = info[@"kMRMediaRemoteNowPlayingInfoArtworkData"];
        if ([artwork isKindOfClass:[NSData class]] && [((NSData*)artwork) length] > 0) {
            artworkBase64 = [((NSData*)artwork) base64EncodedStringWithOptions:0];
        }
        id mime = info[@"kMRMediaRemoteNowPlayingInfoArtworkMIMEType"];
        if ([mime isKindOfClass:[NSString class]]) artworkMimeType = mime;
        id d = info[@"kMRMediaRemoteNowPlayingInfoDuration"];
        if ([d respondsToSelector:@selector(doubleValue)]) duration = @([d doubleValue]);
        id e = info[@"kMRMediaRemoteNowPlayingInfoElapsedTime"];
        if ([e respondsToSelector:@selector(doubleValue)]) elapsed = @([e doubleValue]);
        id ts = info[@"kMRMediaRemoteNowPlayingInfoTimestamp"];
        timestampEpoch = extractTimestampEpochSeconds(ts);
        id rate = info[@"kMRMediaRemoteNowPlayingInfoPlaybackRate"];
        if ([rate respondsToSelector:@selector(doubleValue)]) playbackRate = @([rate doubleValue]);
        id defaultRate = info[@"kMRMediaRemoteNowPlayingInfoDefaultPlaybackRate"];
        if ([defaultRate respondsToSelector:@selector(doubleValue)]) defaultPlaybackRate = @([defaultRate doubleValue]);
    }

    if (title.length == 0) {
        logLine(@"No title found in now playing payload; returning null");
        return @"null";
    }

    NSNumber* effectivePlaying = playing;
    if (!effectivePlaying && playbackRate) {
        effectivePlaying = @([playbackRate doubleValue] > 0.0);
    }
    NSNumber* resolvedRate = effectivePlaybackRate(playbackRate, defaultPlaybackRate, effectivePlaying);

    NSString* playingStr = effectivePlaying ? ([effectivePlaying boolValue] ? @"true" : @"false") : @"null";
    NSString* durationStr = duration ? [duration stringValue] : @"null";
    NSString* elapsedStr = elapsed ? [elapsed stringValue] : @"null";
    NSNumber* elapsedNow = estimateElapsedNow(elapsed, timestampEpoch, duration, resolvedRate);
    NSString* elapsedNowStr = elapsedNow ? [elapsedNow stringValue] : @"null";
    NSString* timestampStr = timestampEpoch ? [timestampEpoch stringValue] : @"null";

    NSString* playbackRateStr = resolvedRate ? [resolvedRate stringValue] : @"null";
    NSUInteger artworkChars = artworkBase64.length;

    NSString* app = (bundleIdentifier && bundleIdentifier.length > 0)
        ? bundleIdentifier
        : @"unknown";

    logLine([NSString stringWithFormat:
             @"payload app=%@ title=%@ artist=%@ album=%@ playing=%@ duration=%@ elapsed=%@ elapsedNow=%@ timestamp=%@ rate=%@ artworkChars=%lu mime=%@",
             summarizeForLog(app, 80),
             summarizeForLog(title, 120),
             summarizeForLog(artist, 120),
             summarizeForLog(album, 120),
             playingStr,
             durationStr,
             elapsedStr,
             elapsedNowStr,
             timestampStr,
             resolvedRate ? [resolvedRate stringValue] : @"null",
             (unsigned long)artworkChars,
             summarizeForLog(artworkMimeType, 64)]);

    return [NSString stringWithFormat:
            @"{\"bundleIdentifier\":\"%@\",\"playing\":%@,\"title\":\"%@\",\"artist\":\"%@\",\"album\":\"%@\",\"artworkData\":\"%@\",\"artworkMimeType\":\"%@\",\"duration\":%@,\"elapsedTime\":%@,\"elapsedTimeNow\":%@,\"timestamp\":%@,\"playbackRate\":%@}",
            escapeJson(app),
            playingStr,
            escapeJson(title),
            escapeJson(artist),
            escapeJson(album),
            escapeJson(artworkBase64),
            escapeJson(artworkMimeType),
            durationStr,
            elapsedStr,
            elapsedNowStr,
            timestampStr,
            playbackRateStr];
}

void adapter_get(void) {
    @autoreleasepool {
        logLine(@"adapter_get invoked");
        NSDictionary* info = fetchNowPlayingInfo();
        NSNumber* playing = fetchIsPlaying();
        NSString* appBundle = fetchNowPlayingBundleIdentifier(info);
        NSString* output = jsonOutput(info, playing, appBundle);
        fprintf(stdout, "%s\n", [output UTF8String]);
        fflush(stdout);
    }
}

void adapter_get_env(void) { adapter_get(); }

void adapter_stream(void) {
    adapter_get();
}

void adapter_stream_env(void) { adapter_stream(); }

void adapter_send(int command) {
    ensureLoaded();
    if (!gSendCommand) {
        fprintf(stderr, "Failed to send command: MediaRemote unavailable\n");
        exit(1);
    }
    switch (command) {
        case 0: case 1: case 2: case 3:
        case 4: case 5: case 6: case 7:
        case 8: case 9: case 10: case 11:
        case 12: case 13:
            break;
        default:
            fprintf(stderr, "Invalid command: %d\n", command);
            exit(1);
    }
    logLine([NSString stringWithFormat:@"adapter_send command=%d", command]);
    bool ok = gSendCommand(command, nil);
    if (!ok) {
        fprintf(stderr, "Failed to send command %d\n", command);
        exit(1);
    }
    waitForCommandCompletion();
}

void adapter_send_env(void) {
    NSString* cmd = envFuncParam(@"adapter_send", 0, @"command");
    if (!cmd) exit(1);
    adapter_send((int)[cmd intValue]);
}

void adapter_seek(long positionMicros) {
    ensureLoaded();
    if (!gSetElapsedTime) {
        fprintf(stderr, "Failed to seek: MediaRemoteSetElapsedTime unavailable\n");
        exit(1);
    }
    if (positionMicros < 0) {
        fprintf(stderr, "Negative seek values are invalid: %ld\n", positionMicros);
        exit(1);
    }
    double seconds = (double)positionMicros / 1000000.0;
    logLine([NSString stringWithFormat:@"adapter_seek micros=%ld seconds=%.3f", positionMicros, seconds]);
    bool ok = gSetElapsedTime(seconds);
    waitForCommandCompletion();
    if (!ok && seekAppearsApplied(seconds)) {
        logLine(@"adapter_seek returned false but seek appears applied; treating as success");
        return;
    }
    if (!ok) {
        NSDictionary* info = fetchNowPlayingInfo();
        NSString* title = @"";
        NSString* artist = @"";
        NSNumber* duration = nil;
        NSNumber* elapsed = extractElapsedSeconds(info);
        if (info) {
            id t = info[@"kMRMediaRemoteNowPlayingInfoTitle"];
            if ([t isKindOfClass:[NSString class]]) title = t;
            id ar = info[@"kMRMediaRemoteNowPlayingInfoArtist"];
            if ([ar isKindOfClass:[NSString class]]) artist = ar;
            id d = info[@"kMRMediaRemoteNowPlayingInfoDuration"];
            if ([d respondsToSelector:@selector(doubleValue)]) duration = @([d doubleValue]);
        }
        logLine([NSString stringWithFormat:@"seek failed details title=%@ artist=%@ duration=%@ elapsed=%@ requested=%.3f",
                 summarizeForLog(title, 120),
                 summarizeForLog(artist, 120),
                 duration ? [duration stringValue] : @"null",
                 elapsed ? [elapsed stringValue] : @"null",
                 seconds]);
        fprintf(stderr, "Failed to seek to %ld\n", positionMicros);
        exit(1);
    }
}

void adapter_seek_env(void) {
    NSString* pos = envFuncParam(@"adapter_seek", 0, @"position");
    if (!pos) exit(1);
    adapter_seek((long)[pos longLongValue]);
}

void adapter_shuffle(int mode) { (void)mode; }
void adapter_shuffle_env(void) {}
void adapter_repeat(int mode) { (void)mode; }
void adapter_repeat_env(void) {}
void adapter_speed(int speed) { (void)speed; }
void adapter_speed_env(void) {}

void adapter_test(void) {
    @autoreleasepool {
        logLine(@"adapter_test invoked");
        NSDictionary* info = fetchNowPlayingInfo();
        NSNumber* playing = fetchIsPlaying();
        if (info || playing) {
            logLine(@"adapter_test result=ok");
            exit(0);
        }
        logLine(@"adapter_test result=failed");
        exit(1);
    }
}
