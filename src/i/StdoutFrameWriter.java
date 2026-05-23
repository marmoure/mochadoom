/*
 * StdoutFrameWriter
 *
 * Converts the current game screen (BufferedImage) to raw RGBA bytes and
 * writes framed packets to stdout. Wire format per frame:
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
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
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

    /** Buffered wrapper around System.out — never close this. */
    private final DataOutputStream out;

    private int frameNumber = 0;

    public StdoutFrameWriter() {
        // Wrap stdout in a BufferedOutputStream for throughput, then in
        // DataOutputStream for convenient multi-byte writes.
        this.out = new DataOutputStream(new BufferedOutputStream(System.out, 256 * 1024));
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
            // For BufferedImage (all our renderers produce one) getRGB() does
            // the correct palette-to-RGB translation automatically.
            final int[] argb;
            if (img instanceof BufferedImage) {
                argb = ((BufferedImage) img).getRGB(0, 0, w, h, null, 0, w);
            } else {
                // Fallback: draw into a fresh BufferedImage
                final BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                tmp.getGraphics().drawImage(img, 0, 0, null);
                argb = tmp.getRGB(0, 0, w, h, null, 0, w);
            }

            // Convert ARGB → RGBA byte array
            final byte[] rgba = argbToRgba(argb);

            // --- write packet ---
            out.write(MAGIC);                      // 4-byte magic
            writeInt32LE(out, frameNumber++);       // frame number
            writeInt32LE(out, w);                   // width
            writeInt32LE(out, h);                   // height
            out.write(rgba);                        // pixels
            out.flush();

        } catch (IOException e) {
            // Broken pipe (consumer closed): log once and exit gracefully
            LOGGER.log(Level.SEVERE, "stdout pipe broken, shutting down", e);
            System.exit(0);
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /**
     * Repack Java's 0xAARRGGBB int[] into an R,G,B,A byte[] expected by
     * the browser's ImageData / WebGL texImage2D.
     */
    private static byte[] argbToRgba(int[] argb) {
        final byte[] rgba = new byte[argb.length * 4];
        int dst = 0;
        for (int pixel : argb) {
            rgba[dst++] = (byte) ((pixel >> 16) & 0xFF); // R
            rgba[dst++] = (byte) ((pixel >>  8) & 0xFF); // G
            rgba[dst++] = (byte) ( pixel        & 0xFF); // B
            rgba[dst++] = (byte) ((pixel >> 24) & 0xFF); // A
        }
        return rgba;
    }

    /** Write a 32-bit integer in little-endian byte order. */
    private static void writeInt32LE(DataOutputStream dos, int v) throws IOException {
        dos.write( v        & 0xFF);
        dos.write((v >>  8) & 0xFF);
        dos.write((v >> 16) & 0xFF);
        dos.write((v >> 24) & 0xFF);
    }
}
