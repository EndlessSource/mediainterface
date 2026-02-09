package org.endlesssource.mediainterface.linux;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisMediaPlayer2 extends DBusInterface {
    String getIdentity();
    String getDesktopEntry();
    String[] getSupportedUriSchemes();
    String[] getSupportedMimeTypes();
    boolean getCanQuit();
    boolean getCanRaise();
    boolean getHasTrackList();

    void Raise();
    void Quit();
}

