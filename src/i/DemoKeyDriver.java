/*
 * DemoKeyDriver
 *
 * Injects a hard-coded sequence of key-down / key-up events directly into the
 * Doom event queue.  This is used in headless (-stdout / -outfile) mode to
 * drive the game without any real keyboard or .lmp demo file, just enough
 * input to show meaningful frame changes.
 *
 * Key sequence (game-tic offsets, 1 tic ≈ 1 second at the 1-FPS ticker):
 *
 *   tic  5  — right-arrow key DOWN   (start turning right)
 *   tic 10  — right-arrow key UP     (stop turning right)
 *   tic 15  — space key DOWN         (use / fire)
 *   tic 20  — space key UP
 *   tic 25  — left-arrow key DOWN    (start turning left)
 *   tic 30  — left-arrow key UP      (stop turning left)
 *
 * After the last event the game continues running indefinitely.
 */
package i;

import doom.event_t;
import doom.evtype_t;
import g.Signals.ScanCode;
import mochadoom.Loggers;

import java.util.logging.Logger;

/**
 * Synthetic key-input driver for headless frame-capture mode.
 * Call {@link #tick(int, doom.IDoom)} once per game loop iteration.
 */
public class DemoKeyDriver {

    private static final Logger LOGGER = Loggers.getLogger(DemoKeyDriver.class.getName());


    // -----------------------------------------------------------------------
    //  Hard-coded event schedule
    // -----------------------------------------------------------------------

    private static final int[] TIC     = {  5,            10,           15,         20,        25,          30  };
    private static final ScanCode[] KEY = { ScanCode.SC_RIGHT, ScanCode.SC_RIGHT,
                                            ScanCode.SC_SPACE, ScanCode.SC_SPACE,
                                            ScanCode.SC_LEFT,  ScanCode.SC_LEFT  };
    private static final evtype_t[] EV = { evtype_t.ev_keydown, evtype_t.ev_keyup,
                                           evtype_t.ev_keydown, evtype_t.ev_keyup,
                                           evtype_t.ev_keydown, evtype_t.ev_keyup };

    private static final int LAST_EVENT_TIC = TIC[TIC.length - 1];

    // -----------------------------------------------------------------------

    private int nextEvent = 0;    // index into TIC/KEY/EV arrays

    /**
     * Called once per game loop iteration.  Injects any due events and,
     * after the sequence completes, counts down to System.exit(0).
     *
     * @param gametic current game tic counter
     * @param doom    the running DoomMain instance (used to post events)
     */
    public void tick(int gametic, doom.IDoom doom) {
        // Inject all events whose tic has arrived
        while (nextEvent < TIC.length && gametic >= TIC[nextEvent]) {
            final evtype_t type = EV[nextEvent];
            final ScanCode sc   = KEY[nextEvent];
            LOGGER.fine(String.format("DemoKeyDriver: tic=%d  %s  %s", gametic, type, sc));
            doom.PostEvent(new event_t.keyevent_t(type, sc));
            nextEvent++;
        }

    }
}
