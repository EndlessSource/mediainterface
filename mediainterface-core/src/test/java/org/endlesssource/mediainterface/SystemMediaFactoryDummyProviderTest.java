package org.endlesssource.mediainterface;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.test.DummyPlatformMediaProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SystemMediaFactoryDummyProviderTest {

    @Test
    void createSystemInterface_nullOptions_throws() {
        assertThrows(NullPointerException.class, () -> SystemMediaFactory.createSystemInterface(null));
    }

    @Test
    void createSystemInterface_usesDummyProvider_andPassesOptions() {
        SystemMediaOptions options = SystemMediaOptions.defaults()
                .withEventDrivenEnabled(false)
                .withSessionPollInterval(Duration.ofMillis(250))
                .withSessionUpdateInterval(Duration.ofMillis(200));

        try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface(options)) {
            assertNotNull(media);
            assertTrue(media.hasActiveSessions());

            Optional<MediaSession> session = media.getActiveSession();
            assertTrue(session.isPresent());
            assertEquals("Dummy Player", session.get().getApplicationName());

            Optional<NowPlaying> nowPlaying = session.get().getNowPlaying();
            assertTrue(nowPlaying.isPresent());
            assertEquals(Optional.of("Dummy Song"), nowPlaying.get().getTitle());
        }

        SystemMediaOptions captured = DummyPlatformMediaProvider.consumeLastOptions();
        assertNotNull(captured);
        assertFalse(captured.isEventDrivenEnabled());
        assertEquals(Duration.ofMillis(250), captured.getSessionPollInterval());
        assertEquals(Duration.ofMillis(200), captured.getSessionUpdateInterval());
    }

    @Test
    void currentPlatformSupport_reportsAvailableWhenDummyProviderPresent() {
        PlatformSupport support = SystemMediaFactory.getCurrentPlatformSupport();
        assertTrue(support.available());
        assertTrue(support.compiled());
        assertEquals("test-dummy", support.platform());
    }

    @Test
    void compiledAndRuntimePlatforms_includeDummyProvider() {
        assertTrue(SystemMediaFactory.getCompiledPlatforms().contains("test-dummy"));
        assertTrue(SystemMediaFactory.getRuntimeAvailablePlatforms().contains("test-dummy"));
    }
}
