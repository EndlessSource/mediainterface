package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.spi.PlatformMediaProvider;
import org.freedesktop.dbus.exceptions.DBusException;

public final class LinuxPlatformMediaProvider implements PlatformMediaProvider {
    @Override
    public String platformId() {
        return "linux";
    }

    @Override
    public boolean supportsCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("nix") || os.contains("nux");
    }

    @Override
    public PlatformSupport probeSupport() {
        if (!supportsCurrentOs()) {
            return PlatformSupport.unavailable(platformId(), "Current OS is not Linux");
        }
        try {
            Class.forName("org.freedesktop.dbus.connections.impl.DBusConnectionBuilder");
            return PlatformSupport.available(platformId());
        } catch (ClassNotFoundException e) {
            return PlatformSupport.unavailable(platformId(), "Missing D-Bus runtime classes");
        }
    }

    @Override
    public SystemMediaInterface create(SystemMediaOptions options) {
        try {
            return new LinuxSystemMediaInterface(options);
        } catch (DBusException e) {
            throw new RuntimeException("Failed to initialize Linux media interface: " + e.getMessage(), e);
        }
    }
}
