/**
 * doom-stream server
 *
 * Reads the binary frame stream produced by StdoutFrameWriter from stdin
 * (pipe mochadoom's stdout here), or spawns the game itself with --spawn.
 * Converts each raw RGBA frame to JPEG and broadcasts it to all connected
 * browser clients over WebSocket.
 *
 * Wire protocol (little-endian):
 *   [4]  magic  = 0x44 0x4F 0x4F 0x4D  ("DOOM")
 *   [4]  frame number (int32 LE)
 *   [4]  width        (int32 LE)
 *   [4]  height       (int32 LE)
 *   [W*H*4]  RGBA pixel data, row-major top-down
 *
 * Usage (pipe):
 *   java -jar src/mochadoom.jar -stdout 2>nul | node server/server.js
 *
 * Usage (spawn — recommended, Java logs visible in terminal):
 *   node server/server.js --spawn
 */

'use strict';

const http        = require('http');
const path        = require('path');
const fs          = require('fs');
const { WebSocketServer } = require('ws');
const { Jimp }    = require('jimp');
const { spawn }   = require('child_process');

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------
const PORT        = process.env.PORT || 8080;
const JPEG_QUALITY = 80;   // 0-100
const SPAWN_GAME  = process.argv.includes('--spawn');
const GAME_DIR    = path.resolve(__dirname, '..');
const GAME_CMD    = 'java';
const GAME_ARGS   = ['-jar', 'src/mochadoom.jar', '-stdout'];

// ---------------------------------------------------------------------------
// HTTP server — serves index.html + assets
// ---------------------------------------------------------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript',
  '.css':  'text/css',
  '.ico':  'image/x-icon',
};

const httpServer = http.createServer((req, res) => {
  // Only serve files from the server/ directory
  let filePath = req.url === '/' ? '/index.html' : req.url;
  filePath = path.join(__dirname, filePath);

  const ext = path.extname(filePath);
  const mime = MIME[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': mime });
    res.end(data);
  });
});

// ---------------------------------------------------------------------------
// WebSocket server
// ---------------------------------------------------------------------------
const wss = new WebSocketServer({ server: httpServer });

let clientCount = 0;

wss.on('connection', (ws) => {
  clientCount++;
  console.log(`[ws] client connected  (total: ${clientCount})`);

  // Send the last known frame immediately so the client isn't blank
  if (lastJpegFrame) {
    ws.send(lastJpegFrame);
  }

  ws.on('close', () => {
    clientCount--;
    console.log(`[ws] client disconnected (total: ${clientCount})`);
  });

  ws.on('error', () => {}); // swallow individual client errors
});

/** Broadcast a Buffer to all connected clients. */
function broadcast(buf) {
  wss.clients.forEach((ws) => {
    if (ws.readyState === ws.OPEN) {
      ws.send(buf, { binary: true });
    }
  });
}

// ---------------------------------------------------------------------------
// Frame parser
// ---------------------------------------------------------------------------
const MAGIC = Buffer.from([0x44, 0x4f, 0x4f, 0x4d]); // "DOOM"
const HEADER_SIZE = 16; // 4 magic + 4 frame# + 4 width + 4 height

let recvBuf     = Buffer.alloc(0);
let lastJpegFrame = null;
let frameCount  = 0;

/**
 * Feed raw bytes from the game process; parse complete frames and encode them.
 */
async function feedData(chunk) {
  recvBuf = Buffer.concat([recvBuf, chunk]);

  while (true) {
    // Need at least a full header
    if (recvBuf.length < HEADER_SIZE) break;

    // Verify magic
    if (!recvBuf.slice(0, 4).equals(MAGIC)) {
      // Scan forward for the next magic
      const idx = recvBuf.indexOf(MAGIC, 1);
      if (idx === -1) {
        recvBuf = Buffer.alloc(0);
        break;
      }
      console.warn(`[parser] resync: skipped ${idx} bytes`);
      recvBuf = recvBuf.slice(idx);
      continue;
    }

    const frameNum = recvBuf.readInt32LE(4);
    const w        = recvBuf.readInt32LE(8);
    const h        = recvBuf.readInt32LE(12);
    const pixelLen = w * h * 4;
    const totalLen = HEADER_SIZE + pixelLen;

    // Wait for the full payload
    if (recvBuf.length < totalLen) break;

    // Extract RGBA pixels
    const rgba = recvBuf.slice(HEADER_SIZE, totalLen);
    recvBuf = recvBuf.slice(totalLen);

    frameCount++;
    if (frameCount % 30 === 0) {
      console.log(`[parser] frame ${frameNum}  ${w}x${h}  total=${frameCount}`);
    }

    // Encode to JPEG asynchronously so we don't block parsing
    encodeAndBroadcast(rgba, w, h).catch((e) => console.error('[encode]', e));
  }
}

async function encodeAndBroadcast(rgbaBuffer, w, h) {
  // Jimp accepts raw RGBA buffer directly
  const image = new Jimp({ width: w, height: h, data: rgbaBuffer });
  const jpegBuf = await image.getBuffer('image/jpeg', { quality: JPEG_QUALITY });

  lastJpegFrame = jpegBuf;
  broadcast(jpegBuf);
}

// ---------------------------------------------------------------------------
// Attach the data source (stdin or spawned process)
// ---------------------------------------------------------------------------
let dataSource;

if (SPAWN_GAME) {
  console.log(`[spawn] cd ${GAME_DIR}`);
  console.log(`[spawn] ${GAME_CMD} ${GAME_ARGS.join(' ')}`);

  dataSource = spawn(GAME_CMD, GAME_ARGS, {
    cwd: GAME_DIR,
    // stdout → we parse binary frames
    // stderr → inherit so Java log messages appear in the terminal
    // stdin  → ignore (headless, no input)
    stdio: ['ignore', 'pipe', 'inherit'],
  });

  dataSource.stdout.on('data', (chunk) => feedData(chunk));

  dataSource.on('close', (code) => {
    console.log(`[spawn] game process exited with code ${code}`);
  });

  dataSource.on('error', (err) => {
    console.error('[spawn] failed to start game:', err.message);
  });
} else {
  // Read frames from stdin (pipe: java ... -stdout 2>nul | node server/server.js)
  process.stdin.on('data', (chunk) => feedData(chunk));
  process.stdin.on('end', () => {
    console.log('[stdin] EOF — game stream ended');
  });
  // Prevent Node from exiting when stdin ends if we still have clients
  process.stdin.resume();
}

// ---------------------------------------------------------------------------
// Start listening
// ---------------------------------------------------------------------------
httpServer.listen(PORT, () => {
  console.log(`\n🎮  DOOM stream server running at http://localhost:${PORT}\n`);
  if (SPAWN_GAME) {
    console.log('Game process will start now. Open the URL above in your browser.\n');
  } else {
    console.log('Pipe the game here (Java logs go to stderr, not stdout):');
    console.log(`  java -jar src/mochadoom.jar -stdout 2>nul | node server/server.js\n`);
    console.log('Or use --spawn to let the server manage the process:');
    console.log(`  node server/server.js --spawn\n`);
  }
});
