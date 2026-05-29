/*
 * StdoutAudioWriter
 *
 * Writes raw PCM audio chunks to stdout for the Java→Node.js→browser pipeline.
 * Called from the sound driver's playback thread each time a mixed audio chunk
 * is ready.
 *
 * Wire format per chunk:
 *
 *   [4 bytes]  magic:          0x44 0x4F 0x4F 0x41  ('D','O','O','A')
 *   [4 bytes]  chunk number    (little-endian uint32)
 *   [4 bytes]  sample rate     (little-endian uint32, e.g. 22050)
 *   [4 bytes]  channel count   (little-endian uint32, e.g. 2)
 *   [4 bytes]  bits per sample (little-endian uint32, e.g. 16)
 *   [4 bytes]  byte count N    (little-endian uint32)
 *   [N bytes]  PCM data        (signed 16-bit big-endian stereo interleaved)
 */
package i;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StdoutAudioWriter {

    private static final Logger LOG = Logger.getLogger(StdoutAudioWriter.class.getName());

    private final int sampleRate;
    private final int numChannels;
    private final int bitsPerSample;

    private int chunkNumber;

    /** Reused packet buffer; grown as needed. */
    private byte[] packetBuf = new byte[24 + 8192];

    public StdoutAudioWriter(int sampleRate, int numChannels, int bitsPerSample) {
        this.sampleRate = sampleRate;
        this.numChannels = numChannels;
        this.bitsPerSample = bitsPerSample;
    }

    public void writeChunk(byte[] pcm, int length) {
        final int packetSize = 24 + length;
        if (packetBuf.length < packetSize) {
            packetBuf = new byte[packetSize];
        }

        int off = 0;
        packetBuf[off++] = 'D';
        packetBuf[off++] = 'O';
        packetBuf[off++] = 'O';
        packetBuf[off++] = 'A';
        off = writeLE32(packetBuf, off, chunkNumber++);
        off = writeLE32(packetBuf, off, sampleRate);
        off = writeLE32(packetBuf, off, numChannels);
        off = writeLE32(packetBuf, off, bitsPerSample);
        off = writeLE32(packetBuf, off, length);
        System.arraycopy(pcm, 0, packetBuf, off, length);

        try {
            StdoutSink.get().writePacket(packetBuf, packetSize);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "stdout audio pipe broken, shutting down", e);
            System.exit(0);
        }
    }

    private static int writeLE32(byte[] buf, int off, int v) {
        buf[off++] = (byte)  (v         & 0xFF);
        buf[off++] = (byte) ((v >>  8)  & 0xFF);
        buf[off++] = (byte) ((v >> 16)  & 0xFF);
        buf[off++] = (byte) ((v >> 24)  & 0xFF);
        return off;
    }
}
