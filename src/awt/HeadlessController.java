/*
 * HeadlessController
 *
 * A no-op stand-in for DoomWindowController used when the game is started
 * with the -stdout flag. It satisfies every call that Engine.java makes on
 * the controller without touching any AWT display APIs.
 */
package awt;

import doom.event_t;
import java.util.function.Consumer;
import mochadoom.Loggers;

/**
 * Headless (no window) controller. Replaces {@link DoomWindowController} when
 * {@code -stdout} is active. All display-related operations are no-ops.
 */
public class HeadlessController {

    /** A minimal EventObserver-compatible object that accepts but ignores interests. */
    public static final class NoOpObserver {
        public NoOpObserver addInterest(EventBase.KeyStateInterest<?> interest) { return this; }
        public NoOpObserver removeInterest(EventBase.KeyStateInterest<?> interest) { return this; }
    }

    private final NoOpObserver observer = new NoOpObserver();

    public HeadlessController() {
        Loggers.getLogger(HeadlessController.class.getName())
               .info("Running in headless stdout mode — no AWT window will be created.");
    }

    /** Called each game tick instead of painting the frame. Frame is written by StdoutFrameWriter. */
    public void updateFrame() {
        // intentionally empty — frame output is handled by Engine.updateFrame()
    }

    /** Returns the no-op observer. The Engine registers key interests on this. */
    public NoOpObserver getObserver() {
        return observer;
    }

    /** Fullscreen toggle is meaningless without a window. */
    public boolean switchFullscreen() {
        return false;
    }

    public boolean isFullscreen() {
        return false;
    }
}
