package org.endlesssource.mediainterface;

import java.util.Objects;

/**
 * Platform availability information for a media provider.
 */
public record PlatformSupport(String platform, boolean compiled, boolean available, String reason) {
    public PlatformSupport(String platform, boolean compiled, boolean available, String reason) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.compiled = compiled;
        this.available = available;
        this.reason = reason == null ? "" : reason;
    }

    public static PlatformSupport available(String platform) {
        return new PlatformSupport(platform, true, true, "");
    }

    public static PlatformSupport unavailable(String platform, String reason) {
        return new PlatformSupport(platform, true, false, reason);
    }

    public static PlatformSupport notCompiled(String platform, String reason) {
        return new PlatformSupport(platform, false, false, reason);
    }
}
