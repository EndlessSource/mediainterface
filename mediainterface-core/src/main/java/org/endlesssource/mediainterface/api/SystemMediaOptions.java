package org.endlesssource.mediainterface.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration options for {@link SystemMediaInterface} implementations.
 */
public final class SystemMediaOptions {
    public static final Duration DEFAULT_SESSION_POLL_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_SESSION_UPDATE_INTERVAL = Duration.ofMillis(200);

    private final boolean eventDrivenEnabled;
    private final Duration sessionPollInterval;
    private final Duration sessionUpdateInterval;
    private final boolean positionUpdatesEnabled;

    private SystemMediaOptions(boolean eventDrivenEnabled,
                               Duration sessionPollInterval,
                               Duration sessionUpdateInterval,
                               boolean positionUpdatesEnabled) {
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.sessionPollInterval = requirePositive("sessionPollInterval", sessionPollInterval);
        this.sessionUpdateInterval = requirePositive("sessionUpdateInterval", sessionUpdateInterval);
        this.positionUpdatesEnabled = positionUpdatesEnabled;
    }

    public static SystemMediaOptions defaults() {
        return new SystemMediaOptions(true, DEFAULT_SESSION_POLL_INTERVAL, DEFAULT_SESSION_UPDATE_INTERVAL, true);
    }

    public boolean isEventDrivenEnabled() {
        return eventDrivenEnabled;
    }

    public Duration getSessionPollInterval() {
        return sessionPollInterval;
    }

    public Duration getSessionUpdateInterval() {
        return sessionUpdateInterval;
    }

    public boolean isPositionUpdatesEnabled() {
        return positionUpdatesEnabled;
    }

    public SystemMediaOptions withEventDrivenEnabled(boolean enabled) {
        return new SystemMediaOptions(enabled, sessionPollInterval, sessionUpdateInterval, positionUpdatesEnabled);
    }

    public SystemMediaOptions withSessionPollInterval(Duration interval) {
        return new SystemMediaOptions(eventDrivenEnabled, interval, sessionUpdateInterval, positionUpdatesEnabled);
    }

    public SystemMediaOptions withSessionUpdateInterval(Duration interval) {
        return new SystemMediaOptions(eventDrivenEnabled, sessionPollInterval, interval, positionUpdatesEnabled);
    }

    public SystemMediaOptions withPositionUpdatesEnabled(boolean enabled) {
        return new SystemMediaOptions(eventDrivenEnabled, sessionPollInterval, sessionUpdateInterval, enabled);
    }

    private static Duration requirePositive(String name, Duration value) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
