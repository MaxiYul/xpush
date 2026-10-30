package io.xpusb.core;

import java.io.IOException;

/** Источник с произвольным доступом (ISO-файл). */
public interface RandomAccessSource {
    long length() throws IOException;

    void readFully(long pos, byte[] buf, int off, int len) throws IOException;
}
