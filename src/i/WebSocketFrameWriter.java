/*
 * WebSocketFrameWriter
 *
 * Encodes the current game screen as a JPEG and broadcasts it to all
 * connected WebSocket clients via GameWebSocketServer. Called once per
 * game tick from Engine.updateFrame() when running in -websocket mode.
 */
package i;

import v.DoomGraphicSystem;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebSocketFrameWriter {

    private static final Logger LOG = Logger.getLogger(WebSocketFrameWriter.class.getName());
    private static final float JPEG_QUALITY = 0.8f;

    private final GameWebSocketServer wsServer;

    /** Reused RGB image to avoid palette/alpha issues with JPEG encoding. */
    private BufferedImage rgbCache;

    private final ImageWriter jpegWriter;
    private final ImageWriteParam jpegParams;

    /** Reused output buffer — reset before each encode. */
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream(48 * 1024);

    public WebSocketFrameWriter(GameWebSocketServer wsServer) {
        this.wsServer = wsServer;

        final Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (it.hasNext()) {
            jpegWriter = it.next();
            jpegParams = jpegWriter.getDefaultWriteParam();
            jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            jpegParams.setCompressionQuality(JPEG_QUALITY);
        } else {
            jpegWriter = null;
            jpegParams = null;
            LOG.severe("No JPEG ImageWriter available — WebSocket frames will not be sent");
        }
    }

    public void writeFrame(DoomGraphicSystem<?, ?> gs) {
        if (jpegWriter == null) return;

        final Image img = gs.getScreenImage();
        if (img == null) return;

        final int w = gs.getScreenWidth();
        final int h = gs.getScreenHeight();

        // JPEG encoder requires TYPE_INT_RGB; game images may be palette or ARGB.
        if (rgbCache == null || rgbCache.getWidth() != w || rgbCache.getHeight() != h) {
            rgbCache = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        final Graphics2D g = rgbCache.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();

        try {
            baos.reset();
            jpegWriter.setOutput(new MemoryCacheImageOutputStream(baos));
            jpegWriter.write(null, new IIOImage(rgbCache, null, null), jpegParams);
            wsServer.broadcast(baos.toByteArray());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "JPEG encode error", e);
        }
    }
}
