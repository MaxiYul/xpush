package io.xpusb.core;

import java.io.IOException;

/** Блочное устройство (USB-флешка или файл-образ при тестировании). */
public interface BlockDevice {
    int sectorSize();

    long sectorCount();

    void read(long lba, byte[] buf, int off, int sectors) throws IOException;

    void write(long lba, byte[] buf, int off, int sectors) throws IOException;

    void flush() throws IOException;
}
