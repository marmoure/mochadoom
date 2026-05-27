/*
 * GameWebSocketServer
 *
 * Pure-Java WebSocket + HTTP server (no external dependencies).
 * Serves server/index.html over HTTP and streams JPEG frames to browsers
 * over WebSocket. Key events from browsers are posted back into the game.
 *
 * Wire protocol (WebSocket binary frames): JPEG-encoded game screen.
 * Key event messages (text frames): {"t":"d"|"u","k":"<keyname>"}
 */
package i;

import doom.IDoom;
import doom.event_t;
import doom.evtype_t;
import g.Signals.ScanCode;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;

public class GameWebSocketServer {

    private static final Logger LOG = Logger.getLogger(GameWebSocketServer.class.getName());
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    private static final Map<String, ScanCode> KEY_MAP = new HashMap<>();
    static {
        KEY_MAP.put("ArrowLeft",  ScanCode.SC_LEFT);
        KEY_MAP.put("ArrowRight", ScanCode.SC_RIGHT);
        KEY_MAP.put("ArrowUp",    ScanCode.SC_UP);
        KEY_MAP.put("ArrowDown",  ScanCode.SC_DOWN);
        KEY_MAP.put("Control",    ScanCode.SC_LCTRL);
        KEY_MAP.put("Shift",      ScanCode.SC_LSHIFT);
        KEY_MAP.put("Alt",        ScanCode.SC_LALT);
        KEY_MAP.put("Space",      ScanCode.SC_SPACE);
        KEY_MAP.put("Enter",      ScanCode.SC_ENTER);
        KEY_MAP.put("Escape",     ScanCode.SC_ESCAPE);
        KEY_MAP.put("Tab",        ScanCode.SC_TAB);
        KEY_MAP.put("1", ScanCode.SC_1); KEY_MAP.put("2", ScanCode.SC_2);
        KEY_MAP.put("3", ScanCode.SC_3); KEY_MAP.put("4", ScanCode.SC_4);
        KEY_MAP.put("5", ScanCode.SC_5); KEY_MAP.put("6", ScanCode.SC_6);
        KEY_MAP.put("7", ScanCode.SC_7);
        KEY_MAP.put("F1",  ScanCode.SC_F1);  KEY_MAP.put("F2",  ScanCode.SC_F2);
        KEY_MAP.put("F3",  ScanCode.SC_F3);  KEY_MAP.put("F10", ScanCode.SC_F10);
        KEY_MAP.put("F11", ScanCode.SC_F11); KEY_MAP.put("F12", ScanCode.SC_F12);
    }

    /** Start accepting connections on the given port. Non-blocking — runs in a daemon thread. */
    public void start(int port, IDoom doom) {
        Thread t = new Thread(() -> acceptLoop(port, doom), "ws-accept");
        t.setDaemon(true);
        t.start();
    }

    /** Send a JPEG frame to all connected WebSocket clients. Called from the game loop thread. */
    public void broadcast(byte[] jpeg) {
        for (Client c : clients) {
            c.sendBinary(jpeg);
        }
    }

    // -----------------------------------------------------------------------
    //  Accept loop
    // -----------------------------------------------------------------------

