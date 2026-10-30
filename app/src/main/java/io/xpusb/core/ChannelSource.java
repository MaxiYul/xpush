package io.xpusb.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** RandomAccessSource поверх FileChannel (подходит для fd из ContentResolver). */
public final class ChannelSource implements RandomAccessSource {
    private final FileChannel ch;

    public ChannelSource(FileChannel ch) { this.ch = ch; }

    @Override public long length() throws IOException { return ch.size(); }

    @Override public void readFully(long pos, byte[] buf, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        long p = pos;
        while (bb.hasRemaining()) {
            int r = ch.read(bb, p);
            if (r < 0) throw new IOException("Неожиданный конец ISO на позиции " + p);
            p += r;
        }
    }
}
