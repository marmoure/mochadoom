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

import i.GameWebSocketServer;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Public API entry point for embedding MochaDoom as a library.
 *
 * <p>Typical usage:
 * <pre>{@code
 * DoomConfig config = DoomConfig.builder()
 *     .iwad("/path/to/doom.wad")
 *     .webSocketPort(8080)
 *     .noSound(true)
 *     .build();
 *
 * MochaDoom doom = new MochaDoom(config);
 * doom.start();       // non-blocking — game runs in a background daemon thread
 *
 * // later...
 * doom.pause();
 * doom.resume();
 * doom.stop();
 * }</pre>
 *
 * <p><strong>Single instance:</strong> The underlying engine is a JVM-wide singleton.
 * Constructing multiple {@code MochaDoom} objects and calling {@code start()} on
 * more than one of them in the same JVM will throw {@link IllegalStateException}
 * on the second call.
 *
 * @since 1.0.0
 */
public final class MochaDoom {

    private static final Logger LOG = Logger.getLogger(MochaDoom.class.getName());

    private final DoomConfig config;

    /** Non-null after {@link #start()} returns successfully. */
    private volatile Thread gameThread;

    /** Tracks whether a library-level pause is currently active. */
    private volatile boolean paused = false;

    /**
     * Creates a new MochaDoom instance with the given configuration.
     * The game does not start until {@link #start()} is called.
     *
     * @param config engine configuration; must not be {@code null}
     * @throws NullPointerException if {@code config} is {@code null}
     */
    public MochaDoom(DoomConfig config) {
        if (config == null) throw new NullPointerException("config must not be null");
        this.config = config;
    }

    /**
     * Starts the game engine in a background daemon thread and returns immediately.
     *
     * <p>Engine initialisation (loading WAD data, setting up subsystems) happens
     * synchronously before this method returns, so any startup {@link IOException}
     * surfaces here rather than silently inside the background thread.
     *
     * @throws IllegalStateException if {@code start()} has already been called on this instance
     * @throws IOException           if engine initialisation fails (e.g. WAD not found)
     * @since 1.0.0
     */
    public synchronized void start() throws IOException {
        if (gameThread != null) {
            throw new IllegalStateException("MochaDoom is already running");
        }

        Engine.startGame(config.toArgv());

        if (config.webSocketPort > 0) {
            Engine.startWebSocketServer(config.webSocketPort);
        }

        final Thread t = new Thread(() -> {
            try {
                Engine.getEngine().DOOM.setupLoop();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "MochaDoom game loop terminated unexpectedly", e);
            }
        }, "mochadoom-game-loop");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
            LOG.log(Level.SEVERE, "Unhandled exception in " + thread.getName(), ex));

        gameThread = t;
        t.start();
    }

    /**
     * Pauses the game loop, halting all game ticks, sound, and frame rendering.
     *
     * <p>This is a <em>true</em> tick-halt pause: the game-loop thread blocks until
     * {@link #resume()} is called. This is distinct from the in-game pause (triggered
     * by the player pressing Pause/Escape), which only draws a banner and mutes audio
     * but continues advancing game state.
     *
     * <p>Has no effect if already paused or if {@link #start()} has not been called.
     *
     * @since 1.0.0
     */
    public void pause() {
        if (!paused) {
            paused = true;
            Engine.setLibraryPaused(true);
        }
    }

    /**
     * Resumes the game loop after a {@link #pause()}.
     *
     * <p>Has no effect if the game is not currently paused.
     *
     * @since 1.0.0
     */
    public void resume() {
        if (paused) {
            paused = false;
            Engine.setLibraryPaused(false);
        }
    }

    /**
     * Returns {@code true} if the game is currently paused via {@link #pause()}.
     *
     * @return {@code true} if paused
     * @since 1.0.0
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Returns {@code true} if {@link #start()} has been called and the game loop
     * thread is still alive.
     *
     * @return {@code true} if running
     * @since 1.0.0
     */
    public boolean isRunning() {
        final Thread t = gameThread;
        return t != null && t.isAlive();
    }

    /**
     * Requests a shutdown by interrupting the background game-loop thread.
     *
     * <p>The game loop does not have a graceful shutdown path in the current
     * implementation, so this is best-effort. If the game loop is blocked in
     * {@link #pause()}, the interrupt clears the block first.
     *
     * @since 1.0.0
     */
    public void stop() {
        final Thread t = gameThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Returns the underlying {@link GameWebSocketServer} if the engine was started
     * in WebSocket mode, or {@code null} otherwise.
     *
     * <p><strong>Note:</strong> {@link GameWebSocketServer} is an internal
     * implementation class. Its API may change between versions.
     *
     * @return the WebSocket server, or {@code null} if not in WebSocket mode
     * @since 1.0.0
     */
    public GameWebSocketServer getWebSocketServer() {
        return Engine.getWebSocketServer();
    }

    /**
     * Returns the background game-loop thread, or {@code null} if {@link #start()}
     * has not been called.
     *
     * <p>Callers that need to wait for the game to exit can call
     * {@link Thread#join()} on the returned thread.
     *
     * @return the game thread, or {@code null}
     * @since 1.0.0
     */
    public Thread getGameThread() {
        return gameThread;
    }
}
