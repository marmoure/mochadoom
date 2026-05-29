# MochaDoom — Library Integration Guide

This document is for Java developers who want to embed MochaDoom in another
project. It covers dependency setup, the full public API, the three output
modes, and the binary wire protocols for receiving video and audio.

---

## 1. Adding the dependency

MochaDoom is not yet published to Maven Central. Install it to your local
repository first:

```bash
git clone <this-repo>
cd mochadoom
mvn install -DskipTests
```

Then declare it in your project.

**Maven**
```xml
<dependency>
    <groupId>io.github.mochadoom</groupId>
    <artifactId>mochadoom</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.mochadoom:mochadoom:1.0.0-SNAPSHOT")
```

**Gradle (Groovy DSL)**
```groovy
implementation 'io.github.mochadoom:mochadoom:1.0.0-SNAPSHOT'
```

Minimum Java version: **11**

---

## 2. Public API

Only two classes form the public API, both in the `mochadoom` package:

| Class | Role |
|-------|------|
| `DoomConfig` | Immutable configuration, built via a fluent builder |
| `MochaDoom` | Engine lifecycle — start, pause, resume, stop |

Everything else (`doom.*`, `s.*`, `i.*`, …) is internal and subject to change
between versions.

---

## 3. Choosing a mode

| Mode | Video out | Audio out | Input |
|------|-----------|-----------|-------|
| **Desktop** | AWT window | System speakers | Window keyboard/mouse |
| **WebSocket** | Browser `<canvas>` via JPEG | Browser Web Audio API | Browser keyboard via WebSocket |
| **Stdout** | Raw RGBA frames on stdout | Raw PCM on stdout | stdin JSON key events |

Mode is determined by what you configure in `DoomConfig`. In both WebSocket and
stdout modes the server never opens a hardware audio device — all audio is
delivered to the downstream consumer only.

---

## 4. Desktop mode

The game opens an AWT window, renders to it, and plays audio through the
system speakers. Input is captured from the window.

```java
import mochadoom.DoomConfig;
import mochadoom.MochaDoom;

DoomConfig config = DoomConfig.builder()
    .iwad("/path/to/doom.wad")
    .build();

MochaDoom doom = new MochaDoom(config);
doom.start();                   // non-blocking — game loop runs in background
doom.getGameThread().join();    // wait until the player exits the game
```

This mode has no streaming output; the library is self-contained.

---

## 5. WebSocket mode

The game runs headless. Each game tick the engine encodes the screen as JPEG
and sends it as a binary WebSocket frame, then sends a mixed PCM audio chunk as
another binary WebSocket frame. Both are sent to every connected client. No
audio plays on the server.

```java
DoomConfig config = DoomConfig.builder()
    .iwad("/path/to/doom.wad")
    .webSocketPort(3001)    // Java opens a WebSocket server on this port
    .build();

MochaDoom doom = new MochaDoom(config);
doom.start();
```

### Binary message protocol

Every binary frame begins with a 1-byte type tag:

| Byte 0 | Remaining bytes | Meaning |
|--------|-----------------|---------|
| `0x01` | JPEG image | Video frame |
| `0x02` | Raw PCM audio | Audio chunk |

#### `0x01` Video frame

Bytes 1 … N are a JPEG-encoded screenshot. Decode with any JPEG library or
with the browser's `createImageBitmap`.

#### `0x02` Audio chunk

Bytes 1 … N are raw PCM in this fixed format:

```
Encoding:    signed 16-bit, big-endian
Channels:    2 (stereo, interleaved L R L R …)
Sample rate: 22 050 Hz
Chunk size:  ~4 200 bytes  (~1 050 stereo sample frames ≈ 47.6 ms)
```

Audio chunks arrive at ~21 per second, independent of the video frame rate.

### Receiving messages in Java

```java
// Example using the Java-WebSocket library
WebSocketClient client = new WebSocketClient(new URI("ws://localhost:3001")) {
    @Override
    public void onMessage(ByteBuffer data) {
        byte type = data.get(0);
        byte[] payload = new byte[data.remaining()];
        data.slice().get(payload);

        if (type == 0x01) {
            handleVideoFrame(payload);        // JPEG bytes
        } else if (type == 0x02) {
            handleAudioChunk(payload);        // signed 16-bit big-endian stereo 22 050 Hz
        }
    }
};
```

### Receiving messages in the browser

