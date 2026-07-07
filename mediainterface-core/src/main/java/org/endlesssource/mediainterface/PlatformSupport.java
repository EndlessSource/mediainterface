package org.endlesssource.mediainterface;

import java.util.Objects;

/**
 * Platform availability information for a media provider.
 */
public final class PlatformSupport {
    private final String platform;
    private final boolean compiled;
    private final boolean available;
    private final String reason;

    public PlatformSupport(String platform, boolean compiled, boolean available, String reason) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.compiled = compiled;
        this.available = available;
        this.reason = reason == null ? "" : reason;
    }

    public String platform() {
        return platform;
    }

    public boolean compiled() {
        return compiled;
    }

    public boolean available() {
        return available;
    }

    public String reason() {
        return reason;
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PlatformSupport)) {
            return false;
        }
        PlatformSupport other = (PlatformSupport) obj;
        return compiled == other.compiled
                && available == other.available
                && platform.equals(other.platform)
                && reason.equals(other.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(platform, compiled, available, reason);
    }

    @Override
    public String toString() {
        return "PlatformSupport[platform=" + platform + ", compiled=" + compiled
                + ", available=" + available + ", reason=" + reason + "]";
    }
}
