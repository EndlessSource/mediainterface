package org.endlesssource.mediainterface.examples;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.SystemMediaFactory;
import org.endlesssource.mediainterface.api.ArtworkDecoder;
import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.api.TransportCapabilities;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SwingNowPlayingExample {
    private static final int ART_SIZE = 192;
    private static final int SEEK_RANGE = 1000;

    public static void main(String[] args) {
        PlatformSupport support = SystemMediaFactory.getCurrentPlatformSupport();
        if (!support.available()) {
            System.err.println("Unsupported platform: " + SystemMediaFactory.getPlatformName());
            System.err.println("Reason: " + support.reason());
            return;
        }

        SystemMediaOptions options = SystemMediaOptions.defaults()
                .withEventDrivenEnabled(true)
                .withSessionPollInterval(Duration.ofMillis(200))
                .withSessionUpdateInterval(Duration.ofMillis(200));

        SystemMediaInterface media = SystemMediaFactory.createSystemInterface(options);
        SwingUtilities.invokeLater(() -> createUi(media));
    }

    private static void createUi(SystemMediaInterface media) {
        JFrame frame = new JFrame("MediaInterface Swing Example");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 520);
        frame.setMinimumSize(new Dimension(700, 420));

        JLabel artwork = new JLabel("No Artwork", JLabel.CENTER);
        artwork.setPreferredSize(new Dimension(ART_SIZE, ART_SIZE));
        JPanel left = new JPanel(new BorderLayout());
        left.add(artwork, BorderLayout.NORTH);

        JTextArea tree = new JTextArea();
        tree.setEditable(false);
        tree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tree.setText(UiSnapshot.empty().tree());
        JScrollPane detailsScroll = new JScrollPane(tree);

        frame.setLayout(new BorderLayout(12, 12));
        frame.add(left, BorderLayout.WEST);
        frame.add(detailsScroll, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new BorderLayout(8, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton prev = new JButton("Prev");
        JButton play = new JButton("Play");
        JButton pause = new JButton("Pause");
        JButton toggle = new JButton("Toggle");
        JButton next = new JButton("Next");
        JButton stop = new JButton("Stop");
        buttons.add(prev);
        buttons.add(play);
        buttons.add(pause);
        buttons.add(toggle);
        buttons.add(next);
        buttons.add(stop);

        JPanel seekPanel = new JPanel(new BorderLayout(8, 0));
        JSlider seekSlider = new JSlider(0, SEEK_RANGE, 0);
        seekSlider.setEnabled(false);
        JLabel seekLabel = new JLabel("--:-- / --:--");
        seekPanel.add(seekSlider, BorderLayout.CENTER);
        seekPanel.add(seekLabel, BorderLayout.EAST);

        controlPanel.add(buttons, BorderLayout.NORTH);
        controlPanel.add(seekPanel, BorderLayout.CENTER);
        frame.add(controlPanel, BorderLayout.SOUTH);
        frame.setVisible(true);

        ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor();
        ExecutorService controlsExecutor = Executors.newSingleThreadExecutor();
        AtomicBoolean seekDragging = new AtomicBoolean(false);
        AtomicReference<UiSnapshot> latestSnapshot = new AtomicReference<>(UiSnapshot.empty());

        prev.addActionListener(e -> submitControl(controlsExecutor, media, session -> session.getControls().previous()));
        play.addActionListener(e -> submitControl(controlsExecutor, media, session -> session.getControls().play()));
        pause.addActionListener(e -> submitControl(controlsExecutor, media, session -> session.getControls().pause()));
        toggle.addActionListener(e -> submitControl(controlsExecutor, media, session -> session.getControls().togglePlayPause()));
        next.addActionListener(e -> submitControl(controlsExecutor, media, session -> session.getControls().next()));
        stop.addActionListener(e -> submitControl(controlsExecutor, media, session -> session.getControls().stop()));

        seekSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                seekDragging.set(true);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                seekDragging.set(false);
                UiSnapshot snapshot = latestSnapshot.get();
                if (!snapshot.seekEnabled() || snapshot.durationMs() == null || snapshot.durationMs() <= 0) {
                    return;
                }
                long targetMs = Math.max(0L, snapshot.durationMs() * seekSlider.getValue() / SEEK_RANGE);
                submitControl(controlsExecutor, media, session -> session.getControls().seek(Duration.ofMillis(targetMs)));
            }
        });

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                polling.shutdownNow();
                controlsExecutor.shutdownNow();
                media.close();
            }
        });

        AtomicBoolean inFlight = new AtomicBoolean(false);
        polling.scheduleWithFixedDelay(() -> {
            if (!inFlight.compareAndSet(false, true)) {
                return;
            }
            try {
                UiSnapshot snapshot = collectSnapshot(media);
                latestSnapshot.set(snapshot);
                SwingUtilities.invokeLater(() -> applySnapshot(snapshot, artwork, tree, seekSlider, seekLabel, seekDragging.get()));
            } finally {
                inFlight.set(false);
            }
        }, 0L, 200L, TimeUnit.MILLISECONDS);
    }

    private static UiSnapshot collectSnapshot(SystemMediaInterface media) {
        Optional<MediaSession> sessionOpt = media.getActiveSession();
        if (sessionOpt.isEmpty()) {
            return UiSnapshot.empty();
        }

        MediaSession session = sessionOpt.get();
        PlaybackState playbackState = session.getControls().getPlaybackState();
        TransportCapabilities caps = session.getControls().getCapabilities();
        Optional<NowPlaying> nowOpt = session.getNowPlaying();
        String tree = toTree(session, playbackState, nowOpt);

        if (nowOpt.isEmpty()) {
            return new UiSnapshot(tree, null, false, null, null);
        }

        NowPlaying now = nowOpt.get();
        ImageIcon icon = decodeArtwork(now.getArtwork().orElse(null));
        Long durationMs = now.getDuration().map(Duration::toMillis).orElse(null);
        Long positionMs = now.getPosition().map(Duration::toMillis).orElse(null);
        boolean seekEnabled = caps.canSeek() && durationMs != null && durationMs > 0;
        return new UiSnapshot(tree, icon, seekEnabled, durationMs, positionMs);
    }

    private static void applySnapshot(UiSnapshot snapshot,
                                      JLabel artworkLabel,
                                      JTextArea treeArea,
                                      JSlider seekSlider,
                                      JLabel seekLabel,
                                      boolean seekDragging) {
        treeArea.setText(snapshot.tree());
        treeArea.setCaretPosition(0);

        seekSlider.setEnabled(snapshot.seekEnabled());
        String seekText = formatMillis(snapshot.positionMs()) + " / " + formatMillis(snapshot.durationMs());
        seekLabel.setText(seekText);
        if (!seekDragging && snapshot.seekEnabled() && snapshot.durationMs() != null && snapshot.durationMs() > 0) {
            long position = snapshot.positionMs() == null ? 0L : Math.max(0L, Math.min(snapshot.positionMs(), snapshot.durationMs()));
            int sliderValue = (int) ((position * SEEK_RANGE) / snapshot.durationMs());
            seekSlider.setValue(Math.max(0, Math.min(SEEK_RANGE, sliderValue)));
        } else if (!seekDragging && !snapshot.seekEnabled()) {
            seekSlider.setValue(0);
        }

        if (snapshot.icon() == null) {
            artworkLabel.setText("No Artwork");
            artworkLabel.setIcon(null);
        } else {
            Image scaled = snapshot.icon().getImage().getScaledInstance(ART_SIZE, ART_SIZE, Image.SCALE_SMOOTH);
            artworkLabel.setIcon(new ImageIcon(scaled));
            artworkLabel.setText("");
        }
    }

    private static void submitControl(ExecutorService controlsExecutor,
                                      SystemMediaInterface media,
                                      SessionControl action) {
        controlsExecutor.submit(() -> media.getActiveSession().ifPresent(action::invoke));
    }

    private static String toTree(MediaSession session, PlaybackState state, Optional<NowPlaying> nowOpt) {
        StringBuilder out = new StringBuilder();
        out.append("MediaSession\n");
        out.append("├─ sessionId: ").append(session.getSessionId()).append('\n');
        out.append("├─ applicationName: ").append(session.getApplicationName()).append('\n');
        out.append("├─ active: ").append(session.isActive()).append('\n');
        out.append("├─ playbackState: ").append(state).append('\n');
        out.append("└─ nowPlaying");

        if (nowOpt.isEmpty()) {
            out.append(": <empty>\n");
            return out.toString();
        }
        out.append('\n');

        NowPlaying now = nowOpt.get();
        String duration = now.getDuration().map(SwingNowPlayingExample::formatDuration).orElse("--:--");
        String position = now.getPosition().map(SwingNowPlayingExample::formatDuration).orElse("--:--");
        String artwork = now.getArtwork().orElse("");
        String artworkSummary = artwork.isBlank() ? "<none>" : ("present (" + artwork.length() + " chars)");

        out.append("   ├─ title: ").append(now.getTitle().orElse("<none>")).append('\n');
        out.append("   ├─ artist: ").append(now.getArtist().orElse("<none>")).append('\n');
        out.append("   ├─ album: ").append(now.getAlbum().orElse("<none>")).append('\n');
        out.append("   ├─ duration: ").append(duration).append('\n');
        out.append("   ├─ position: ").append(position).append('\n');
        out.append("   ├─ liveStream: ").append(now.isLiveStream()).append('\n');
        out.append("   ├─ lastUpdated: ").append(now.getLastUpdated()).append('\n');
        out.append("   ├─ artwork: ").append(artworkSummary).append('\n');

        Map<String, String> metadata = now.getAdditionalMetadata();
        if (metadata == null || metadata.isEmpty()) {
            out.append("   └─ additionalMetadata: {}\n");
            return out.toString();
        }

        out.append("   └─ additionalMetadata\n");
        Map<String, String> sorted = new TreeMap<>(metadata);
        int i = 0;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            boolean last = i == sorted.size() - 1;
            out.append("      ")
                    .append(last ? "└─ " : "├─ ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append('\n');
            i++;
        }
        return out.toString();
    }

    private static ImageIcon decodeArtwork(String artwork) {
        return ArtworkDecoder.decodeBytes(artwork).map(ImageIcon::new).orElse(null);
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0L, duration.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String formatMillis(Long millis) {
        if (millis == null || millis < 0) {
            return "--:--";
        }
        return formatDuration(Duration.ofMillis(millis));
    }

    private record UiSnapshot(String tree,
                              ImageIcon icon,
                              boolean seekEnabled,
                              Long durationMs,
                              Long positionMs) {
        static UiSnapshot empty() {
            return new UiSnapshot(
                    "MediaSession\n└─ nowPlaying: <empty>\n",
                    null,
                    false,
                    null,
                    null
            );
        }
    }

    private interface SessionControl {
        void invoke(MediaSession session);
    }

    private SwingNowPlayingExample() {
    }
}
