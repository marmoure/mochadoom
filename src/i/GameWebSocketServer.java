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
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;

public class GameWebSocketServer {

    private static final Logger LOG = Logger.getLogger(GameWebSocketServer.class.getName());
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    private static final Map<String, ScanCode> KEY_MAP = new HashMap<>();
    static {
        // Arrow / navigation
        KEY_MAP.put("ArrowLeft",  ScanCode.SC_LEFT);
        KEY_MAP.put("ArrowRight", ScanCode.SC_RIGHT);
        KEY_MAP.put("ArrowUp",    ScanCode.SC_UP);
        KEY_MAP.put("ArrowDown",  ScanCode.SC_DOWN);
        KEY_MAP.put("Home",       ScanCode.SC_HOME);
        KEY_MAP.put("End",        ScanCode.SC_END);
        KEY_MAP.put("PageUp",     ScanCode.SC_PGUP);
        KEY_MAP.put("PageDown",   ScanCode.SC_PGDOWN);
        KEY_MAP.put("Insert",     ScanCode.SC_INSERT);
        KEY_MAP.put("Delete",     ScanCode.SC_DELETE);

        // Modifiers (browser sends normalised names via getGameKey())
        KEY_MAP.put("Control",    ScanCode.SC_LCTRL);
        KEY_MAP.put("Shift",      ScanCode.SC_LSHIFT);
        KEY_MAP.put("Alt",        ScanCode.SC_LALT);

        // Editing / whitespace
        KEY_MAP.put("Backspace",  ScanCode.SC_BACKSPACE);
        KEY_MAP.put("Tab",        ScanCode.SC_TAB);
        KEY_MAP.put("CapsLock",   ScanCode.SC_CAPSLK);
        KEY_MAP.put("Space",      ScanCode.SC_SPACE);
        KEY_MAP.put("Enter",      ScanCode.SC_ENTER);
        KEY_MAP.put("Escape",     ScanCode.SC_ESCAPE);

        // System
        KEY_MAP.put("NumLock",    ScanCode.SC_NUMLK);
        KEY_MAP.put("ScrollLock", ScanCode.SC_SCROLLLK);
        KEY_MAP.put("Pause",      ScanCode.SC_PAUSE);
        KEY_MAP.put("PrintScreen",ScanCode.SC_PRTSCRN);

        // Letters — browser normalises KeyX codes to lowercase via getGameKey()
        KEY_MAP.put("a", ScanCode.SC_A); KEY_MAP.put("b", ScanCode.SC_B);
        KEY_MAP.put("c", ScanCode.SC_C); KEY_MAP.put("d", ScanCode.SC_D);
        KEY_MAP.put("e", ScanCode.SC_E); KEY_MAP.put("f", ScanCode.SC_F);
        KEY_MAP.put("g", ScanCode.SC_G); KEY_MAP.put("h", ScanCode.SC_H);
        KEY_MAP.put("i", ScanCode.SC_I); KEY_MAP.put("j", ScanCode.SC_J);
        KEY_MAP.put("k", ScanCode.SC_K); KEY_MAP.put("l", ScanCode.SC_L);
        KEY_MAP.put("m", ScanCode.SC_M); KEY_MAP.put("n", ScanCode.SC_N);
        KEY_MAP.put("o", ScanCode.SC_O); KEY_MAP.put("p", ScanCode.SC_P);
        KEY_MAP.put("q", ScanCode.SC_Q); KEY_MAP.put("r", ScanCode.SC_R);
        KEY_MAP.put("s", ScanCode.SC_S); KEY_MAP.put("t", ScanCode.SC_T);
        KEY_MAP.put("u", ScanCode.SC_U); KEY_MAP.put("v", ScanCode.SC_V);
        KEY_MAP.put("w", ScanCode.SC_W); KEY_MAP.put("x", ScanCode.SC_X);
        KEY_MAP.put("y", ScanCode.SC_Y); KEY_MAP.put("z", ScanCode.SC_Z);

        // Digits (Digit0-9 codes stripped to bare digit by getGameKey())
        KEY_MAP.put("0", ScanCode.SC_0); KEY_MAP.put("1", ScanCode.SC_1);
        KEY_MAP.put("2", ScanCode.SC_2); KEY_MAP.put("3", ScanCode.SC_3);
        KEY_MAP.put("4", ScanCode.SC_4); KEY_MAP.put("5", ScanCode.SC_5);
        KEY_MAP.put("6", ScanCode.SC_6); KEY_MAP.put("7", ScanCode.SC_7);
        KEY_MAP.put("8", ScanCode.SC_8); KEY_MAP.put("9", ScanCode.SC_9);

        // Symbol / punctuation keys — browser sends e.code directly
        KEY_MAP.put("Minus",        ScanCode.SC_MINUS);
        KEY_MAP.put("Equal",        ScanCode.SC_EQUALS);
        KEY_MAP.put("BracketLeft",  ScanCode.SC_LBRACE);
        KEY_MAP.put("BracketRight", ScanCode.SC_RBRACE);
        KEY_MAP.put("Backslash",    ScanCode.SC_BACKSLASH);
        KEY_MAP.put("Semicolon",    ScanCode.SC_SEMICOLON);
        KEY_MAP.put("Quote",        ScanCode.SC_QUOTE);
        KEY_MAP.put("Backquote",    ScanCode.SC_TILDE);
        KEY_MAP.put("Comma",        ScanCode.SC_COMMA);
        KEY_MAP.put("Period",       ScanCode.SC_PERIOD);
        KEY_MAP.put("Slash",        ScanCode.SC_SLASH);

        // Function keys
        KEY_MAP.put("F1",  ScanCode.SC_F1);  KEY_MAP.put("F2",  ScanCode.SC_F2);
        KEY_MAP.put("F3",  ScanCode.SC_F3);  KEY_MAP.put("F4",  ScanCode.SC_F4);
        KEY_MAP.put("F5",  ScanCode.SC_F5);  KEY_MAP.put("F6",  ScanCode.SC_F6);
        KEY_MAP.put("F7",  ScanCode.SC_F7);  KEY_MAP.put("F8",  ScanCode.SC_F8);
        KEY_MAP.put("F9",  ScanCode.SC_F9);  KEY_MAP.put("F10", ScanCode.SC_F10);
        KEY_MAP.put("F11", ScanCode.SC_F11); KEY_MAP.put("F12", ScanCode.SC_F12);

        // Numpad — browser sends e.code (e.g. "Numpad7") directly
        KEY_MAP.put("Numpad0", ScanCode.SC_NUMKEY0); KEY_MAP.put("Numpad1", ScanCode.SC_NUMKEY1);
        KEY_MAP.put("Numpad2", ScanCode.SC_NUMKEY2); KEY_MAP.put("Numpad3", ScanCode.SC_NUMKEY3);
        KEY_MAP.put("Numpad4", ScanCode.SC_NUMKEY4); KEY_MAP.put("Numpad5", ScanCode.SC_NUMKEY5);
        KEY_MAP.put("Numpad6", ScanCode.SC_NUMKEY6); KEY_MAP.put("Numpad7", ScanCode.SC_NUMKEY7);
        KEY_MAP.put("Numpad8", ScanCode.SC_NUMKEY8); KEY_MAP.put("Numpad9", ScanCode.SC_NUMKEY9);
        KEY_MAP.put("NumpadEnter",    ScanCode.SC_NPENTER);
        KEY_MAP.put("NumpadMultiply", ScanCode.SC_NPMULTIPLY);
        KEY_MAP.put("NumpadSubtract", ScanCode.SC_NPMINUS);
        KEY_MAP.put("NumpadAdd",      ScanCode.SC_NPPLUS);
        KEY_MAP.put("NumpadDecimal",  ScanCode.SC_NPDOT);
        KEY_MAP.put("NumpadDivide",   ScanCode.SC_NPSLASH);
        KEY_MAP.put("NumpadEqual",    ScanCode.SC_NPEQUALS);
        KEY_MAP.put("NumpadComma",    ScanCode.SC_NPCOMMA);
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
            c.sendBinary((byte) 0x01, jpeg, jpeg.length);
        }
    }

    /** Send a PCM audio chunk to all connected WebSocket clients. Called from the sound thread. */
    public void broadcastAudio(byte[] pcm, int length) {
        for (Client c : clients) {
            c.sendBinary((byte) 0x02, pcm, length);
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
                postEvent(new String(payload, StandardCharsets.UTF_8), doom);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Event dispatch (keyboard + mouse)
    // -----------------------------------------------------------------------

    private static void postEvent(String json, IDoom doom) {
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

        /**
         * Send a typed binary WebSocket frame (RFC 6455 opcode 0x2).
         * The on-wire payload is {@code [type, payload[0..length-1]]}.
         * Thread-safe: synchronized on out.
         *
         * @param type    message type byte (0x01 = video, 0x02 = audio)
         * @param payload data buffer
         * @param length  number of bytes from payload to send
         */
        void sendBinary(byte type, byte[] payload, int length) {
            final int wsLen = 1 + length; // type byte + payload
            try {
                synchronized (out) {
                    if (wsLen < 126) {
                        out.write(0x82);
                        out.write(wsLen);
                    } else if (wsLen <= 65535) {
                        out.write(new byte[]{
                            (byte) 0x82, (byte) 0x7E,
                            (byte) ((wsLen >> 8) & 0xFF), (byte) (wsLen & 0xFF)
                        });
                    } else {
                        out.write(new byte[]{
                            (byte) 0x82, (byte) 0x7F,
                            0, 0, 0, 0,
                            (byte) ((wsLen >> 24) & 0xFF), (byte) ((wsLen >> 16) & 0xFF),
                            (byte) ((wsLen >>  8) & 0xFF), (byte) (wsLen & 0xFF)
                        });
                    }
                    out.write(type);
                    out.write(payload, 0, length);
                    out.flush();
                }
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
