# MochaDoom — Library Usage Guide

MochaDoom can be embedded in any Java/Maven project as a library JAR.
This document covers setup, configuration, and common use patterns.

---

## 1. Build and install

Clone the repo and install the JAR to your local Maven repository:

```bash
cd mochadoom
mvn install
```

This produces `~/.m2/repository/io/github/mochadoom/mochadoom/1.0.0-SNAPSHOT/mochadoom-1.0.0-SNAPSHOT.jar`
and a matching Javadoc JAR. HTML documentation is also written to `target/apidocs/`.

---

## 2. Add the dependency

In your project's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.mochadoom</groupId>
    <artifactId>mochadoom</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 3. Quick start

```java
import mochadoom.DoomConfig;
import mochadoom.MochaDoom;

public class Main {
    public static void main(String[] args) throws Exception {

        DoomConfig config = DoomConfig.builder()
            .iwad("/path/to/doom.wad")   // path to your IWAD file
            .webSocketPort(8080)          // stream frames to the browser
            .noSound(true)                // disable audio (useful on servers)
            .build();

        MochaDoom doom = new MochaDoom(config);
        doom.start();   // returns immediately; game runs in background

        System.out.println("Game running. Open http://localhost:8080 in a browser.");

        // Keep the JVM alive (game thread is a daemon thread)
        doom.getGameThread().join();
    }
}
```

Open `http://localhost:8080` in any browser to see the game and use the keyboard
to play. The built-in Node.js relay is not needed when using WebSocket mode directly.

---

## 4. DoomConfig builder reference

| Method | Default | Description |
|--------|---------|-------------|
| `.iwad(String path)` | auto-discover | Path to the IWAD (e.g. `doom.wad`, `doom2.wad`) |
| `.webSocketPort(int port)` | disabled | Enable WebSocket server on this port. Also sets headless mode. |
| `.noSound(boolean)` | `false` | Disable all sound and music (`-nosound`) |
| `.noMusic(boolean)` | `false` | Disable music only (`-nomusic`) |
| `.headless(boolean)` | `false` | No AWT window. Implied by `webSocketPort`. |
| `.extraArgs(String... args)` | none | Pass raw engine flags not yet covered by the builder |

### Extra args examples

```java
DoomConfig config = DoomConfig.builder()
    .iwad("doom2.wad")
    .extraArgs("-skill", "4")          // UV difficulty
    .extraArgs("-warp", "1", "1")      // jump straight to E1M1
    .extraArgs("-timedemo", "demo1")   // benchmark mode
    .build();
```

---

## 5. Pause and resume

`MochaDoom.pause()` is a true tick-halt: the game-loop thread blocks completely
until `resume()` is called. Audio stops and the in-game PAUSE banner is shown on
any connected browser.

```java
doom.start();

Thread.sleep(5_000);
doom.pause();
System.out.println("Game paused: " + doom.isPaused()); // true

Thread.sleep(3_000);
doom.resume();
System.out.println("Game resumed");
```

This is different from the player pressing the Pause key in-game, which only
mutes audio and draws the banner but does not stop game ticks.

---

## 6. WebSocket server access

When `webSocketPort` is set, the embedded WebSocket server is available after `start()`:

```java
import i.GameWebSocketServer;

GameWebSocketServer ws = doom.getWebSocketServer();
if (ws != null) {
    System.out.println("WebSocket clients connected: " + ws.getClientCount());
}
```

The browser client at `server/index.html` (included in the repo) connects to
this server automatically. You can also connect your own WebSocket client: the
server sends binary frames (JPEG-encoded game frames) and accepts text frames
with key events in this format:

```json
{ "t": "d", "k": "ArrowLeft" }   // key down
{ "t": "u", "k": "ArrowLeft" }   // key up
```

Supported key names: `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`,
`Space`, `Control`, `Shift`, `Alt`, `Enter`, `Escape`, `Tab`,
`F1`–`F3`, `F10`–`F12`, `1`–`7`.

---

## 7. Headless mode (no WebSocket)

If you want the game to run without any visible window and without WebSocket,
use headless mode. Frames will not be sent anywhere but the game loop runs normally.
This is useful when you only need game state or are implementing your own rendering.

```java
DoomConfig config = DoomConfig.builder()
    .iwad("doom.wad")
    .headless(true)
    .noSound(true)
    .build();

MochaDoom doom = new MochaDoom(config);
doom.start();
```

---

## 8. Thread management

`start()` launches the game loop in a background thread named `"mochadoom-game-loop"`.
The thread is a **daemon thread**, meaning it does not prevent the JVM from exiting
when your own code finishes.

### Keep the JVM alive until the game exits

```java
doom.start();
doom.getGameThread().join(); // blocks here until the game loop ends
```

### Check whether the game is still running

```java
if (doom.isRunning()) {
    System.out.println("Game is running");
}
```

`isRunning()` returns `false` after `stop()` is called and the thread has died,
or if the game loop threw an unhandled exception.

### Monitor the game thread from outside

```java
Thread gameThread = doom.getGameThread();

// Wait up to 10 seconds for the game to exit on its own
gameThread.join(10_000);

if (gameThread.isAlive()) {
    System.out.println("Game still running after 10 s");
}
```

### Run the game fully in its own thread group

If you want the game thread isolated with a custom `ThreadGroup` (useful for
catching uncaught exceptions from a parent context):

