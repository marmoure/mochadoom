/*
 * StdoutSink — synchronized singleton for all stdout packet writes.
 *
 * Video (DOOM) and audio (DOOA) packets are written from different threads.
 * All writes go through here to prevent packets from interleaving on stdout.
 */
package i;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class StdoutSink {

    private static final StdoutSink INSTANCE = new StdoutSink();

    private final OutputStream out;

    private StdoutSink() {
        out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 512 * 1024);
    }

    static StdoutSink get() {
        return INSTANCE;
    }

    synchronized void writePacket(byte[] data, int length) throws IOException {
        out.write(data, 0, length);
        out.flush();
    }
}
