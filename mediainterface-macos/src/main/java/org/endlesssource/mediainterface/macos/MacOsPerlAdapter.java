package org.endlesssource.mediainterface.macos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MacOsPerlAdapter {
    private static final Logger logger = LoggerFactory.getLogger(MacOsPerlAdapter.class);
    private static final String SCRIPT_RESOURCE = "/native/macos/adapter/mediaremote_adapter.pl";
    private static final String FRAMEWORK_ZIP_RESOURCE_PREFIX = "/native/macos/adapter/";
    private static final String FRAMEWORK_ZIP_RESOURCE_SUFFIX = "/MediaRemoteAdapter.framework.zip";
    private static final String TEST_CLIENT_RESOURCE_SUFFIX = "/MediaRemoteAdapterTestClient";

    private final Path scriptPath;
    private final Path frameworkPath;
    private final Path testClientPath;

    MacOsPerlAdapter() {
        String envScript = trimToNull(System.getenv("MEDIAREMOTE_ADAPTER_SCRIPT_PATH"));
        String envFramework = trimToNull(System.getenv("MEDIAREMOTE_ADAPTER_FRAMEWORK_PATH"));
        String envTestClient = trimToNull(System.getenv("MEDIAREMOTE_ADAPTER_TEST_CLIENT_PATH"));

        if (envFramework != null) {
            this.scriptPath = envScript != null ? Path.of(envScript) : extractScript();
            this.frameworkPath = Path.of(envFramework);
            this.testClientPath = envTestClient != null ? Path.of(envTestClient) : null;
            logger.info("Using MediaRemote adapter in external framework mode");
        } else {
            this.scriptPath = extractScript();
            BundledAdapterAssets assets = extractBundledAdapterAssets();
            this.frameworkPath = assets.frameworkPath();
            this.testClientPath = assets.testClientPath();
            logger.info("Using MediaRemote adapter in bundled framework mode");
        }
        runOfficialRaw("test").ifPresent(out -> {
            if (!out.isBlank()) {
                logger.info("Adapter test stdout: {}", out);
            }
        });
    }

    static boolean isSupported() {
        if (!Files.isExecutable(Path.of("/usr/bin/perl"))) {
            return false;
        }
        String envFramework = trimToNull(System.getenv("MEDIAREMOTE_ADAPTER_FRAMEWORK_PATH"));
        String envScript = trimToNull(System.getenv("MEDIAREMOTE_ADAPTER_SCRIPT_PATH"));
        if (envFramework != null) {
            boolean frameworkOk = Files.exists(Path.of(envFramework));
            boolean scriptOk = envScript == null || Files.exists(Path.of(envScript));
            return frameworkOk && scriptOk;
        }
        String arch = normalizedJvmArch();
        String zipRes = FRAMEWORK_ZIP_RESOURCE_PREFIX + arch + FRAMEWORK_ZIP_RESOURCE_SUFFIX;
        try (InputStream script = MacOsPerlAdapter.class.getResourceAsStream(SCRIPT_RESOURCE);
             InputStream zip = MacOsPerlAdapter.class.getResourceAsStream(zipRes)) {
            return script != null && zip != null;
        } catch (IOException e) {
            return false;
        }
    }

    Snapshot get() {
        Optional<String> out = runOfficialGet();
        if (out.isPresent()) {
            Snapshot parsed = parseOfficialJsonSnapshot(out.get());
            if (parsed != null) {
                return parsed;
            }
        }
        return Snapshot.empty();
    }

    /**
     * Starts the adapter in streaming mode: one long-lived process that emits
     * a JSON snapshot line whenever the system now-playing state changes
     * (plus a slow heartbeat). This avoids spawning a process per poll.
     *
     * @param consumer receives each parsed snapshot (on the reader thread)
     * @param onExit invoked once when the stream process terminates
     * @return handle to the stream, or empty if the process could not start
     */
    Optional<StreamHandle> startStream(Consumer<Snapshot> consumer, Runnable onExit) {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().add("/usr/bin/perl");
        pb.command().add(scriptPath.toAbsolutePath().toString());
        pb.command().add(frameworkPath.toAbsolutePath().toString());
        if (testClientPath != null) {
            pb.command().add(testClientPath.toAbsolutePath().toString());
        }
        pb.command().add("stream");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        Snapshot snapshot = parseOfficialJsonSnapshot(line.trim());
                        if (snapshot != null) {
                            consumer.accept(snapshot);
                        }
                    }
                } catch (IOException e) {
                    logger.debug("Adapter stream reader terminated: {}", e.getMessage());
                } finally {
                    onExit.run();
                }
            }, "mediainterface-macos-stream-reader");
            reader.setDaemon(true);
            reader.start();
            logger.info("Started MediaRemote adapter stream (pid={})", process.pid());
            return Optional.of(new StreamHandle(process));
        } catch (Exception e) {
            logger.warn("Failed to start MediaRemote adapter stream: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static final class StreamHandle {
        private final Process process;

        private StreamHandle(Process process) {
            this.process = process;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void close() {
            // Closing stdin signals the adapter to exit; destroy as a fallback.
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            process.destroy();
        }
    }

    boolean play() {
        return runOfficial("send", "0");
    }

    boolean pause() {
        return runOfficial("send", "1");
    }

    boolean toggle() {
        return runOfficial("send", "2");
    }

    boolean next() {
        return runOfficial("send", "4");
    }

    boolean previous() {
        return runOfficial("send", "5");
    }

    boolean stop() {
        return runOfficial("send", "3");
    }

    boolean seek(Duration position) {
        if (position == null || position.isNegative()) {
            return false;
        }
        long requestedMicros = Math.max(0L, position.toNanos() / 1000L);
        Snapshot snapshot = get();
        long micros = clampSeekMicros(requestedMicros, snapshot.durationMs());
        logger.debug(
                "Seek request: requestedMicros={} clampedMicros={} durationMs={} title={}",
                requestedMicros,
                micros,
                snapshot.durationMs(),
                snapshot.title()
        );
        return runOfficial("seek", String.valueOf(micros));
    }

    private boolean runOfficial(String... commandArgs) {
        return runOfficialRaw(commandArgs).map(out -> !out.toLowerCase().contains("error")).orElse(false);
    }

    private Optional<String> runOfficialGet() {
        return runOfficialRaw("get", "--now");
    }

    private Optional<String> runOfficialRaw(String... commandArgs) {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().add("/usr/bin/perl");
        pb.command().add(scriptPath.toAbsolutePath().toString());
        pb.command().add(frameworkPath.toAbsolutePath().toString());
        if (testClientPath != null) {
            pb.command().add(testClientPath.toAbsolutePath().toString());
        }
        for (String a : commandArgs) {
            pb.command().add(a);
        }
        try {
            Path stdoutFile = Files.createTempFile("mediainterface_macos_adapter_out", ".txt");
            Path stderrFile = Files.createTempFile("mediainterface_macos_adapter_err", ".txt");
            stdoutFile.toFile().deleteOnExit();
            stderrFile.toFile().deleteOnExit();
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());

            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                logger.debug("Official adapter command timed out: {}", String.join(" ", pb.command()));
                return Optional.empty();
            }
            String out = Files.readString(stdoutFile, StandardCharsets.UTF_8).trim();
            String err = Files.readString(stderrFile, StandardCharsets.UTF_8).trim();
            int exitCode = p.exitValue();
            if (!err.isBlank()) {
                logger.info("Official adapter stderr ({}): {}", exitCode, err);
            }
            if (exitCode != 0) {
                logger.warn("Official adapter command failed with exit {}: {}", exitCode, String.join(" ", pb.command()));
                if (!out.isBlank()) {
                    logger.warn("Official adapter stdout on failure: {}", out);
                }
                return Optional.empty();
            }
            return Optional.of(out);
        } catch (Exception e) {
            logger.debug("Official adapter command failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Snapshot parseOfficialJsonSnapshot(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return Snapshot.empty();
        }
        String title = extractJsonString(json, "title");
        String artist = extractJsonString(json, "artist");
        String album = extractJsonString(json, "album");
        String artworkData = extractJsonString(json, "artworkData");
        String artworkMimeType = extractJsonString(json, "artworkMimeType");
        String app = extractJsonString(json, "bundleIdentifier");
        Long durationMs = extractJsonDouble(json, "duration").map(v -> Math.round(v * 1000.0)).orElse(null);
        String playingRaw = extractJsonBoolean(json, "playing").map(v -> v ? "1" : "0").orElse("u");
        Double elapsedSeconds = extractJsonDouble(json, "elapsedTime").orElse(null);
        Double elapsedNowSeconds = extractJsonDouble(json, "elapsedTimeNow").orElse(null);
        Double timestampEpochSeconds = extractJsonDouble(json, "timestamp").orElse(null);
        Double playbackRate = extractJsonDouble(json, "playbackRate").orElse(null);
        boolean active = title != null && !title.isBlank();
        String artwork = toDataUri(artworkData, artworkMimeType);
        return new Snapshot(active, app, title, artist, album, artwork, durationMs, playingRaw,
                elapsedSeconds, elapsedNowSeconds, timestampEpochSeconds, playbackRate);
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        int quoteStart = json.indexOf('"', start + needle.length());
        if (quoteStart < 0) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            char c = json.charAt(quoteEnd);
            if (c == '"' && json.charAt(quoteEnd - 1) != '\\') break;
            quoteEnd++;
        }
        if (quoteEnd >= json.length()) return null;
        String raw = json.substring(quoteStart + 1, quoteEnd);
        return raw.replace("\\\"", "\"").replace("\\n", " ").trim();
    }

    private static Optional<Double> extractJsonDouble(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return Optional.empty();
        int i = start + needle.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) end++;
        if (end <= i) return Optional.empty();
        String raw = json.substring(i, end).trim();
        try {
            return Optional.of(Double.parseDouble(raw));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> extractJsonBoolean(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return Optional.empty();
        int i = start + needle.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (json.startsWith("true", i)) return Optional.of(true);
        if (json.startsWith("false", i)) return Optional.of(false);
        return Optional.empty();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long clampSeekMicros(long requestedMicros, Long durationMs) {
        if (durationMs == null || durationMs <= 0L) {
            return Math.max(0L, requestedMicros);
        }
        long maxMicros = Math.max(0L, durationMs * 1000L);
        return Math.max(0L, Math.min(requestedMicros, maxMicros));
    }

    private static String toDataUri(String artworkData, String artworkMimeType) {
        if (artworkData == null || artworkData.isBlank()) {
            return null;
        }
        String mime = (artworkMimeType == null || artworkMimeType.isBlank()) ? "image/jpeg" : artworkMimeType.trim();
        return "data:" + mime + ";base64," + artworkData.trim();
    }

    private static Path extractScript() {
        try (InputStream in = MacOsPerlAdapter.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing Perl adapter script resource: " + SCRIPT_RESOURCE);
            }
            Path temp = Files.createTempFile("mediainterface_mediaremote_adapter", ".pl");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().setExecutable(true);
            temp.toFile().deleteOnExit();
            return temp;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract Perl adapter script", e);
        }
    }

    private static BundledAdapterAssets extractBundledAdapterAssets() {
        String arch = normalizedJvmArch();
        String frameworkZipResource = FRAMEWORK_ZIP_RESOURCE_PREFIX + arch + FRAMEWORK_ZIP_RESOURCE_SUFFIX;
        String testClientResource = FRAMEWORK_ZIP_RESOURCE_PREFIX + arch + TEST_CLIENT_RESOURCE_SUFFIX;

        try (InputStream zipIn = MacOsPerlAdapter.class.getResourceAsStream(frameworkZipResource)) {
            if (zipIn == null) {
                throw new IllegalStateException("Missing bundled adapter framework resource: " + frameworkZipResource);
            }
            Path root = Files.createTempDirectory("mediainterface_mra");
            root.toFile().deleteOnExit();
            unzipInto(zipIn, root);
            Path frameworkPath = root.resolve("MediaRemoteAdapter.framework");
            if (!Files.exists(frameworkPath.resolve("MediaRemoteAdapter"))) {
                throw new IllegalStateException("Bundled adapter framework extraction is incomplete");
            }

            Path testClientPath = null;
            try (InputStream tcIn = MacOsPerlAdapter.class.getResourceAsStream(testClientResource)) {
                if (tcIn != null) {
                    testClientPath = root.resolve("MediaRemoteAdapterTestClient");
                    Files.copy(tcIn, testClientPath, StandardCopyOption.REPLACE_EXISTING);
                    testClientPath.toFile().setExecutable(true);
                    testClientPath.toFile().deleteOnExit();
                }
            }

            return new BundledAdapterAssets(frameworkPath, testClientPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract bundled MediaRemote adapter resources", e);
        }
    }

    private static void unzipInto(InputStream zipStream, Path destination) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("Invalid zip entry path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    if (target.getFileName().toString().equals("MediaRemoteAdapter")) {
                        target.toFile().setExecutable(true);
                    }
                    target.toFile().deleteOnExit();
                }
                zis.closeEntry();
            }
        }
    }

    private static String normalizedJvmArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            return "x64";
        }
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        throw new IllegalStateException("Unsupported macOS JVM architecture for adapter: " + arch);
    }

    private static final class BundledAdapterAssets {
        private final Path frameworkPath;
        private final Path testClientPath;

        private BundledAdapterAssets(Path frameworkPath, Path testClientPath) {
            this.frameworkPath = frameworkPath;
            this.testClientPath = testClientPath;
        }

        Path frameworkPath() {
            return frameworkPath;
        }

        Path testClientPath() {
            return testClientPath;
        }
    }

    /**
     * One now-playing snapshot. Raw elapsed/timestamp/rate values are kept so
     * the playback position can be projected at read time instead of being
     * frozen at parse time.
     */
    static final class Snapshot {
        private final boolean active;
        private final String app;
        private final String title;
        private final String artist;
        private final String album;
        private final String artwork;
        private final Long durationMs;
        private final String playingRaw;
        private final Double elapsedSeconds;
        private final Double elapsedNowSeconds;
        private final Double timestampEpochSeconds;
        private final Double playbackRate;

        Snapshot(boolean active, String app, String title, String artist, String album,
                 String artwork, Long durationMs, String playingRaw,
                 Double elapsedSeconds, Double elapsedNowSeconds,
                 Double timestampEpochSeconds, Double playbackRate) {
            this.active = active;
            this.app = app;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
            this.durationMs = durationMs;
            this.playingRaw = playingRaw;
            this.elapsedSeconds = elapsedSeconds;
            this.elapsedNowSeconds = elapsedNowSeconds;
            this.timestampEpochSeconds = timestampEpochSeconds;
            this.playbackRate = playbackRate;
        }

        static Snapshot empty() {
            return new Snapshot(false, null, null, null, null, null, null, "u",
                    null, null, null, null);
        }

        Snapshot withDurationMs(Long newDurationMs) {
            return new Snapshot(active, app, title, artist, album, artwork, newDurationMs, playingRaw,
                    elapsedSeconds, elapsedNowSeconds, timestampEpochSeconds, playbackRate);
        }

        boolean active() {
            return active;
        }

        String app() {
            return app;
        }

        String title() {
            return title;
        }

        String artist() {
            return artist;
        }

        String album() {
            return album;
        }

        String artwork() {
            return artwork;
        }

        Long durationMs() {
            return durationMs;
        }

        String playingRaw() {
            return playingRaw;
        }

        /**
         * Projects the playback position to the given wall-clock time.
         */
        Long positionMsAt(long nowEpochMillis) {
            Double baseElapsed = elapsedSeconds != null ? elapsedSeconds : elapsedNowSeconds;
            if (baseElapsed == null) {
                return null;
            }

            double currentSeconds = baseElapsed;
            boolean isPlaying = "1".equals(playingRaw);
            if (isPlaying && timestampEpochSeconds != null && playbackRate != null && playbackRate > 0.0d) {
                double nowEpochSeconds = nowEpochMillis / 1000.0d;
                double delta = Math.max(0.0d, nowEpochSeconds - timestampEpochSeconds);
                currentSeconds = baseElapsed + (delta * playbackRate);
            } else if (elapsedNowSeconds != null) {
                currentSeconds = elapsedNowSeconds;
            }

            if (currentSeconds < 0.0d) {
                currentSeconds = 0.0d;
            }

            long positionMs = Math.round(currentSeconds * 1000.0d);
            if (durationMs != null && durationMs > 0L && positionMs > durationMs) {
                positionMs = durationMs;
            }
            return positionMs;
        }
    }
}
