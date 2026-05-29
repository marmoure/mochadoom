/*
 * Copyright (C) 2017 Good Sign
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package mochadoom;

import awt.DoomWindow;
import awt.DoomWindowController;
import awt.EventBase.KeyStateInterest;
import static awt.EventBase.KeyStateSatisfaction.*;
import awt.EventHandler;
import awt.HeadlessController;
import doom.CVarManager;
import doom.CommandVariable;
import doom.ConfigManager;
import doom.DoomMain;
import static g.Signals.ScanCode.*;
import i.DemoKeyDriver;
import i.FileFrameWriter;
import i.GameWebSocketServer;
import i.Strings;
import i.StdinKeyReader;
import i.StdoutAudioWriter;
import i.StdoutFrameWriter;
import i.WebSocketFrameWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Engine {
    private static volatile Engine instance;

    private static final ReentrantLock PAUSE_LOCK    = new ReentrantLock();
    private static final Condition     PAUSE_COND    = PAUSE_LOCK.newCondition();
    private static volatile boolean    libraryPaused = false;
    
    /**
     * Mocha Doom engine entry point.
     */
    public static void main(final String[] argv) throws IOException {
        startGame(argv);

        if (instance.wsServer != null) {
            final int port = instance.cvm.get(CommandVariable.WEBSOCKET, Integer.class, 0).orElse(8080);
            startWebSocketServer(port);
        }

        // never returns
        try {
            instance.DOOM.setupLoop();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Initialise the game engine with the given command-line arguments.
     * Must be called before {@link #startWebSocketServer(int)} or
     * {@link DoomMain#setupLoop()}.
     */
    public static void startGame(final String[] args) throws IOException {
        // Must be set BEFORE any AWT class is loaded.
        for (final String arg : args) {
            if ("-stdout".equalsIgnoreCase(arg) || "-websocket".equalsIgnoreCase(arg)) {
                System.setProperty("java.awt.headless", "true");
                System.setOut(System.err);
                break;
            }
        }
        synchronized (Engine.class) {
            if (instance == null) {
                instance = new Engine(args);
            }
        }
    }

    /**
     * Start the embedded WebSocket server on the given port.
     * Call {@link #startGame(String[])} first, and only when the engine was
     * initialised with the {@code -websocket} flag.
     */
    public static void startWebSocketServer(final int port) {
        final Engine local = instance;
        if (local == null) throw new IllegalStateException("Call startGame() first");
        if (local.wsServer == null) throw new IllegalStateException("Engine not in -websocket mode");
        local.wsServer.start(port, local.DOOM);
    }  
    
    public final CVarManager cvm;
    public final ConfigManager cm;

    /** Non-null only in normal (AWT window) mode. */
    public final DoomWindowController<?, EventHandler> windowController;

    /** Non-null only in headless (-stdout) mode. */
    private final HeadlessController headlessController;

    /** Non-null only in headless (-stdout) mode; writes RGBA frames to stdout. */
    private final StdoutFrameWriter stdoutWriter;

    /** Non-null when -outfile is specified; appends RGBA frames to a binary file. */
    private final FileFrameWriter fileWriter;

    /** Non-null when -demokeys is specified; injects synthetic key events each tick. */
    private final DemoKeyDriver demoKeyDriver;

    /** Non-null in -websocket mode; accepts browser connections and receives key events. */
    private final GameWebSocketServer wsServer;

    /** Non-null in -websocket mode; encodes frames as JPEG and broadcasts via wsServer. */
    private final WebSocketFrameWriter wsFrameWriter;

    /** Non-null in headless (-stdout) mode; writes PCM audio chunks to stdout. */
    private final StdoutAudioWriter stdoutAudioWriter;

    final DoomMain<?, ?> DOOM;
    
    @SuppressWarnings("unchecked")
    private Engine(final String... argv) throws IOException {
        instance = this;

        // reads command line arguments
        this.cvm = new CVarManager(Arrays.asList(argv));

        // reads default.cfg and mochadoom.cfg
        this.cm = new ConfigManager();

        final boolean websocket = cvm.present(CommandVariable.WEBSOCKET);
        final boolean headless  = cvm.bool(CommandVariable.STDOUT);

        // Audio-output sinks must be set BEFORE new DoomMain<>() because DoomMain's
        // constructor calls ISoundDriver.InitSound(), which checks Engine.hasAudioOutput()
        // to decide whether to open a hardware audio line.
        if (websocket) {
            this.wsServer          = new GameWebSocketServer();
            this.stdoutAudioWriter = null;
        } else if (headless) {
            this.wsServer          = null;
            this.stdoutAudioWriter = new StdoutAudioWriter(22050, 2, 16);
        } else {
            this.wsServer          = null;
            this.stdoutAudioWriter = null;
        }

        // initializes stuff — sound driver InitSound() runs here
        this.DOOM = new DoomMain<>();

        if (websocket) {
            // ---- WEBSOCKET MODE: no AWT window, frames sent as JPEG over WebSocket ----
            this.headlessController  = new HeadlessController();
            this.windowController    = null;
            this.stdoutWriter        = null;
            this.fileWriter          = null;
            this.demoKeyDriver       = null;
            this.wsFrameWriter       = new WebSocketFrameWriter(wsServer);
        } else if (headless) {
            // ---- HEADLESS MODE: no AWT window, output goes to stdout / file ----
            this.headlessController  = new HeadlessController();
            this.windowController    = null;
            this.stdoutWriter        = new StdoutFrameWriter();
            this.wsFrameWriter       = null;

            // -outfile <path>: append RGBA frames to a binary file (only when explicitly requested).
            if (cvm.present(CommandVariable.OUTFILE)) {
                final String outfilePath = cvm.get(CommandVariable.OUTFILE, String.class, 0).orElse("doom_frames.bin");
                this.fileWriter = new FileFrameWriter(outfilePath);
            } else {
                this.fileWriter = null;
            }

            // -demokeys: activate synthetic key-event driver.
            this.demoKeyDriver = cvm.bool(CommandVariable.DEMOKEYS) ? new DemoKeyDriver() : null;

            // Start stdin key reader so browser can inject input via the relay.
            final Thread stdinThread = new Thread(new StdinKeyReader(this.DOOM), "stdin-key-reader");
            stdinThread.setDaemon(true);
            stdinThread.start();
        } else {
            // ---- NORMAL MODE: AWT canvas window ----
            this.headlessController  = null;
            this.stdoutWriter        = null;
            this.fileWriter          = null;
            this.demoKeyDriver       = null;
            this.wsFrameWriter       = null;
            this.windowController   = DoomWindow.createCanvasWindowController(
                DOOM.graphicSystem::getScreenImage,
                DOOM::PostEvent,
                DOOM.graphicSystem.getScreenWidth(),
                DOOM.graphicSystem.getScreenHeight()
            );

            windowController.getObserver().addInterest(
                new KeyStateInterest<>(obs -> {
                    EventHandler.fullscreenChanges(windowController.getObserver(), windowController.switchFullscreen());
                    return WANTS_MORE_ATE;
                }, SC_LALT, SC_ENTER)
            ).addInterest(
                new KeyStateInterest<>(obs -> {
                    if (!windowController.isFullscreen()) {
                        if (DOOM.menuactive || DOOM.paused || DOOM.demoplayback) {
                            EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = !DOOM.mousecaptured);
                        } else {
                            EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = true);
                        }
                    }
                    return WANTS_MORE_PASS;
                }, SC_LALT)
            ).addInterest(
                new KeyStateInterest<>(obs -> {
                    if (!windowController.isFullscreen() && !DOOM.mousecaptured && DOOM.menuactive) {
                        EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = true);
                    }
                    return WANTS_MORE_PASS;
                }, SC_ESCAPE)
            ).addInterest(
                new KeyStateInterest<>(obs -> {
                    if (!windowController.isFullscreen() && !DOOM.mousecaptured && DOOM.paused) {
                        EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = true);
                    }
                    return WANTS_MORE_PASS;
                }, SC_PAUSE)
            );
        }
    }
    
    /**
     * Returns the active {@link GameWebSocketServer}, or {@code null} if
     * the engine was not started in WebSocket mode.
     *
     * @return the WebSocket server, or {@code null}
     */
    public static GameWebSocketServer getWebSocketServer() {
        final Engine local = instance;
        return local != null ? local.wsServer : null;
    }

    /**
     * Sets the library-level pause state. When pausing, also halts audio and
     * sets the in-game {@code paused} flag. On resume, reverses all three and
     * unblocks the game loop thread.
     *
     * <p>Called from {@link MochaDoom#pause()} and {@link MochaDoom#resume()}.
     *
     * @param pause {@code true} to pause, {@code false} to resume
     */
    public static void setLibraryPaused(boolean pause) {
        PAUSE_LOCK.lock();
        try {
            libraryPaused = pause;
            final Engine eng = instance;
            if (eng != null) {
                eng.DOOM.paused = pause;
                if (pause) {
                    eng.DOOM.doomSound.PauseSound();
                } else {
                    eng.DOOM.doomSound.ResumeSound();
                    PAUSE_COND.signalAll();
                }
            }
        } finally {
            PAUSE_LOCK.unlock();
        }
    }

    /**
     * Blocks the game-loop thread until the library-level pause is cleared.
     * Returns immediately when not paused. Called once per iteration at the
     * top of {@link doom.DoomMain#DoomLoop()}.
     */
    public static void awaitIfLibraryPaused() {
        if (!libraryPaused) return; // fast path — no lock cost on every normal tick
        PAUSE_LOCK.lock();
        try {
            while (libraryPaused) {
                PAUSE_COND.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            PAUSE_LOCK.unlock();
        }
    }

    /**
     * Returns {@code true} when audio is being streamed (stdout or WebSocket mode).
     * The sound driver uses this to succeed even without a hardware audio device
     * and to skip opening one when streaming.
     */
    public static boolean hasAudioOutput() {
        final Engine local = instance;
        return local != null && (local.stdoutAudioWriter != null || local.wsServer != null);
    }

    /**
     * Delivers a mixed PCM chunk to all active audio outputs (stdout and/or WebSocket).
     * Called from the sound driver's playback thread.
     *
     * @param pcm    buffer containing signed 16-bit big-endian stereo samples
     * @param length number of valid bytes in {@code pcm}
     */
    public static void updateAudio(byte[] pcm, int length) {
        final Engine local = instance;
        if (local == null) return;
        if (local.stdoutAudioWriter != null) {
            local.stdoutAudioWriter.writeChunk(pcm, length);
        }
        if (local.wsServer != null) {
            local.wsServer.broadcastAudio(pcm, length);
        }
    }

    /**
     * Delivers a mixed PCM music chunk to the WebSocket audio stream (type 0x03).
     * Kept separate from {@link #updateAudio} so the browser can schedule music
     * and SFX on independent timelines and avoid interleaving gaps.
     *
     * @param pcm    buffer containing signed 16-bit big-endian stereo samples
     * @param length number of valid bytes in {@code pcm}
     */
    public static void updateMusic(byte[] pcm, int length) {
        final Engine local = instance;
        if (local == null) return;
        if (local.wsServer != null) {
            local.wsServer.broadcastMusic(pcm, length);
        }
    }

    public static void updateFrame() {
        if (instance.wsFrameWriter != null) {
            instance.wsFrameWriter.writeFrame(instance.DOOM.graphicSystem);
        }
        if (instance.stdoutWriter != null) {
            instance.stdoutWriter.writeFrame(instance.DOOM.graphicSystem);
        }
        if (instance.fileWriter != null) {
            instance.fileWriter.writeFrame(instance.DOOM.graphicSystem);
        }
        if (instance.wsFrameWriter == null && instance.stdoutWriter == null && instance.fileWriter == null) {
            instance.windowController.updateFrame();
        }
    }

    /**
     * Returns the active {@link DemoKeyDriver}, or {@code null} if
     * {@code -demokeys} was not specified.
     */
    public static DemoKeyDriver getDemoKeyDriver() {
        final Engine local = instance;
        return local != null ? local.demoKeyDriver : null;
    }
        
    public String getWindowTitle(double frames) {
        if (cvm.bool(CommandVariable.SHOWFPS)) {
            return String.format("%s - %s FPS: %.2f", Strings.MOCHA_DOOM_TITLE, DOOM.bppMode, frames);
        } else {
            return String.format("%s - %s", Strings.MOCHA_DOOM_TITLE, DOOM.bppMode);
        }
    }

    public static Engine getEngine() {
        Engine local = Engine.instance;
        if (local == null) {
            synchronized (Engine.class) {
                local = Engine.instance;
                if (local == null) {
                    try {
                        Engine.instance = local = new Engine();
                    } catch (IOException ex) {
                        Logger.getLogger(Engine.class.getName()).log(Level.SEVERE, null, ex);
                        throw new Error("This launch is DOOMed");
                    }
                }
            }
        }
        
        return local;
    }
    
    public static CVarManager getCVM() {
        return getEngine().cvm;
    }
    
    public static ConfigManager getConfig() {
        return getEngine().cm;
    }
}