```js
const ws = new WebSocket('ws://localhost:3001');
ws.binaryType = 'arraybuffer';

// AudioContext must be resumed after a user gesture (browser autoplay policy)
const audioCtx = new AudioContext({ sampleRate: 22050 });
window.addEventListener('keydown', () => audioCtx.resume(), { once: true });

let nextAudioTime = 0;
const LOOKAHEAD = 0.15; // seconds to buffer ahead — prevents gaps

ws.onmessage = async ({ data }) => {
    const type = new Uint8Array(data, 0, 1)[0];

    if (type === 0x01) {
        // Video: decode JPEG and draw to canvas
        const bitmap = await createImageBitmap(
            new Blob([new Uint8Array(data, 1)], { type: 'image/jpeg' })
        );
        canvas.width  = bitmap.width;
        canvas.height = bitmap.height;
        ctx.drawImage(bitmap, 0, 0);

    } else if (type === 0x02) {
        // Audio: decode PCM and schedule playback
        const view  = new DataView(data, 1);                    // skip type byte
        const count = Math.floor((data.byteLength - 1) / 4);   // 4 bytes per stereo frame
        const buf   = audioCtx.createBuffer(2, count, 22050);
        const L = buf.getChannelData(0);
        const R = buf.getChannelData(1);
        for (let i = 0; i < count; i++) {
            L[i] = view.getInt16(i * 4,     false) / 32768;    // false = big-endian
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

// Forward keyboard input to the game
function sendKey(t, k) { ws.send(JSON.stringify({ t, k })); }
window.addEventListener('keydown', e => { if (!e.repeat) sendKey('d', e.code); });
window.addEventListener('keyup',   e => sendKey('u', e.code));
```

### Using the bundled Node.js relay

For browser deployments the simplest topology is:

```
Browser  ←── WebSocket ──→  Node.js relay  ←── WebSocket ──→  Java (MochaDoom)
         (HTTP + WS :8080)                       (WS :3001)
```

The `server/` directory contains a ready-made relay (`server.js`) and browser
client (`index.html`) that implement the full protocol with audio scheduling,
keyboard forwarding, auto-reconnect, and an initial video-frame cache for
clients that connect mid-stream.

```bash
# Install relay dependencies (once)
cd server && npm install

# Spawn Java automatically and start relay
node server.js --spawn
# → open http://localhost:8080 in a browser

# Or connect to an already-running Java process
node server.js
```

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP + browser WebSocket port |
| `GAME_WS_PORT` | `3001` | Port where Java's WebSocket server listens |

---

## 6. Stdout mode

The game runs headless and multiplexes video frames and audio chunks into a
single binary stream on stdout, each prefixed with a 4-byte magic word. No
audio plays on the server.

```java
DoomConfig config = DoomConfig.builder()
    .iwad("/path/to/doom.wad")
    .headless(true)
    .build();

MochaDoom doom = new MochaDoom(config);
doom.start();
doom.getGameThread().join();
```

Or from the command line:

```bash
java -jar mochadoom-1.0.0-SNAPSHOT.jar -stdout -iwad doom.wad | your-consumer
```

### `DOOM` — video packet

```
Offset   Bytes    Field
──────   ──────   ──────────────────────────────────────────────
0        4        Magic: 0x44 0x4F 0x4F 0x4D  ("DOOM")
4        4        Frame number  (little-endian uint32, starts at 0)
8        4        Width  in px  (little-endian uint32, typically 320)
12       4        Height in px  (little-endian uint32, typically 200)
16       W×H×4    Pixels: R G B A per pixel, row-major, top-down
```

Total for 320×200: 16 + 256 000 = **256 016 bytes**

### `DOOA` — audio packet

```
Offset   Bytes    Field
──────   ──────   ──────────────────────────────────────────────
0        4        Magic: 0x44 0x4F 0x4F 0x41  ("DOOA")
4        4        Chunk number    (little-endian uint32, starts at 0)
8        4        Sample rate     (little-endian uint32) — 22 050
12       4        Channel count   (little-endian uint32) — 2
16       4        Bits per sample (little-endian uint32) — 16
20       4        Byte count N    (little-endian uint32)
24       N        PCM: signed 16-bit big-endian stereo interleaved
```

Typical N: **4 200 bytes** (~1 050 stereo sample frames ≈ 47.6 ms)

Video and audio packets are interleaved on stdout. Each packet is written
atomically — no partial writes or interleaving mid-packet.

### Parsing in Java

```java
DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));

while (true) {
    int magic = in.readInt(); // reads 4 bytes big-endian

    if (magic == 0x444F4F4D) {           // "DOOM" — video
        int frameNo = Integer.reverseBytes(in.readInt());
        int w       = Integer.reverseBytes(in.readInt());
        int h       = Integer.reverseBytes(in.readInt());
        byte[] rgba = in.readNBytes(w * h * 4);
        handleVideoFrame(frameNo, w, h, rgba);

    } else if (magic == 0x444F4F41) {    // "DOOA" — audio
        int chunkNo    = Integer.reverseBytes(in.readInt());
        int sampleRate = Integer.reverseBytes(in.readInt());
        int channels   = Integer.reverseBytes(in.readInt());
        int bits       = Integer.reverseBytes(in.readInt());
        int byteCount  = Integer.reverseBytes(in.readInt());
        byte[] pcm     = in.readNBytes(byteCount);
        handleAudioChunk(chunkNo, pcm, sampleRate, channels, bits);
    }
    // unknown magic: stream corrupted or out of sync — handle as appropriate
}
```

