package s;

import mochadoom.Engine;

import javax.sound.midi.*;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Music module for streaming (WebSocket) mode.
 *
 * Opens Java's built-in Gervill soft synthesizer in pull / stream mode so its
 * PCM output can be captured and forwarded to {@link Engine#updateMusic}.
 *
 * Requires JVM flag:
 *   --add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED
 *
 * If that flag is absent (Java 17+ strict encapsulation), the module silences
 * music entirely rather than falling back to hardware MIDI, so the user never
 * hears music leaking from the server terminal.
 */
public class StreamingMusicModule implements IMusic {

    private static final int SAMPLE_RATE = 22050;
    private static final int FRAMES_PER_CHUNK = 512; // ~23 ms per chunk
    private static final AudioFormat FORMAT =
        new AudioFormat(SAMPLE_RATE, 16, 2, true, true);

    private Sequencer  sequencer;
    private Synthesizer synth;
    private AudioInputStream stream;
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean songLoaded;

    // True when PCM streaming is up; false means music is silenced.
    private boolean streaming;

    @Override
    public void InitMusic() {
        try {
            synth  = findGervill();
            stream = openStream(synth, FORMAT);

            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            sequencer.getTransmitter().setReceiver(synth.getReceiver());

            running.set(true);
            streaming = true;
            Thread t = new Thread(this::readLoop, "music-pcm");
            t.setDaemon(true);
            t.start();

            System.err.println("I_InitMusic: streaming mode (PCM → WebSocket)");
        } catch (Exception e) {
            streaming = false;
            System.err.println("I_InitMusic: PCM streaming unavailable — " + e);
            System.err.println("  Music is silenced. For browser music add JVM flag:");
            System.err.println("  --add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED");
        }
    }

    private void readLoop() {
        final byte[] buf = new byte[FRAMES_PER_CHUNK * 4]; // 4 bytes / frame (stereo 16-bit)
        final long periodNs = (long)((double)FRAMES_PER_CHUNK / SAMPLE_RATE * 1_000_000_000L);
        long nextNs = System.nanoTime() + periodNs;

        while (running.get()) {
            try {
                int n = stream.read(buf, 0, buf.length);
                if (n > 0) Engine.updateMusic(buf, n);
                else if (n == -1) break;
            } catch (Exception e) {
                if (running.get()) System.err.println("music-pcm read error: " + e.getMessage());
                break;
            }
            // Pace reads to wall-clock time so we don't flood the browser.
            long sleepNs = nextNs - System.nanoTime();
            nextNs += periodNs;
            if (sleepNs > 100_000L) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L));
                } catch (InterruptedException e) { break; }
            }
        }
    }

    @Override
    public void ShutdownMusic() {
        running.set(false);
        if (sequencer != null && sequencer.isOpen()) { sequencer.stop(); sequencer.close(); }
        if (synth    != null && synth.isOpen())      synth.close();
    }

    @Override
    public void SetMusicVolume(int volume) {
        if (!streaming || synth == null) return;
        try {
            Receiver r = synth.getReceiver();
            for (int ch = 0; ch < 16; ch++) {
                ShortMessage msg = new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 7, volume);
                r.send(msg, -1);
            }
        } catch (Exception e) {
            System.err.println("SetMusicVolume: " + e.getMessage());
        }
    }

    @Override
    public void PauseSong(int handle) {
        if (streaming && sequencer != null && sequencer.isRunning()) sequencer.stop();
    }

    @Override
    public void ResumeSong(int handle) {
        if (streaming && sequencer != null && songLoaded) sequencer.start();
    }

    @Override
    public int RegisterSong(byte[] data) {
        if (!streaming || sequencer == null) return -1;
        try {
            Sequence seq;
            try {
                seq = MidiSystem.getSequence(new ByteArrayInputStream(data));
            } catch (InvalidMidiDataException e) {
                seq = MusReader.getSequence(new ByteArrayInputStream(data));
            }
            sequencer.stop();
            sequencer.setSequence(seq);
            songLoaded = true;
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    @Override
    public void PlaySong(int handle, boolean looping) {
        if (!streaming || sequencer == null || !songLoaded) return;
        sequencer.setLoopCount(looping ? Sequencer.LOOP_CONTINUOUSLY : 0);
        sequencer.start();
    }

    @Override
    public void StopSong(int handle) {
        if (streaming && sequencer != null) sequencer.stop();
    }

    @Override
    public void UnRegisterSong(int handle) {
        songLoaded = false;
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    /**
     * Finds Java's built-in Gervill SoftSynthesizer.
     * Prefers an exact class-name match so we don't accidentally pick up a
     * hardware synth that has no {@code openStream} method.
     */
    private static Synthesizer findGervill() throws MidiUnavailableException {
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(info);
                if (dev instanceof Synthesizer
                        && dev.getClass().getName().endsWith("SoftSynthesizer")) {
                    return (Synthesizer) dev;
                }
            } catch (MidiUnavailableException ignored) {}
        }
        // Fall back to the default synthesizer — may or may not be Gervill.
        return MidiSystem.getSynthesizer();
    }

    /**
     * Opens {@code synth} in PCM-pull mode via reflection on
     * {@code SoftSynthesizer.openStream(AudioFormat, Map)}.
     * Requires {@code --add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED}.
     */
    private static AudioInputStream openStream(Synthesizer synth, AudioFormat fmt)
            throws Exception {
        Method m = synth.getClass().getMethod("openStream", AudioFormat.class, java.util.Map.class);
        m.setAccessible(true);
        return (AudioInputStream) m.invoke(synth, fmt, null);
    }
}
