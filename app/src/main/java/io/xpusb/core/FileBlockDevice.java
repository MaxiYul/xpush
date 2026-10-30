package io.xpusb.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Блочное устройство поверх файла (для проверки на ПК). */
public final class FileBlockDevice implements BlockDevice, Closeable {
    private final RandomAccessFile raf;
    private final long sectors;

    public FileBlockDevice(String path, long sizeBytes) throws IOException {
        raf = new RandomAccessFile(path, "rw");
        raf.setLength(sizeBytes);
        sectors = sizeBytes / 512;
    }

    @Override public int sectorSize() { return 512; }

    @Override public long sectorCount() { return sectors; }

    @Override public synchronized void read(long lba, byte[] buf, int off, int n) throws IOException {
        raf.seek(lba * 512);
        raf.readFully(buf, off, n * 512);
    }

    @Override public synchronized void write(long lba, byte[] buf, int off, int n) throws IOException {
        if (lba < 0 || lba + n > sectors) throw new IOException("write beyond end: " + lba);
        raf.seek(lba * 512);
        raf.write(buf, off, n * 512);
    }

    @Override public void flush() { }

    @Override public void close() throws IOException { raf.close(); }
}