```java
ThreadGroup doomGroup = new ThreadGroup("doom-group");
Thread launcher = new Thread(doomGroup, () -> {
    try {
        MochaDoom doom = new MochaDoom(config);
        doom.start();
        doom.getGameThread().join();
    } catch (Exception e) {
        e.printStackTrace();
    }
}, "doom-launcher");
launcher.setDaemon(false); // non-daemon so the JVM waits for it
launcher.start();
launcher.join(); // blocks until the entire game session ends
```

---

## 9. Stopping the game

Call `stop()` to interrupt the game-loop thread:

```java
doom.stop();
```

`stop()` sends a thread interrupt. The game loop does not have a graceful shutdown
path, so the thread will terminate at the next interruptible operation (typically
within one tick, i.e. < 30 ms at the default frame rate).

### Wait for the thread to actually die

`stop()` returns immediately. If you need to confirm shutdown before continuing:

```java
doom.stop();
Thread gameThread = doom.getGameThread();
if (gameThread != null) {
    gameThread.join(5_000); // wait up to 5 s
    if (gameThread.isAlive()) {
        System.err.println("Game thread did not exit cleanly");
    }
}
```

### Stopping a paused game

If the game is paused when you call `stop()`, the interrupt wakes the blocked
thread and it exits cleanly — you do not need to `resume()` first:

```java
doom.pause();
// ... later ...
doom.stop(); // works even while paused
```

---

## 10. Restarting the game

**The engine cannot be restarted within the same JVM.** The underlying engine
holds static state that is set once on first start and never reset.
Calling `start()` on a second `MochaDoom` instance after stopping the first
will throw `IllegalStateException`.

To support restart, run each game session in its own JVM process using
`ProcessBuilder`. Your application controls the process lifecycle and can
start a fresh one at any time.

### Example: restartable game controller

```java
import java.util.List;

public class RestartableGame {

    private Process process;

    /** Starts a new game session. Throws if a session is already running. */
    public void start(String iwadPath, int webSocketPort) throws Exception {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Game is already running");
        }

        // Resolve the same java executable that is running this code
        String java = ProcessHandle.current().info().command().orElse("java");

        process = new ProcessBuilder(
            java,
            "-jar", "mochadoom-1.0.0-SNAPSHOT.jar",
            "-iwad",      iwadPath,
            "-websocket", String.valueOf(webSocketPort),
            "-nosound"
        )
        .redirectErrorStream(true)   // merge stderr into stdout
        .start();

        // Drain output so the process does not block on a full pipe
        Thread outputDrain = new Thread(() -> {
            try { process.getInputStream().transferTo(System.out); }
            catch (Exception ignored) {}
        }, "doom-output-drain");
        outputDrain.setDaemon(true);
        outputDrain.start();
    }

    /** Stops the running session. No-op if not running. */
    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    /** Stops the current session and immediately starts a new one. */
    public void restart(String iwadPath, int webSocketPort) throws Exception {
        stop();
        start(iwadPath, webSocketPort);
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }
}
```

Usage:

```java
RestartableGame game = new RestartableGame();

game.start("doom.wad", 8080);
System.out.println("Session 1 running");

Thread.sleep(10_000);

game.restart("doom.wad", 8080);
System.out.println("Session 2 running (fresh engine state)");

Thread.sleep(10_000);
game.stop();
```

### Waiting for a process to exit

```java
boolean exited = process.waitFor(10, TimeUnit.SECONDS);
if (!exited) {
    process.destroyForcibly(); // hard kill if it didn't exit cleanly
}
int exitCode = process.exitValue();
```

### Why the in-process singleton cannot be reset

The engine stores its state in a `static` field in `Engine`. Static fields survive
for the lifetime of the classloader — there is no supported way to unload a
classloader mid-run in a standard application. Using a child classloader per
session would technically work but is fragile and not recommended. The
`ProcessBuilder` approach above is simpler, more isolated, and gives each session
a completely clean JVM.

---

## 11. Lifecycle summary

```
new MochaDoom(config)
        │
        ▼
   doom.start()  ──► game loop starts in daemon thread "mochadoom-game-loop"
        │
        ▼
   doom.pause()  ──► loop blocks, audio stops, PAUSE banner shown
        │
        ▼
  doom.resume()  ──► loop unblocks, audio resumes
        │
        ▼
   doom.stop()   ──► game-loop thread interrupted; isRunning() → false
```

`isRunning()` returns `true` between `start()` and thread death.
`isPaused()` returns `true` between `pause()` and `resume()`.

---

## 13. WAD file

MochaDoom requires a Doom IWAD. Free options:

- **Freedoom Phase 1 / Phase 2** — open-source, free to distribute: https://freedoom.github.io
- **DOOM Shareware** — `doom1.wad`, freely distributable first episode

Commercial WADs (`doom.wad`, `doom2.wad`) work but require ownership of the game.

---

## 14. Maven coordinates quick reference

```xml
<!-- dependency -->
<dependency>
    <groupId>io.github.mochadoom</groupId>
    <artifactId>mochadoom</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- to attach Javadoc in your IDE -->
<dependency>
    <groupId>io.github.mochadoom</groupId>
    <artifactId>mochadoom</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <classifier>javadoc</classifier>
</dependency>
```

Javadoc is also available as HTML in `target/apidocs/` after running `mvn javadoc:javadoc`
inside the mochadoom repo, or browsing `mochadoom/MochaDoom.html` and
`mochadoom/DoomConfig.html` directly.