### Parsing in Node.js

```js
const DOOM_MAGIC = 0x444F4F4D;
const DOOA_MAGIC = 0x444F4F41;
let buf = Buffer.alloc(0);

process.stdin.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 8) {
        const magic = buf.readUInt32BE(0);
        if (magic === DOOM_MAGIC) {
            if (buf.length < 16) break;
            const w = buf.readUInt32LE(8), h = buf.readUInt32LE(12);
            const size = 16 + w * h * 4;
            if (buf.length < size) break;
            handleVideoFrame(buf.slice(0, size));
            buf = buf.slice(size);
        } else if (magic === DOOA_MAGIC) {
            if (buf.length < 24) break;
            const n = buf.readUInt32LE(20);
            const size = 24 + n;
            if (buf.length < size) break;
            handleAudioChunk(buf.slice(24, size)); // raw PCM bytes
            buf = buf.slice(size);
        } else {
            buf = buf.slice(1); // re-sync on unknown magic
        }
    }
});
```

---

## 7. `DoomConfig` reference

| Builder method | Type | Default | Description |
|----------------|------|---------|-------------|
| `.iwad(path)` | `String` | auto-discover | Path to the IWAD (`.wad`) file |
| `.webSocketPort(port)` | `int` | disabled | Open WebSocket server on this port; implies headless |
| `.headless(bool)` | `boolean` | `false` | Stdout mode — no AWT window, no WebSocket server |
| `.noSound(bool)` | `boolean` | `false` | Disable all audio (SFX + music) in the output stream |
| `.noMusic(bool)` | `boolean` | `false` | Disable music only; SFX still delivered |
| `.extraArgs(args…)` | `String…` | — | Raw engine flags (see table below) |

Common extra args:

| Flag | Example | Description |
|------|---------|-------------|
| `-skill N` | `-skill 4` | Difficulty 1 (easiest) to 5 (nightmare) |
| `-warp E M` | `-warp 1 8` | Warp to episode E, map M on load |
| `-timedemo name` | `-timedemo demo1` | Play demo at maximum speed |
| `-fastdemo name` | `-fastdemo demo1` | Play demo at uncapped speed |
| `-nosfx` | | Disable sound effects; music still delivered |

---

## 8. `MochaDoom` lifecycle reference

```java
MochaDoom doom = new MochaDoom(config);

// Initialise engine synchronously (loads WAD, opens ports).
// Then starts the game loop in a background daemon thread.
// Throws IOException if WAD is missing or the port is already bound.
doom.start();

// Pause: game-loop thread blocks. No ticks, no video, no audio output.
doom.pause();

// Resume: unblocks the game-loop thread.
doom.resume();

doom.isRunning();         // true while the game-loop thread is alive
doom.isPaused();          // true while paused via pause()

doom.getGameThread();     // the daemon thread — call .join() to wait for exit
doom.stop();              // interrupt the game-loop thread (best-effort)
```

**Constraints to be aware of:**

- **JVM singleton.** Only one `MochaDoom` instance may call `start()` per JVM.
  A second call on any instance throws `IllegalStateException`. To restart,
  spawn a fresh JVM process.
- **Thread safety.** `pause()`, `resume()`, `stop()`, `isRunning()`, and
  `isPaused()` are safe to call from any thread. `start()` is synchronized.
- **Daemon thread.** The game-loop thread will not prevent JVM exit on its own.
- **Synchronous init.** WAD loading, sound driver setup, and port binding all
  happen inside `start()` before it returns, so `IOException` surfaces
  immediately rather than silently inside the background thread.

---

## 9. Audio format quick reference

| Property | Value |
|----------|-------|
| Encoding | Signed PCM |
| Bit depth | 16-bit |
| Byte order | Big-endian |
| Channels | 2 (stereo) |
| Layout | Interleaved (L₀ R₀ L₁ R₁ …) |
| Sample rate | 22 050 Hz |
| Bytes per stereo sample frame | 4 |
| Typical chunk size | ~4 200 bytes |
| Typical chunk duration | ~47.6 ms |

Equivalent `javax.sound.sampled.AudioFormat`:

```java
new AudioFormat(
    22050,  // sample rate
    16,     // bits per sample
    2,      // channels
    true,   // signed
    true    // big-endian
);
```
