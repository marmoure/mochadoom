/**
 * doom-stream server
 *
 * Relays between the Java game (WebSocket server) and browser clients.
 * Java encodes frames as JPEG and sends them over WebSocket; this server
 * forwards them to all connected browser clients and routes key events back.
 *
 * Java game runs with:  -websocket <GAME_WS_PORT>  (default 3001)
 *
 * Usage (spawn — recommended):
 *   node server/server.js --spawn
 *
 * Usage (connect to already-running game):
 *   node server/server.js
 */

'use strict';

const http = require('http');
const path = require('path');
const fs = require('fs');
const { WebSocketServer, WebSocket } = require('ws');
const { spawn } = require('child_process');

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------
const PORT = process.env.PORT || 8080;
const GAME_WS_PORT = process.env.GAME_WS_PORT || 3001;
const SPAWN_GAME = process.argv.includes('--spawn');
const GAME_DIR = path.resolve(__dirname, '..');
const GAME_CMD = 'java';
const GAME_ARGS = ['-jar', 'target/mochadoom-1.0.0-SNAPSHOT.jar', '-websocket', String(GAME_WS_PORT), '-fps', '60'];

// ---------------------------------------------------------------------------
// HTTP server — serves index.html + assets
// ---------------------------------------------------------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.ico': 'image/x-icon',
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
let lastVideoFrame = null; // most recent 0x01-prefixed video frame, for new-client catch-up
let gameWs = null;

wss.on('connection', (ws) => {
  clientCount++;
  console.log(`[ws] client connected  (total: ${clientCount})`);

  // Send the last known video frame immediately so the client isn't blank.
  // Audio is not cached — new clients pick up from the next chunk naturally.
  if (lastVideoFrame) ws.send(lastVideoFrame, { binary: true });

  ws.on('message', (data) => {
    // Forward browser key events to the Java game
    if (!gameWs || gameWs.readyState !== WebSocket.OPEN) return;
    try {
      const { t, k } = JSON.parse(data);
      if ((t === 'd' || t === 'u') && typeof k === 'string') {
        gameWs.send(data.toString());
      }
    } catch (_) { /* ignore malformed messages */ }
  });

  ws.on('close', () => {
    clientCount--;
    console.log(`[ws] client disconnected (total: ${clientCount})`);
  });

  ws.on('error', () => { });
});

/** Broadcast a Buffer to all connected browser clients. */
function broadcast(buf) {
  wss.clients.forEach((ws) => {
    if (ws.readyState === ws.OPEN) ws.send(buf, { binary: true });
  });
}

// ---------------------------------------------------------------------------
// WebSocket connection to the Java game
// ---------------------------------------------------------------------------
let frameCount = 0;

// Binary message type bytes (must match Java GameWebSocketServer)
const TYPE_VIDEO = 0x01;
const TYPE_AUDIO = 0x02;

function connectToGame() {
  const url = `ws://localhost:${GAME_WS_PORT}`;
  console.log(`[game-ws] connecting to ${url}`);
  gameWs = new WebSocket(url, { perMessageDeflate: false });

  gameWs.on('open', () => {
    console.log('[game-ws] connected to Java game');
  });

  gameWs.on('message', (data, isBinary) => {
    if (!isBinary) return;
    // Cache only video frames so new clients get a picture immediately.
    // Audio frames are not cached — clients that connect mid-stream pick up
    // from the next audio chunk naturally.
    const type = data[0];
    if (type === TYPE_VIDEO) {
      frameCount++;
      if (frameCount % 300 === 0) console.log(`[game-ws] relayed ${frameCount} frames`);
      lastVideoFrame = data;
    }
    broadcast(data);
  });

  gameWs.on('close', () => {
    console.warn('[game-ws] disconnected — retrying in 2s');
    gameWs = null;
    setTimeout(connectToGame, 2000);
  });

  gameWs.on('error', (e) => {
    console.error('[game-ws] error:', e.message);
    // close event fires next and handles retry
  });
}

// ---------------------------------------------------------------------------
// Spawn the Java game (optional) then connect via WebSocket
// ---------------------------------------------------------------------------
if (SPAWN_GAME) {
  console.log(`[spawn] cd ${GAME_DIR}`);
  console.log(`[spawn] ${GAME_CMD} ${GAME_ARGS.join(' ')}`);

  const proc = spawn(GAME_CMD, GAME_ARGS, {
    cwd: GAME_DIR,
    stdio: ['ignore', 'ignore', 'inherit'], // stderr only — no stdout pipe needed
  });

  proc.on('close', (code) => console.log(`[spawn] game exited with code ${code}`));
  proc.on('error', (err) => console.error('[spawn] failed to start game:', err.message));

  // Give Java a moment to bind its WebSocket port before we connect
  setTimeout(connectToGame, 1500);
} else {
  connectToGame();
}

// ---------------------------------------------------------------------------
// Start listening
// ---------------------------------------------------------------------------
httpServer.listen(PORT, () => {
  console.log(`\n🎮  DOOM stream server → http://localhost:${PORT}\n`);
  if (SPAWN_GAME) {
    console.log('Game process will start now. Open the URL above in your browser.\n');
  } else {
    console.log(`Waiting for Java game on ws://localhost:${GAME_WS_PORT}`);
    console.log(`  java -jar target/mochadoom-1.0.0-SNAPSHOT.jar -websocket ${GAME_WS_PORT}\n`);
    console.log('Or let this server spawn the game automatically:');
    console.log(`  node server/server.js --spawn\n`);
  }
});
