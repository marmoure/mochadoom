# Mocha Doom

![Top Language](https://img.shields.io/github/languages/top/axdoomer/mochadoom.svg?style=flat)
![Code Size](https://img.shields.io/github/languages/code-size/axdoomer/mochadoom.svg?style=flat)
![License](https://img.shields.io/github/license/axdoomer/mochadoom.svg?style=flat&logo=gnu)

Mocha Doom is a pure Java Doom source port. Most of the hard work of porting Doom to Java has already been done, thanks to Velktron (Maes), but he has stopped working on it in 2013. Although the port is almost complete, some work remains to do, most importantly the network code for the multiplayer is missing. Features like support for the Boom format would also be great. I have decided to continue the development in my free time and fix some bugs.

# How to run

1. Open the project with Eclipse or NetBeans
2. Delete every file that has errors (if any)
3. Build and run the project

## Advanced users

On Linux, two different scripts can be used.

1. `build-and-run.sh` which will build Mocha Doom and run it. You can use it as such: `./build-and-run.sh -iwad ~/DOOM2.WAD`. This is the preferred way to quickly test changes for developers.
2. `build-jar.sh` which will build a JAR file. You can then run the JAR file as such: `java -jar mochadoom.jar -iwad ~/DOOM2.WAD`. This is the preferred way for distributing a Mocha Doom executable.

# License

Mocha Doom contains work from many contributors. Here are the main contributors, but it's no limited to this list. Others are listed in the copyright headers of the files where they own copyright.

- Copyright (C) 1993-1996  [id Software, Inc.](http://www.idsoftware.com/)
- Copyright (C) 2010-2013  [Victor Epitropou](https://sourceforge.net/projects/mochadoom/)
- Copyright (C) 2016-2017  [Alexandre-Xavier Labonté-Lamoureux](https://github.com/AXDOOMER/)
- Copyright (C) 2017  [Good Sign](https://github.com/GoodSign2017)

Mocha Doom is distributed under the [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html).

# Rip and Tear!

Mocha Doom in action:
![so_much_blood](https://cloud.githubusercontent.com/assets/6194072/18658610/94a326c2-7ed2-11e6-98af-4ed4c8b28510.png)

---

# Headless stdout Rendering Mode

Mocha Doom can run without a window and stream every rendered frame as raw
RGBA bytes to **stdout**. This makes it easy to pipe the output to a browser
canvas, a video encoder, or any other consumer.

## Prerequisites

- Java 8 or newer on your `PATH`
- A valid IWAD file (e.g. `doom1.wad`)
- The project compiled into `classes/` (see *Advanced users* above)

---

## Step 1 — Compile

```bash
# Linux / macOS
./build-and-run.sh   # or build-jar.sh / your IDE

# Windows (PowerShell) — compile changed sources
javac -sourcepath src -d classes `
  src/mochadoom/Engine.java `
  src/i/StdoutFrameWriter.java `
  src/awt/HeadlessController.java `
  src/doom/CommandVariable.java
```

---

## Step 2 — Run in headless mode

Add the `-stdout` flag. Because there is no window, you also need a source of
input — the built-in demo files work perfectly:

```bash
# Linux / macOS — stream frames into a file
java -cp classes mochadoom.Engine -stdout -timedemo demo1 > frames.bin

# Windows (PowerShell)
java -cp classes mochadoom.Engine -stdout -timedemo demo1 | Set-Content -Encoding Byte frames.bin
```

Other useful flags that work alongside `-stdout`:

| Flag | Effect |
|------|--------|
| `-warp 1 1` | Jump straight into E1M1 |
| `-timedemo demo1` | Play back the built-in demo as fast as possible |
| `-fastdemo demo1` | Play back demo at full speed (no rate limiting) |
| `-skill 4` | Set difficulty (1–5) |
| `-nosound` | Disable audio (saves CPU in headless mode) |
| `-indexed` | Use 8-bit indexed renderer (fastest) |

---

## Step 3 — Wire format

Each frame is written as a self-framed binary packet:

```
Offset  Size   Description
──────  ─────  ──────────────────────────────────────────────────
0       4      Magic: ASCII "DOOM" (0x44 0x4F 0x4F 0x4D)
4       4      Frame number (little-endian int32, starts at 0)
8       4      Width  in pixels (little-endian int32, typically 320)
12      4      Height in pixels (little-endian int32, typically 200)
16      W×H×4  Pixel data: R G B A per pixel, row-major, top-down
```

Total packet size for the default 320×200 resolution:  
`16 + 320 × 200 × 4 = 256,016 bytes`

---

## Step 4 — Render on a browser `<canvas>`

Save the snippet below as **`player.html`** and open it in any modern browser.
It connects to the Node.js relay from Step 5 over a WebSocket.

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Doom Canvas Player</title>
  <style>
    body { background: #000; display: flex; justify-content: center;
           align-items: center; height: 100vh; margin: 0; }
    canvas { image-rendering: pixelated; width: 640px; height: 400px; }
  </style>
</head>
<body>
  <canvas id="screen" width="320" height="200"></canvas>
  <script>
    const canvas = document.getElementById('screen');
    const ctx    = canvas.getContext('2d');
    const ws     = new WebSocket('ws://localhost:8080');
    ws.binaryType = 'arraybuffer';

    ws.onmessage = ({ data }) => {
      const view   = new DataView(data);
      const w      = view.getInt32(8,  true); // little-endian
      const h      = view.getInt32(12, true);
      const pixels = new Uint8ClampedArray(data, 16, w * h * 4);
      ctx.putImageData(new ImageData(pixels, w, h), 0, 0);
    };
  </script>
</body>
</html>
```

---

## Step 5 — Node.js WebSocket relay

This relay reads the binary stream from stdin and forwards each complete frame
packet to every connected browser.

**Install once:**
```bash
npm install ws
```

**`relay.js`:**
```js
const { WebSocketServer } = require('ws');
const wss = new WebSocketServer({ port: 8080 });

const HEADER = 16;          // magic(4) + frameNo(4) + w(4) + h(4)
const MAGIC  = 0x444F4F4D;  // "DOOM"

let clients = new Set();
wss.on('connection', ws => {
  clients.add(ws);
  ws.on('close', () => clients.delete(ws));
  console.log('Browser connected');
});

let buf = Buffer.alloc(0);

process.stdin.on('data', chunk => {
  buf = Buffer.concat([buf, chunk]);

  while (buf.length >= HEADER) {
    // Re-sync if magic is missing
    if (buf.readUInt32BE(0) !== MAGIC) { buf = buf.slice(1); continue; }

    const w     = buf.readInt32LE(8);
    const h     = buf.readInt32LE(12);
    const total = HEADER + w * h * 4;
    if (buf.length < total) break;            // wait for full frame

    const frame = buf.slice(0, total);
    clients.forEach(ws => ws.readyState === 1 && ws.send(frame));
    buf = buf.slice(total);
  }
});

console.log('Relay listening on ws://localhost:8080 — open player.html');
```

**Run everything together:**
```bash
# One command: game → relay; then open player.html in your browser
java -cp classes mochadoom.Engine -stdout -nosound -timedemo demo1 | node relay.js
```

---

## Quick sanity check

Confirm the stream is correct by dumping the first 16 bytes of the header:

```bash
# Linux / macOS
java -cp classes mochadoom.Engine -stdout -nosound -timedemo demo1 \
  | head -c 16 | xxd

# Expected output:
# 00000000: 444f 4f4d 0000 0000 4001 0000 c800 0000  DOOM....@.......
#            ^magic^  ^frame0^  ^320 LE^  ^200 LE^
```

