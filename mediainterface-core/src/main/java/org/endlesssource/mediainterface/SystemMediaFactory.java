package org.endlesssource.mediainterface;

import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.spi.PlatformMediaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public final class SystemMediaFactory {
    private static final Logger logger = LoggerFactory.getLogger(SystemMediaFactory.class);

    private SystemMediaFactory() {}

    /**
     * Create a system media interface for the current platform
     * @return SystemMediaInterface implementation for current OS
     * @throws UnsupportedOperationException if the current platform is not supported
     * @throws RuntimeException if a platform is supported but initialization fails
     */
    public static SystemMediaInterface createSystemInterface() {
        return createSystemInterface(SystemMediaOptions.defaults());
    }

    /**
     * Create a system media interface for the current platform with options
     * @param options Configuration options
     * @return SystemMediaInterface implementation for current OS
     * @throws UnsupportedOperationException if the current platform is not supported
     * @throws RuntimeException if a platform is supported but initialization fails
     */
    public static SystemMediaInterface createSystemInterface(SystemMediaOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        String currentPlatform = getPlatformName();
        logger.debug("Creating system media interface for platform={}", currentPlatform);
        List<PlatformMediaProvider> providers = loadProviders();
        List<PlatformMediaProvider> candidates = providers.stream()
                .filter(PlatformMediaProvider::supportsCurrentOs)
                .sorted(Comparator.comparing(PlatformMediaProvider::platformId))
                .toList();

        if (candidates.isEmpty()) {
            throw new UnsupportedOperationException("No provider module found for current platform: " + currentPlatform);
        }

        List<String> reasons = new ArrayList<>();
        for (PlatformMediaProvider provider : candidates) {
            logger.debug("Probing provider {}", provider.platformId());
            PlatformSupport support = provider.probeSupport();
            if (support.available()) {
                logger.info("Using media provider {}", provider.platformId());
                return provider.create(options);
            }
            reasons.add(provider.platformId() + ": " + support.reason());
        }

        throw new UnsupportedOperationException("Current platform is not runtime-available: "
                + String.join("; ", reasons));
    }

    /**
     * Check if the current platform is supported
     * @return true if the current platform has media interface support
     */
    public static boolean isPlatformSupported() {
        return getCurrentPlatformSupport().available();
    }

    /**
     * Get the current platform name
     * @return Platform name (linux, macOS, windows, unknown)
     */
    public static String getPlatformName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("nix") || os.contains("nux")) {
            return "linux";
        } else if (os.contains("mac")) {
            return "macos";
        } else if (os.contains("win")) {
            return "windows";
        } else {
            return "unknown";
        }
    }

    /**
     * Get platforms compiled into the current classpath (providers present).
     */
    public static List<String> getCompiledPlatforms() {
        return loadProviders().stream()
                .map(PlatformMediaProvider::platformId)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get platforms that are runtime-available right now.
     */
    public static List<String> getRuntimeAvailablePlatforms() {
        return loadProviders().stream()
                .map(PlatformMediaProvider::probeSupport)
                .filter(PlatformSupport::available)
                .map(PlatformSupport::platform)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get support status for the current platform.
     */
    public static PlatformSupport getCurrentPlatformSupport() {
        String current = getPlatformName();
        List<PlatformMediaProvider> candidates = loadProviders().stream()
                .filter(PlatformMediaProvider::supportsCurrentOs)
                .toList();
        if (candidates.isEmpty()) {
            return PlatformSupport.notCompiled(current,
                    "No provider module on classpath for platform: " + current);
        }
        List<PlatformSupport> probes = candidates.stream()
                .map(PlatformMediaProvider::probeSupport)
                .toList();
        Optional<PlatformSupport> available = probes.stream()
                .filter(PlatformSupport::available)
                .findFirst();
        if (available.isPresent()) {
            return available.get();
        }
        String reasons = probes.stream()
                .map(PlatformSupport::reason)
                .filter(reason -> reason != null && !reason.isBlank())
                .collect(Collectors.joining("; "));
        return PlatformSupport.unavailable(current, reasons.isBlank() ? "Provider probe failed" : reasons);
    }

    private static List<PlatformMediaProvider> loadProviders() {
        ServiceLoader<PlatformMediaProvider> loader = ServiceLoader.load(PlatformMediaProvider.class);
        List<PlatformMediaProvider> providers = new ArrayList<>();
        loader.iterator().forEachRemaining(providers::add);
        if (logger.isDebugEnabled()) {
            logger.debug("Discovered media providers: {}",
                    providers.stream().map(PlatformMediaProvider::platformId).collect(Collectors.joining(", ")));
        }
        return providers;
    }
}
