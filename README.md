# MochaDoom — Java Doom Library

A pure-Java Doom engine that can be embedded as a library or run standalone.
Supports three output modes: a local AWT window, WebSocket streaming to a
browser, and a raw binary stdout pipe for custom consumers.

---

## Modes at a glance

| Mode | Video | Audio | Input |
|------|-------|-------|-------|
| **Desktop** (default) | AWT window | System speakers | Keyboard/mouse on window |
| **WebSocket** (`-websocket`) | Browser `<canvas>` via JPEG | Browser Web Audio API | Browser keyboard → WebSocket |
| **Stdout** (`-stdout`) | Raw RGBA frames on stdout | Raw PCM on stdout | stdin JSON key events |

---

## Build

Requires Java 8+ and Maven.

```bash
mvn package -DskipTests
# produces target/mochadoom-1.0.0-SNAPSHOT.jar
```

> **Embedding MochaDoom in another project?** See [LIBRARY.md](LIBRARY.md) for
> dependency coordinates, the full API reference, wire protocol details, and
> integration examples in Java, Node.js, and the browser.

---

## Java library API

Add the JAR to your classpath, then use the `mochadoom` package:

```java
import mochadoom.DoomConfig;
import mochadoom.MochaDoom;

DoomConfig config = DoomConfig.builder()
    .iwad("/path/to/doom.wad")
    .webSocketPort(8080)       // omit for desktop mode
    .build();

MochaDoom doom = new MochaDoom(config);
doom.start();    // non-blocking — game runs in a background daemon thread

// later...
doom.pause();
doom.resume();
doom.stop();
```

### `DoomConfig` builder options

| Method | Default | Description |
|--------|---------|-------------|
| `.iwad(String path)` | auto-discover | Path to the IWAD file (`doom.wad`, `doom2.wad`, …) |
| `.webSocketPort(int port)` | disabled | Start WebSocket server on this port; implies headless mode |
| `.headless(boolean)` | `false` | Run without an AWT window (stdout mode) |
| `.noSound(boolean)` | `false` | Disable all audio (SFX + music) |
| `.noMusic(boolean)` | `false` | Disable music only; SFX still play |
| `.extraArgs(String... args)` | — | Raw engine flags not yet exposed by the builder |

### `MochaDoom` methods

| Method | Description |
|--------|-------------|
| `start()` | Initialise engine synchronously, then run game loop in a daemon thread |
| `pause()` | Halt game ticks, sound, and rendering (thread blocks until `resume()`) |
| `resume()` | Unblock the game loop after a `pause()` |
| `stop()` | Interrupt the game loop thread (best-effort shutdown) |
| `isRunning()` | `true` if game thread is alive |
| `isPaused()` | `true` if currently paused via `pause()` |
| `getGameThread()` | The background thread — call `join()` to wait for exit |

---

## WebSocket streaming mode

In this mode the game runs headless on a server. Video frames (JPEG) and audio
chunks (PCM) are sent as tagged binary WebSocket messages to all connected
browsers. No sound plays on the server.

### Quick start with the bundled Node.js relay

The `server/` directory contains a ready-to-use relay that sits between the
Java WebSocket server and browser clients, serving `index.html` over HTTP.

```bash
# Install dependencies (once)
cd server && npm install

# Spawn Java + start relay (recommended)
node server.js --spawn

# Or connect to an already-running Java process
node server.js
# then in a separate terminal:
java -jar target/mochadoom-1.0.0-SNAPSHOT.jar -websocket 3001
```

Open `http://localhost:8080` in a browser. Video and audio start immediately
after the first keypress (required by browser autoplay policy).

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP + browser WebSocket port |
| `GAME_WS_PORT` | `3001` | Port where Java's WebSocket server listens |

### WebSocket binary protocol

All binary frames from the game carry a 1-byte type prefix:

| Byte 0 | Payload | Description |
|--------|---------|-------------|
| `0x01` | JPEG bytes | Video frame |
| `0x02` | Raw PCM bytes | Audio chunk |

#### Video frame (`0x01`)

The payload is a JPEG-encoded screenshot. The browser decodes it with
`createImageBitmap` and draws it onto a `<canvas>`.

#### Audio chunk (`0x02`)

The payload is raw PCM:

```
Format:   signed 16-bit, big-endian, stereo interleaved
Rate:     22050 Hz
Channels: 2 (left, right)
Chunk:    ~1050 sample frames = ~4200 bytes = ~47.6 ms of audio
```

The browser decodes it with the Web Audio API:

```js
const view  = new DataView(msg, 1);           // skip type byte
const count = Math.floor((msg.byteLength - 1) / 4);
const buf   = audioCtx.createBuffer(2, count, 22050);
const L = buf.getChannelData(0);
const R = buf.getChannelData(1);
for (let i = 0; i < count; i++) {
    L[i] = view.getInt16(i * 4,     false) / 32768; // big-endian
    R[i] = view.getInt16(i * 4 + 2, false) / 32768;
}
const src = audioCtx.createBufferSource();
src.buffer = buf;
src.connect(audioCtx.destination);
src.start(nextAudioTime);
nextAudioTime += buf.duration;
```

### Implementing your own browser client

Minimum viable browser client for WebSocket mode:

