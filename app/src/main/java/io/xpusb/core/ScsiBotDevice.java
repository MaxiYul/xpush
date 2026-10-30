package io.xpusb.core;

import java.io.IOException;

/** SCSI-команды поверх Bulk-Only Transport. Не зависит от Android. */
public final class ScsiBotDevice implements BlockDevice {
    private static final int CBW_SIG = 0x43425355;
    private static final int CSW_SIG = 0x53425355;
    public static final int BULK_CHUNK = 16384;     // безопасный размер для старых ядер Android
    private static final int MAX_SECTORS_PER_CMD = 128;
    private static final int T_CMD = 10000;
    private static final int T_DATA = 30000;

    private final BotTransport t;
    private int tag = 1;
    private int blockSize;
    private long blockCount;
    private final long readyRetryDelayMs;

    public ScsiBotDevice(BotTransport t) throws IOException {
        this(t, 500);
    }

    public ScsiBotDevice(BotTransport t, long readyRetryDelayMs) throws IOException {
        this.t = t;
        this.readyRetryDelayMs = readyRetryDelayMs;
        init();
    }

    private void init() throws IOException {
        IOException last = null;
        for (int i = 0; i < 40; i++) {
            try {
                command(new byte[]{0, 0, 0, 0, 0, 0}, null, 0, 0, false);
                last = null;
                break;
            } catch (IOException e) {
                last = e;
                sleep(readyRetryDelayMs);
            }
        }
        if (last != null) throw new IOException("Накопитель не готов: " + last.getMessage());
        byte[] cap = new byte[8];
        commandRetry(new byte[]{0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0}, cap, 0, 8, true);
        long lastLba = be32(cap, 0);
        blockSize = (int) be32(cap, 4);
        if (blockSize <= 0) throw new IOException("Накопитель вернул неверный размер блока.");
        if (lastLba == 0xFFFFFFFFL) throw new IOException("Устройства больше 2 ТБ не поддерживаются.");
        blockCount = lastLba + 1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long be32(byte[] b, int o) {
        return ((b[o] & 0xffL) << 24) | ((b[o + 1] & 0xffL) << 16) | ((b[o + 2] & 0xffL) << 8) | (b[o + 3] & 0xffL);
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    private static void putLe32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static final class ScsiException extends IOException {
        private static final long serialVersionUID = 1L;
        final int key;

        ScsiException(String m, int key) {
            super(m);
            this.key = key;
        }
    }

    private byte[] cbw(int myTag, int len, boolean in, byte[] cdb) {
        byte[] cbw = new byte[31];
        putLe32(cbw, 0, CBW_SIG);
        putLe32(cbw, 4, myTag);
        putLe32(cbw, 8, len);
        cbw[12] = (byte) (in ? 0x80 : 0);
        cbw[13] = 0;
        cbw[14] = (byte) cdb.length;
        System.arraycopy(cdb, 0, cbw, 15, cdb.length);
        return cbw;
    }

    /** Читает CSW; возвращает статус или бросает IOException. */
    private int readCsw(int myTag) throws IOException {
        byte[] csw = new byte[13];
        int r = t.bulkIn(csw, 0, 13, T_CMD);
        if (r != 13) {
            t.clearHaltIn();
            r = t.bulkIn(csw, 0, 13, T_CMD);
        }
        if (r != 13) {
            t.resetRecovery();
            throw new IOException("Накопитель не ответил (CSW).");
        }
        if (le32(csw, 0) != (CSW_SIG & 0xffffffffL) || le32(csw, 4) != (myTag & 0xffffffffL)) {
            t.resetRecovery();
            throw new IOException("Неверная подпись или метка ответа накопителя.");
        }
        int status = csw[12] & 0xff;
        if (status == 2) {
            t.resetRecovery();
            throw new IOException("Фазовая ошибка накопителя.");
        }
        return status;
    }

    private synchronized void command(byte[] cdb, byte[] data, int off, int len, boolean in) throws IOException {
        int myTag = tag++;
        byte[] cbw = cbw(myTag, len, in, cdb);
        if (t.bulkOut(cbw, 0, cbw.length, T_CMD) != cbw.length) {
            t.resetRecovery();
            throw new IOException("Ошибка отправки команды на накопитель.");
        }
        boolean stalled = false;
        int done = 0;
        while (done < len) {
            int n = Math.min(BULK_CHUNK, len - done);
            int r = in ? t.bulkIn(data, off + done, n, T_DATA) : t.bulkOut(data, off + done, n, T_DATA);
            if (r < 0) {
                stalled = true;
                if (in) t.clearHaltIn();
                else t.clearHaltOut();
                break;
            }
            done += r;
            if (in && r < n) break; // короткий пакет
            if (!in && r != n) {
                t.resetRecovery();
                throw new IOException("Накопитель принял не все данные при записи.");
            }
        }
        int status = readCsw(myTag);
        if (status != 0 || (stalled && done < len)) {
            int[] s = requestSense();
            throw new ScsiException(String.format("SCSI ошибка: ключ 0x%X, ASC 0x%02X, ASCQ 0x%02X", s[0], s[1], s[2]), s[0]);
        }
        if (done < len && in) throw new IOException("Накопитель вернул меньше данных, чем ожидалось.");
    }

    private int[] requestSense() throws IOException {
        int myTag = tag++;
        byte[] cbw = cbw(myTag, 18, true, new byte[]{0x03, 0, 0, 0, 18, 0});
        if (t.bulkOut(cbw, 0, 31, T_CMD) != 31) {
            t.resetRecovery();
            return new int[]{-1, 0, 0};
        }
        byte[] d = new byte[18];
        int r = t.bulkIn(d, 0, 18, T_CMD);
        if (r < 0) t.clearHaltIn();
        try {
            readCsw(myTag);
        } catch (IOException e) {
            return new int[]{-1, 0, 0};
        }
        if (r < 14) return new int[]{-1, 0, 0};
        return new int[]{d[2] & 0x0F, d[12] & 0xff, d[13] & 0xff};
    }

    private void commandRetry(byte[] cdb, byte[] data, int off, int len, boolean in) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                command(cdb, data, off, len, in);
                return;
            } catch (ScsiException e) {
                last = e;
                if (e.key != 6 && e.key != 2 && e.key != 0xB) break; // не Unit Attention/Not Ready/Aborted
                sleep(200);
            }
        }
        throw last;
    }

    @Override public int sectorSize() { return blockSize; }

    @Override public long sectorCount() { return blockCount; }

    private static void lbaCdb(byte[] c, long lba, int n) {
        c[2] = (byte) (lba >> 24);
        c[3] = (byte) (lba >> 16);
        c[4] = (byte) (lba >> 8);
        c[5] = (byte) lba;
        c[7] = (byte) (n >> 8);
        c[8] = (byte) n;
    }

    @Override public void read(long lba, byte[] buf, int off, int sectors) throws IOException {
        while (sectors > 0) {
            int n = Math.min(sectors, MAX_SECTORS_PER_CMD);
            byte[] cdb = new byte[10];
            cdb[0] = 0x28;
            lbaCdb(cdb, lba, n);
            commandRetry(cdb, buf, off, n * blockSize, true);
            lba += n;
            off += n * blockSize;
            sectors -= n;
        }
    }

    @Override public void write(long lba, byte[] buf, int off, int sectors) throws IOException {
        if (lba < 0 || lba + sectors > blockCount) throw new IOException("Запись за пределами устройства: " + lba);
        while (sectors > 0) {
            int n = Math.min(sectors, MAX_SECTORS_PER_CMD);
            byte[] cdb = new byte[10];
            cdb[0] = 0x2A;
            lbaCdb(cdb, lba, n);
            commandRetry(cdb, buf, off, n * blockSize, false);
            lba += n;
            off += n * blockSize;
            sectors -= n;
        }
    }

    @Override public void flush() {
        try {
            command(new byte[]{0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0}, null, 0, 0, false);
        } catch (IOException ignored) {
            // не все накопители поддерживают SYNCHRONIZE CACHE
        }
    }
}
