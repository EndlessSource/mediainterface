package org.endlesssource.mediainterface.macos;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

final class MacOsPerlSystemMediaInterface implements SystemMediaInterface {
    private final SystemMediaOptions options;
    private final MacOsPerlMediaSession session;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();

    MacOsPerlSystemMediaInterface(SystemMediaOptions options) {
        this.options = options;
        MacOsPerlAdapter adapter = new MacOsPerlAdapter();
        this.session = new MacOsPerlMediaSession(adapter, options.isEventDrivenEnabled(), options.getSessionUpdateInterval());
    }

    @Override
    public Optional<MediaSession> getActiveSession() {
        return session.isActive() ? Optional.of(session) : Optional.empty();
    }

    @Override
    public List<MediaSession> getAllSessions() {
        return session.isActive() ? List.of(session) : List.of();
    }

    @Override
    public Optional<MediaSession> getSessionByApp(String appName) {
        String app = session.getApplicationName().toLowerCase();
        String query = appName == null ? "" : appName.toLowerCase();
        return app.contains(query) && session.isActive() ? Optional.of(session) : Optional.empty();
    }

    @Override
    public boolean hasActiveSessions() {
        return session.isActive();
    }

    @Override
    public void addSessionListener(MediaSessionListener listener) {
        listeners.add(listener);
        session.addListener(listener);
    }

    @Override
    public void removeSessionListener(MediaSessionListener listener) {
        listeners.remove(listener);
        session.removeListener(listener);
    }

    @Override
    public boolean isEventDrivenEnabled() {
        return options.isEventDrivenEnabled();
    }

    @Override
    public void close() {
        session.close();
        listeners.clear();
    }
}
