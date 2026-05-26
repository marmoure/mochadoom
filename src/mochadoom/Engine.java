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
import i.Strings;
import i.StdinKeyReader;
import i.StdoutFrameWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Engine {
    private static volatile Engine instance;
    
    /**
     * Mocha Doom engine entry point
     */
    public static void main(final String[] argv) throws IOException {
        // Must be set BEFORE any AWT class is loaded — do it as the very
        // first thing based on a raw argv scan (CVarManager isn't built yet).
        for (final String arg : argv) {
            if ("-stdout".equalsIgnoreCase(arg)) {
                System.setProperty("java.awt.headless", "true");
                // Redirect System.out → System.err so that all text logging
                // (init messages, debug prints, etc.) goes to stderr and does
                // NOT corrupt the binary RGBA frame stream on stdout.
                System.setOut(System.err);
                break;
            }
        }

        final Engine local;
        synchronized (Engine.class) {
            local = new Engine(argv);
        }
        
        /**
         * Add eventHandler listeners to JFrame and its Canvas elememt
         */
        /*content.addKeyListener(listener);        
        content.addMouseListener(listener);
        content.addMouseMotionListener(listener);
        frame.addComponentListener(listener);
        frame.addWindowFocusListener(listener);
        frame.addWindowListener(listener);*/
        // never returns
        try {
            local.DOOM.setupLoop();
        } catch(Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
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

    private final DoomMain<?, ?> DOOM;
    
    @SuppressWarnings("unchecked")
    private Engine(final String... argv) throws IOException {
        instance = this;

        // reads command line arguments
        this.cvm = new CVarManager(Arrays.asList(argv));

        // reads default.cfg and mochadoom.cfg
        this.cm = new ConfigManager();

        // initializes stuff
        this.DOOM = new DoomMain<>();

        final boolean headless = cvm.bool(CommandVariable.STDOUT);

        if (headless) {
            // ---- HEADLESS MODE: no AWT window, output goes to stdout / file ----
            this.headlessController = new HeadlessController();
            this.windowController   = null;
            this.stdoutWriter       = new StdoutFrameWriter();

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
            this.headlessController = null;
            this.stdoutWriter       = null;
            this.fileWriter         = null;
            this.demoKeyDriver      = null;
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
     * Temporary solution. Will be later moved in more detalied place
     */
    public static void updateFrame() {
        if (instance.stdoutWriter != null) {
            // Headless stdout mode: write RGBA frame packet to stdout
            instance.stdoutWriter.writeFrame(instance.DOOM.graphicSystem);
        }
        if (instance.fileWriter != null) {
            // Headless file mode: also append RGBA frame packet to output file
            instance.fileWriter.writeFrame(instance.DOOM.graphicSystem);
        }
        if (instance.stdoutWriter == null && instance.fileWriter == null) {
            // Normal mode: repaint the AWT window
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
