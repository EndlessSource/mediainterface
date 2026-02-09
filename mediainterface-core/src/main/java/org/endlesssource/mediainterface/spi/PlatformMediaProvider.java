package org.endlesssource.mediainterface.spi;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;

/**
 * SPI implemented by platform-specific modules.
 */
public interface PlatformMediaProvider {

    /**
     * Stable platform id, e.g. linux/windows/macos.
     */
    String platformId();

    /**
     * True when this provider targets the current operating system.
     */
    boolean supportsCurrentOs();

    /**
     * Probe runtime availability (native deps/resources/init preconditions).
     */
    PlatformSupport probeSupport();

    /**
     * Create the platform media interface.
     */
    SystemMediaInterface create(SystemMediaOptions options);
}
