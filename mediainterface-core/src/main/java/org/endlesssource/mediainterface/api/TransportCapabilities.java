package org.endlesssource.mediainterface.api;

import java.util.Objects;

/**
 * Transport capabilities - what controls are supported
 */
public final class TransportCapabilities {
    private final boolean canPlay;
    private final boolean canPause;
    private final boolean canNext;
    private final boolean canPrevious;
    private final boolean canStop;
    private final boolean canSeek;

    public TransportCapabilities(boolean canPlay, boolean canPause, boolean canNext, boolean canPrevious,
                                 boolean canStop, boolean canSeek) {
        this.canPlay = canPlay;
        this.canPause = canPause;
        this.canNext = canNext;
        this.canPrevious = canPrevious;
        this.canStop = canStop;
        this.canSeek = canSeek;
    }

    public boolean canPlay() {
        return canPlay;
    }

    public boolean canPause() {
        return canPause;
    }

    public boolean canNext() {
        return canNext;
    }

    public boolean canPrevious() {
        return canPrevious;
    }

    public boolean canStop() {
        return canStop;
    }

    public boolean canSeek() {
        return canSeek;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TransportCapabilities)) {
            return false;
        }
        TransportCapabilities other = (TransportCapabilities) obj;
        return canPlay == other.canPlay
                && canPause == other.canPause
                && canNext == other.canNext
                && canPrevious == other.canPrevious
                && canStop == other.canStop
                && canSeek == other.canSeek;
    }

    @Override
    public int hashCode() {
        return Objects.hash(canPlay, canPause, canNext, canPrevious, canStop, canSeek);
    }

    @Override
    public String toString() {
        return "TransportCapabilities[canPlay=" + canPlay + ", canPause=" + canPause
                + ", canNext=" + canNext + ", canPrevious=" + canPrevious
                + ", canStop=" + canStop + ", canSeek=" + canSeek + "]";
    }
}