    private void acceptLoop(int port, IDoom doom) {
        try (ServerSocket ss = new ServerSocket(port)) {
            LOG.info("WebSocket game server → http://localhost:" + port);
            while (!Thread.currentThread().isInterrupted()) {
                final Socket s = ss.accept();
                Thread t = new Thread(() -> handleConn(s, doom), "ws-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "accept loop failed", e);
        }
    }

    // -----------------------------------------------------------------------
    //  Connection handler (runs on its own daemon thread per client)
    // -----------------------------------------------------------------------

    private void handleConn(Socket socket, IDoom doom) {
        try {
            socket.setTcpNoDelay(true);
            final InputStream  in  = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();

            final String requestLine = readLine(in);
            if (requestLine == null) return;

            final Map<String, String> hdrs = new LinkedHashMap<>();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                final int c = h.indexOf(':');
                if (c > 0) {
                    hdrs.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
                }
            }

            if (!"websocket".equalsIgnoreCase(hdrs.get("upgrade"))) return;
            final String key = hdrs.get("sec-websocket-key");
            if (key == null) return;
            doHandshake(out, key);
            final Client client = new Client(socket, out);
            clients.add(client);
            LOG.info("WS client connected   (total: " + clients.size() + ")");
            try {
                readFrames(in, out, doom);
            } finally {
                clients.remove(client);
                LOG.info("WS client disconnected (total: " + clients.size() + ")");
            }
        } catch (Exception e) {
            // disconnects and parse errors are normal — swallow silently
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    //  WebSocket handshake
    // -----------------------------------------------------------------------

    private static void doHandshake(OutputStream out, String key) throws IOException {
        final String accept;
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            accept = Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 unavailable", e);
        }
        final String resp = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // -----------------------------------------------------------------------
    //  WebSocket frame reader (RFC 6455)
    // -----------------------------------------------------------------------

    private void readFrames(InputStream in, OutputStream out, IDoom doom) throws IOException {
        while (true) {
            final int b0 = in.read(); if (b0 == -1) break;
            final int b1 = in.read(); if (b1 == -1) break;

            final int     opcode = b0 & 0x0F;
            final boolean masked = (b1 & 0x80) != 0;
            int payloadLen = b1 & 0x7F;

            if (payloadLen == 126) {
                payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (payloadLen == 127) {
                // skip high 4 bytes (frames from a browser won't exceed Integer.MAX_VALUE)
                for (int i = 0; i < 4; i++) in.read();
                payloadLen = ((in.read() & 0xFF) << 24) | ((in.read() & 0xFF) << 16)
                           | ((in.read() & 0xFF) <<  8) |  (in.read() & 0xFF);
            }

            final byte[] mask    = masked ? readFully(in, 4) : null;
            final byte[] payload = readFully(in, payloadLen);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            }

            if (opcode == 8) break; // close frame — tear down the connection
            if (opcode == 9) {      // ping → pong (opcode 0xA)
                synchronized (out) {
                    out.write(0x8A);
                    out.write(payload.length);
                    if (payload.length > 0) out.write(payload);
                    out.flush();
                }
                continue;
            }
            if (opcode == 1 || opcode == 2) {
                postKeyEvent(new String(payload, StandardCharsets.UTF_8), doom);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Key event dispatch
    // -----------------------------------------------------------------------

    private static void postKeyEvent(String json, IDoom doom) {
        final String t = jsonString(json, "t");
        final String k = jsonString(json, "k");
        if (t == null || k == null) return;

        final evtype_t type;
        if      ("d".equals(t)) type = evtype_t.ev_keydown;
        else if ("u".equals(t)) type = evtype_t.ev_keyup;
        else return;

        final ScanCode sc = KEY_MAP.get(k);
        if (sc == null) return;
        doom.PostEvent(new event_t.keyevent_t(type, sc));
    }

    /** Minimal JSON string-value extractor — handles {"t":"d","k":"ArrowLeft"} format. */
    private static String jsonString(String json, String key) {
        final String needle = "\"" + key + "\":\"";
        final int s = json.indexOf(needle);
        if (s < 0) return null;
        final int start = s + needle.length();
        final int end   = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    // -----------------------------------------------------------------------
    //  I/O helpers
    // -----------------------------------------------------------------------

    private static String readLine(InputStream in) throws IOException {
        final StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') return sb.toString();
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        final byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            final int r = in.read(buf, off, n - off);
            if (r == -1) throw new EOFException("stream ended mid-frame");
            off += r;
        }
        return buf;
    }

    // -----------------------------------------------------------------------
    //  WebSocket client (one per connection)
    // -----------------------------------------------------------------------

    private static final class Client {
        final Socket socket;
        final OutputStream out;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out    = out;
        }

        /** Send a binary WebSocket frame. Thread-safe: synchronized on out. */
        void sendBinary(byte[] payload) {
            try {
                synchronized (out) {
                    final int len = payload.length;
                    if (len < 126) {
                        out.write(0x82);
                        out.write(len);
                    } else if (len <= 65535) {
                        out.write(new byte[]{
                            (byte) 0x82, (byte) 0x7E,
                            (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF)
                        });
                    } else {
                        out.write(new byte[]{
                            (byte) 0x82, (byte) 0x7F,
                            0, 0, 0, 0,
                            (byte) ((len >> 24) & 0xFF), (byte) ((len >> 16) & 0xFF),
                            (byte) ((len >>  8) & 0xFF), (byte) (len & 0xFF)
                        });
                    }
                    out.write(payload);
                    out.flush();
                }
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
