package org.endlesssource.mediainterface.test;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.spi.PlatformMediaProvider;

import java.util.concurrent.atomic.AtomicReference;

public final class DummyPlatformMediaProvider implements PlatformMediaProvider {
    private static final AtomicReference<SystemMediaOptions> LAST_OPTIONS = new AtomicReference<>();

    @Override
    public String platformId() {
        return "test-dummy";
    }

    @Override
    public boolean supportsCurrentOs() {
        return true;
    }

    @Override
    public PlatformSupport probeSupport() {
        return PlatformSupport.available(platformId());
    }

    @Override
    public SystemMediaInterface create(SystemMediaOptions options) {
        LAST_OPTIONS.set(options);
        return new DummySystemMediaInterface();
    }

    public static SystemMediaOptions consumeLastOptions() {
        return LAST_OPTIONS.getAndSet(null);
    }
}

