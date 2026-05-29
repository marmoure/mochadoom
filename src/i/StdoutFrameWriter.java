/*
 * StdoutFrameWriter
 *
 * Converts the current game screen (BufferedImage) to raw RGBA bytes and
 * writes framed packets to stdout via StdoutSink (which serialises writes
 * with the audio thread to prevent packet interleaving).
 *
 * Wire format per frame:
 *
 *   [4 bytes]  magic: 0x44 0x4F 0x4F 0x4D  ("DOOM")
 *   [4 bytes]  frame number   (little-endian int32)
 *   [4 bytes]  width          (little-endian int32)
 *   [4 bytes]  height         (little-endian int32)
 *   [W*H*4]    pixel data: R G B A per pixel, row-major top-down
 *
 * This format is trivially consumed by a Node.js relay → WebSocket →
 * browser <canvas> ImageData.
 */
package i;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import v.DoomGraphicSystem;

/**
 * Writes one RGBA frame packet to stdout on every call to {@link #writeFrame}.
 * Intended for headless (no-AWT-window) mode activated by the {@code -stdout}
 * command-line switch.
 */
public class StdoutFrameWriter {

    private static final byte[] MAGIC = { 0x44, 0x4F, 0x4F, 0x4D }; // "DOOM"
    private static final Logger LOGGER = Logger.getLogger(StdoutFrameWriter.class.getName());

    private int frameNumber = 0;

    /** Reused packet buffer; grown lazily to fit the largest frame seen. */
    private byte[] packetBuf;

    public StdoutFrameWriter() throws IOException {
        // StdoutSink opens FileDescriptor.out; nothing else needed here.
    }

    /**
     * Grab the current screen image from the graphic system, convert to RGBA
     * and write a framed packet to stdout.
     *
     * @param graphicSystem the running game's graphic system
     */
    public void writeFrame(DoomGraphicSystem<?, ?> graphicSystem) {
        try {
            final java.awt.Image img = graphicSystem.getScreenImage();
            if (img == null) {
                return;
            }

            final int w = graphicSystem.getScreenWidth();
            final int h = graphicSystem.getScreenHeight();

            // Obtain pixel data as ARGB ints (Java's native packed format).
            final int[] argb;
            if (img instanceof BufferedImage) {
                argb = ((BufferedImage) img).getRGB(0, 0, w, h, null, 0, w);
            } else {
                final BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                tmp.getGraphics().drawImage(img, 0, 0, null);
                argb = tmp.getRGB(0, 0, w, h, null, 0, w);
            }

            // Header is 16 bytes; pixel payload is w*h*4 bytes.
            final int packetSize = 16 + argb.length * 4;
            if (packetBuf == null || packetBuf.length < packetSize) {
                packetBuf = new byte[packetSize];
            }

            // Write header into packet buffer.
            int off = 0;
            packetBuf[off++] = MAGIC[0];
            packetBuf[off++] = MAGIC[1];
            packetBuf[off++] = MAGIC[2];
            packetBuf[off++] = MAGIC[3];
            off = writeInt32LE(packetBuf, off, frameNumber++);
            off = writeInt32LE(packetBuf, off, w);
            off = writeInt32LE(packetBuf, off, h);

            // Convert ARGB int[] → RGBA byte[] directly into packet buffer.
            for (int pixel : argb) {
                packetBuf[off++] = (byte) ((pixel >> 16) & 0xFF); // R
                packetBuf[off++] = (byte) ((pixel >>  8) & 0xFF); // G
                packetBuf[off++] = (byte) ( pixel        & 0xFF); // B
                packetBuf[off++] = (byte) ((pixel >> 24) & 0xFF); // A
            }

            StdoutSink.get().writePacket(packetBuf, packetSize);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "stdout pipe broken, shutting down", e);
            System.exit(0);
        }
    }

    /** Write a 32-bit integer in little-endian byte order into buf at off; returns new off. */
    private static int writeInt32LE(byte[] buf, int off, int v) {
        buf[off++] = (byte)  (v         & 0xFF);
        buf[off++] = (byte) ((v >>  8)  & 0xFF);
        buf[off++] = (byte) ((v >> 16)  & 0xFF);
        buf[off++] = (byte) ((v >> 24)  & 0xFF);
        return off;
    }
}
