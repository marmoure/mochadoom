# Plan: Stream Doom → Node.js Server → Browser

## Context

The full pipeline already exists in code:
- `src/i/StdoutFrameWriter.java` — writes binary RGBA frames to stdout
- `server/server.js` — Node.js server parses frames, converts to JPEG, broadcasts via WebSocket
- `server/index.html` — browser client renders JPEG frames on a canvas

The recent commits ("still trying to output over stream") indicate the stream is not working. The root cause is a stdout redirect bug explained below.

---

## Root Cause: Stdout Redirect Bug

In `Engine.main()` (line 55), when `-stdout` is detected:

```java
System.setOut(System.err);  // redirect ALL Java output to stderr
```

This runs **before** `new Engine(argv)` — and therefore before `new StdoutFrameWriter()`.

In `StdoutFrameWriter` constructor (line 44):

```java
this.out = new DataOutputStream(new BufferedOutputStream(System.out, 256 * 1024));
```

At this point `System.out` is already the redirected stream (i.e. stderr). So **frame data is written to stderr**, not stdout. The Node.js process piping stdout receives nothing.

---

## Fix

### 1. `src/i/StdoutFrameWriter.java` — use `FileDescriptor.out` directly

Replace the constructor body with:

```java
import java.io.FileDescriptor;
import java.io.FileOutputStream;

public StdoutFrameWriter() {
    // FileDescriptor.out is the real file descriptor 1 (stdout), unaffected
    // by System.setOut() which only redirects the Java PrintStream wrapper.
    this.out = new DataOutputStream(
        new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 256 * 1024)
    );
}
```

This bypasses `System.setOut()` entirely and writes directly to the OS-level stdout fd, which is what the Node.js pipe reads.

### 2. `server/server.js` — add game flags to spawn args

The current `GAME_ARGS` (line 40) lacks `-nosound` and a game-activity mode. Update:

```javascript
const GAME_ARGS = [
  '-jar', 'src/mochadoom.jar',
  '-stdout',
  '-nosound',
  '-demokeys',   // drive game with synthetic keys so frames aren't static
];
```

Alternatively use `-timedemo demo1` instead of `-demokeys` to replay a built-in demo.
Ignore the demo all togerther
---

## Files to Modify

| File | Change |
|------|--------|
| `src/i/StdoutFrameWriter.java` | Constructor: `new FileOutputStream(FileDescriptor.out)` instead of `System.out` |
| `server/server.js` | Add `-nosound` and `-demokeys` to `GAME_ARGS` array |

---

## Run Instructions (Windows)

```powershell
# 1. Install Node deps (once)
cd server; npm install; cd ..

# 2. Build the JAR
.\build-jar.ps1

# 3. Start the streaming server (spawns Java automatically)
cd server; node server.js --spawn
```

Then open `http://localhost:8080` in a browser. The canvas should show live Doom frames.

---

## Verification

1. Terminal shows: `DOOM stream server running at http://localhost:8080`
2. Browser status pill shows **LIVE** (green dot)
3. Frame counter increments in the top-right stats
4. FPS meter shows a positive value
5. Canvas displays Doom gameplay frames
