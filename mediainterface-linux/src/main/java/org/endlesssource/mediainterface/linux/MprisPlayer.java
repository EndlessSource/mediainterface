package org.endlesssource.mediainterface.linux;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer extends DBusInterface {
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
}
