package timing;

public class MilliTicker implements ITicker {

    private final int fps;

    public MilliTicker() {
        this(60);
    }

    public MilliTicker(int fps) {
        this.fps = fps;
    }

    @Override
    public int GetTime() {
        long tp = System.currentTimeMillis();
        if (basetime == 0) {
            basetime = tp;
        }
        return (int) (((tp - basetime) * fps) / 1000);
    }

    protected volatile long basetime = 0;
    protected volatile int oldtics = 0;
}
