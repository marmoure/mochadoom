package timing;

public class NanoTicker implements ITicker {

    private static final int DEFAULT_FPS = 60;

    private final int fps;

    public NanoTicker() {
        this(DEFAULT_FPS);
    }

    public NanoTicker(int fps) {
        this.fps = fps;
    }

    @Override
    public int GetTime() {
        long tp = System.nanoTime();
        if (basetime == 0) {
            basetime = tp;
        }
        int newtics = (int) (((tp - basetime) * fps) / 1_000_000_000L);
        if (newtics < oldtics) {
            System.err.printf("Timer discrepancies detected : %d", (++discrepancies));
            return oldtics;
        }
        return (oldtics = newtics);
    }

    protected volatile long basetime = 0;
    protected volatile int oldtics = 0;
    protected volatile int discrepancies;
}