```html
<canvas id="screen"></canvas>
<script>
const canvas = document.getElementById('screen');
const ctx    = canvas.getContext('2d');

const audioCtx = new AudioContext({ sampleRate: 22050 });
let nextAudioTime = 0;
const LOOKAHEAD = 0.15; // seconds

// Unlock AudioContext on first user gesture (browser autoplay policy)
window.addEventListener('keydown', () => audioCtx.resume(), { once: true });

const ws = new WebSocket('ws://localhost:8080');
ws.binaryType = 'arraybuffer';

ws.onmessage = async ({ data }) => {
    const type = new Uint8Array(data, 0, 1)[0];

    if (type === 0x01) {
        // Video: JPEG payload starting at byte 1
        const bitmap = await createImageBitmap(
            new Blob([new Uint8Array(data, 1)], { type: 'image/jpeg' })
        );
        canvas.width  = bitmap.width;
        canvas.height = bitmap.height;
        ctx.drawImage(bitmap, 0, 0);
    } else if (type === 0x02) {
        // Audio: raw PCM starting at byte 1
        const view  = new DataView(data, 1);
        const count = Math.floor((data.byteLength - 1) / 4);
        const buf   = audioCtx.createBuffer(2, count, 22050);
        const L = buf.getChannelData(0);
        const R = buf.getChannelData(1);
        for (let i = 0; i < count; i++) {
            L[i] = view.getInt16(i * 4,     false) / 32768;
            R[i] = view.getInt16(i * 4 + 2, false) / 32768;
        }
        const src = audioCtx.createBufferSource();
        src.buffer = buf;
        src.connect(audioCtx.destination);
        const now = audioCtx.currentTime;
        if (nextAudioTime < now + 0.01) nextAudioTime = now + LOOKAHEAD;
        src.start(nextAudioTime);
        nextAudioTime += buf.duration;
    }
};

// Send keyboard events to the game
function sendKey(t, k) { ws.send(JSON.stringify({ t, k })); }
window.addEventListener('keydown', e => { if (!e.repeat) sendKey('d', e.code); });
window.addEventListener('keyup',   e => sendKey('u', e.code));
</script>
```

---

## Stdout streaming mode

In this mode the game outputs video and audio as a multiplexed binary stream on
stdout. Useful for piping into video encoders or custom relay servers.

### Running

```bash
java -jar target/mochadoom-1.0.0-SNAPSHOT.jar -stdout -iwad doom.wad
```

stdout is redirected to stderr internally so Java log output does not
contaminate the stream.

### Video packets (`DOOM`)

```
Offset   Size    Description
──────   ─────   ──────────────────────────────────────
0        4       Magic: 0x44 0x4F 0x4F 0x4D  ("DOOM")
4        4       Frame number  (little-endian uint32)
8        4       Width  in px  (little-endian uint32, typically 320)
12       4       Height in px  (little-endian uint32, typically 200)
16       W×H×4   Pixels: R G B A per pixel, row-major, top-down
```

### Audio packets (`DOOA`)

```
Offset   Size    Description
──────   ─────   ──────────────────────────────────────
0        4       Magic: 0x44 0x4F 0x4F 0x41  ("DOOA")
4        4       Chunk number   (little-endian uint32)
8        4       Sample rate    (little-endian uint32) — 22050
12       4       Channel count  (little-endian uint32) — 2
16       4       Bits per sample (little-endian uint32) — 16
20       4       Byte count N   (little-endian uint32)
24       N       PCM: signed 16-bit big-endian stereo interleaved
```

Video and audio packets are interleaved on stdout. A consumer must parse by
magic prefix and handle both packet types. Packets are written atomically (no
interleaving mid-packet).

### Minimal Node.js stdout relay

```js
'use strict';
const { WebSocketServer } = require('ws');
const wss = new WebSocketServer({ port: 8080 });
const clients = new Set();
wss.on('connection', ws => {
    clients.add(ws);
    ws.on('close', () => clients.delete(ws));
});

const DOOM_MAGIC = 0x444F4F4D; // "DOOM"
const DOOA_MAGIC = 0x444F4F41; // "DOOA"
let buf = Buffer.alloc(0);

process.stdin.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 8) {
        const magic = buf.readUInt32BE(0);
        if (magic === DOOM_MAGIC) {
            // Video packet: 16-byte header + W*H*4 pixels
            if (buf.length < 16) break;
            const w = buf.readUInt32LE(8), h = buf.readUInt32LE(12);
            const total = 16 + w * h * 4;
            if (buf.length < total) break;
            const frame = buf.slice(0, total);
            clients.forEach(ws => ws.readyState === 1 && ws.send(frame));
            buf = buf.slice(total);
        } else if (magic === DOOA_MAGIC) {
            // Audio packet: 24-byte header + N bytes PCM
            if (buf.length < 24) break;
            const n = buf.readUInt32LE(20);
            const total = 24 + n;
            if (buf.length < total) break;
            const chunk = buf.slice(0, total);
            clients.forEach(ws => ws.readyState === 1 && ws.send(chunk));
            buf = buf.slice(total);
        } else {
            buf = buf.slice(1); // re-sync
        }
    }
});
```

---

## Command-line flags reference

Flags can also be passed via `DoomConfig.builder().extraArgs(...)`.

| Flag | Description |
|------|-------------|
| `-iwad <path>` | IWAD file to load |
| `-websocket <port>` | Enable WebSocket server on given port |
| `-stdout` | Enable raw stdout streaming |
| `-nosound` | Disable all audio |
| `-nomusic` | Disable music; SFX still play |
| `-nosfx` | Disable SFX; music still plays |
| `-warp <e> <m>` | Warp to episode/map on start |
| `-skill <1-5>` | Set difficulty |
| `-timedemo <demo>` | Play back a demo (no rate limiting) |

---

## License

Mocha Doom is distributed under the [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html).

Original authors:
- id Software, Inc. (1993–1996)
- Victor Epitropou / velktron (2010–2013)
- Alexandre-Xavier Labonté-Lamoureux (2016–2017)
- Good Sign (2017)
