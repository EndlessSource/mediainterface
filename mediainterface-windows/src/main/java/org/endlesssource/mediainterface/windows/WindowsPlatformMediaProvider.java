package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.spi.PlatformMediaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsPlatformMediaProvider implements PlatformMediaProvider {
    private static final Logger logger = LoggerFactory.getLogger(WindowsPlatformMediaProvider.class);

    @Override
    public String platformId() {
        return "windows";
    }

    @Override
    public boolean supportsCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    @Override
    public PlatformSupport probeSupport() {
        if (!supportsCurrentOs()) {
            logger.debug("Windows provider probe skipped: current OS is not Windows");
            return PlatformSupport.unavailable(platformId(), "Current OS is not Windows");
        }
        if (!WinRtBridge.hasBundledDll()) {
            logger.warn("Windows provider probe failed: bundled native DLL resource missing");
            return PlatformSupport.unavailable(platformId(), "Missing bundled native DLL resource");
        }
        try {
            WinRtBridge.load();
            logger.debug("Windows provider probe succeeded");
            return PlatformSupport.available(platformId());
        } catch (Throwable t) {
            logger.warn("Windows provider probe failed to load native bridge: {}", t.getMessage());
            return PlatformSupport.unavailable(platformId(),
                    "Failed to load native bridge: " + t.getMessage());
        }
    }

    @Override
    public SystemMediaInterface create(SystemMediaOptions options) {
        return new WindowsSystemMediaInterface(options);
    }
}
