package org.endlesssource.mediainterface.api;

/**
 * Transport capabilities - what controls are supported
 */
public record TransportCapabilities(boolean canPlay, boolean canPause, boolean canNext, boolean canPrevious,
                                    boolean canStop, boolean canSeek) {
}

