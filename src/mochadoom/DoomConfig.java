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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for a {@link MochaDoom} instance.
 *
 * <p>Obtain an instance via the fluent builder:
 * <pre>{@code
 * DoomConfig config = DoomConfig.builder()
 *     .iwad("/path/to/doom.wad")
 *     .webSocketPort(8080)
 *     .noSound(true)
 *     .build();
 * }</pre>
 *
 * @since 1.0.0
 */
public final class DoomConfig {

    /**
     * TCP port on which the built-in WebSocket server will listen.
     * {@code -1} means WebSocket mode is disabled.
     */
    public final int webSocketPort;

    /**
     * Absolute or relative path to the IWAD file (e.g. {@code "doom.wad"}).
     * {@code null} means the engine will attempt auto-discovery.
     */
    public final String iwadPath;

    /** If {@code true}, all sound effects and music output are disabled. */
    public final boolean noSound;

    /** If {@code true}, music output only is disabled (sound effects still play). */
    public final boolean noMusic;

    /**
     * If {@code true}, the game runs without an AWT window.
     * Automatically {@code true} when {@link #webSocketPort} is set.
     */
    public final boolean headless;

    /**
     * Unmodifiable list of additional raw command-line arguments passed directly
     * to the engine. Use for flags not yet exposed as typed builder options.
     */
    public final List<String> extraArgs;

    private DoomConfig(Builder b) {
        this.webSocketPort = b.webSocketPort;
        this.iwadPath      = b.iwadPath;
        this.noSound       = b.noSound;
        this.noMusic       = b.noMusic;
        this.headless      = b.headless || b.webSocketPort > 0;
        this.extraArgs     = Collections.unmodifiableList(new ArrayList<>(b.extraArgs));
    }

    /**
     * Converts this configuration into the {@code String[]} argv array expected
     * by the underlying engine. Package-private — only {@link MochaDoom} calls this.
     *
     * @return argv array, never {@code null}
     */
    String[] toArgv() {
        List<String> args = new ArrayList<>();
        if (iwadPath != null) {
            args.add("-iwad");
            args.add(iwadPath);
        }
        if (webSocketPort > 0) {
            args.add("-websocket");
            args.add(String.valueOf(webSocketPort));
        } else if (headless) {
            args.add("-stdout");
        }
        if (noSound) args.add("-nosound");
        if (noMusic) args.add("-nomusic");
        args.addAll(extraArgs);
        return args.toArray(new String[0]);
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link DoomConfig}.
     *
     * @since 1.0.0
     */
    public static final class Builder {

        private int          webSocketPort = -1;
        private String       iwadPath      = null;
        private boolean      noSound       = false;
        private boolean      noMusic       = false;
        private boolean      headless      = false;
        private List<String> extraArgs     = new ArrayList<>();

        private Builder() {}

        /**
         * Sets the IWAD file path.
         *
         * @param path absolute or relative path to the IWAD (e.g. {@code "/games/doom.wad"})
         * @return this builder
         */
        public Builder iwad(String path) {
            this.iwadPath = path;
            return this;
        }

        /**
         * Enables the built-in WebSocket server on the given port.
         * Implies headless mode — no AWT window will be created.
         *
         * @param port TCP port to listen on (1–65535, e.g. {@code 8080})
         * @return this builder
         * @throws IllegalArgumentException if port is out of range
         */
        public Builder webSocketPort(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("webSocketPort must be 1–65535, got: " + port);
            }
            this.webSocketPort = port;
            return this;
        }

        /**
         * Disables all sound effects and music output ({@code -nosound}).
         *
         * @param disabled {@code true} to disable all sound
         * @return this builder
         */
        public Builder noSound(boolean disabled) {
            this.noSound = disabled;
            return this;
        }

        /**
         * Disables music output only ({@code -nomusic}). Sound effects still play.
         *
         * @param disabled {@code true} to disable music
         * @return this builder
         */
        public Builder noMusic(boolean disabled) {
            this.noMusic = disabled;
            return this;
        }

        /**
         * Runs in headless mode — no AWT window is created.
         * Implied automatically when {@link #webSocketPort(int)} is set.
         *
         * @param headless {@code true} for headless mode
         * @return this builder
         */
        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        /**
         * Appends raw engine command-line arguments.
         * Use for engine flags not yet directly exposed by this builder.
         *
         * <p>Example: {@code .extraArgs("-skill", "4", "-warp", "1", "1")}
         *
         * @param args one or more argument strings
         * @return this builder
         */
        public Builder extraArgs(String... args) {
            this.extraArgs.addAll(Arrays.asList(args));
            return this;
        }

        /**
         * Builds the immutable {@link DoomConfig}.
         *
         * @return a new configuration instance
         */
        public DoomConfig build() {
            return new DoomConfig(this);
        }
    }
}
