package org.endlesssource.mediainterface.macos;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.spi.PlatformMediaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MacOsPlatformMediaProvider implements PlatformMediaProvider {
    private static final Logger logger = LoggerFactory.getLogger(MacOsPlatformMediaProvider.class);

    @Override
    public String platformId() {
        return "macos";
    }

    @Override
    public boolean supportsCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac");
    }

    @Override
    public PlatformSupport probeSupport() {
        if (!supportsCurrentOs()) {
            logger.debug("macOS provider probe skipped: current OS is not macOS");
            return PlatformSupport.unavailable(platformId(), "Current OS is not macOS");
        }
        if (!MacOsPerlAdapter.isSupported()) {
            return PlatformSupport.unavailable(platformId(), "Perl adapter is unavailable on this runtime");
        }
        logger.info("Using Perl adapter backend for macOS");
        return PlatformSupport.available(platformId());
    }

    @Override
    public SystemMediaInterface create(SystemMediaOptions options) {
        return new MacOsPerlSystemMediaInterface(options);
    }
}
