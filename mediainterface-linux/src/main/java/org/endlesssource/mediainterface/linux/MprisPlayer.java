package org.endlesssource.mediainterface.linux;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
public interface MprisPlayer extends DBusInterface {
    void Next();
    void Previous();
    void Pause();
    void PlayPause();
    void Stop();
    void Play();
    void Seek(long offset);
    void SetPosition(org.freedesktop.dbus.ObjectPath trackId, long position);
    void OpenUri(String uri);

    String getPlaybackStatus();
    String getLoopStatus();
    void setLoopStatus(String loopStatus);
    double getRate();
    void setRate(double rate);
    boolean getShuffle();
    void setShuffle(boolean shuffle);
    double getVolume();
    void setVolume(double volume);
    long getPosition();
    double getMinimumRate();
    double getMaximumRate();
    boolean getCanGoNext();
    boolean getCanGoPrevious();
    boolean getCanPlay();
    boolean getCanPause();
    boolean getCanSeek();
    boolean getCanControl();

    /**
     * MPRIS Seeked signal, emitted by players when the playback position
     * changes in a way that is not the natural progression of time.
     * Position is in microseconds.
     */
    class Seeked extends DBusSignal {
        private final long position;

        public Seeked(String path, long position) throws DBusException {
            super(path, position);
            this.position = position;
        }

        public long getPosition() {
            return position;
        }
    }
}
