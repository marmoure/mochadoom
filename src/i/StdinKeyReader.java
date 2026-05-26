/*
 * StdinKeyReader
 *
 * Reads key-event commands from stdin (one per line) and posts them into the
 * Doom event queue.  Used in headless (-stdout) mode so a remote client (e.g.
 * the Node.js relay) can inject keyboard input.
 *
 * Line protocol (text, LF-terminated):
 *   d <keyname>   — key-down
 *   u <keyname>   — key-up
 *
 * <keyname> matches browser KeyboardEvent.key values (e.g. ArrowLeft, Space,
 * Control, Shift, 1 … 7).
 */
package i;

import doom.IDoom;
import doom.event_t;
import doom.evtype_t;
import g.Signals.ScanCode;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class StdinKeyReader implements Runnable {

    private final IDoom doom;

    private static final Map<String, ScanCode> KEY_MAP = new HashMap<>();

    static {
        KEY_MAP.put("ArrowLeft",  ScanCode.SC_LEFT);
        KEY_MAP.put("ArrowRight", ScanCode.SC_RIGHT);
        KEY_MAP.put("ArrowUp",    ScanCode.SC_UP);
        KEY_MAP.put("ArrowDown",  ScanCode.SC_DOWN);
        KEY_MAP.put("Control",    ScanCode.SC_LCTRL);
        KEY_MAP.put("Shift",      ScanCode.SC_LSHIFT);
        KEY_MAP.put("Alt",        ScanCode.SC_LALT);
        KEY_MAP.put("Space",      ScanCode.SC_SPACE);
        KEY_MAP.put("Enter",      ScanCode.SC_ENTER);
        KEY_MAP.put("Escape",     ScanCode.SC_ESCAPE);
        KEY_MAP.put("Tab",        ScanCode.SC_TAB);
        KEY_MAP.put("1",          ScanCode.SC_1);
        KEY_MAP.put("2",          ScanCode.SC_2);
        KEY_MAP.put("3",          ScanCode.SC_3);
        KEY_MAP.put("4",          ScanCode.SC_4);
        KEY_MAP.put("5",          ScanCode.SC_5);
        KEY_MAP.put("6",          ScanCode.SC_6);
        KEY_MAP.put("7",          ScanCode.SC_7);
        KEY_MAP.put("F1",         ScanCode.SC_F1);
        KEY_MAP.put("F2",         ScanCode.SC_F2);
        KEY_MAP.put("F3",         ScanCode.SC_F3);
        KEY_MAP.put("F10",        ScanCode.SC_F10);
        KEY_MAP.put("F11",        ScanCode.SC_F11);
        KEY_MAP.put("F12",        ScanCode.SC_F12);
    }

    public StdinKeyReader(IDoom doom) {
        this.doom = doom;
    }

    @Override
    public void run() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(FileDescriptor.in)))) {
            String line;
            while ((line = br.readLine()) != null) {
                parseLine(line);
            }
        } catch (Exception ignored) {
            // stdin closed or game exiting — normal shutdown
        }
    }

    private void parseLine(String line) {
        if (line.length() < 3 || line.charAt(1) != ' ') return;
        final char cmd = line.charAt(0);
        final String key = line.substring(2);

        final evtype_t type;
        if      (cmd == 'd') type = evtype_t.ev_keydown;
        else if (cmd == 'u') type = evtype_t.ev_keyup;
        else return;

        final ScanCode sc = KEY_MAP.get(key);
        if (sc == null) return;

        doom.PostEvent(new event_t.keyevent_t(type, sc));
    }
}
