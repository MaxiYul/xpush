import io.xpusb.core.*;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Проверка протокольной логики USB (BOT/SCSI): программный эмулятор флешки с внедрением сбоев
 * (Unit Attention при TEST UNIT READY и WRITE, stall при READ). Затем полная запись XP-флешки
 * через ScsiBotDevice и дамп образа для независимой проверки verify_fat32.py.
 * Запуск: java -cp out BotSim test.iso dump.img sizeMB
 */
public class BotSim {
    static class Sim implements BotTransport {
        final byte[] disk;
        int phase = 0; // 0 CBW, 1 DATA_OUT, 2 DATA_IN, 3 CSW
        int tag, status, dataLen, pos;
        long lba; int count; int op;
        byte[] inBuf; byte[] outBuf;
        boolean halted;
        // сенс
        int senseKey, asc, ascq;
        // сбои
        boolean turFailOnce = true, writeFailOnce = true, readStallOnce = true;
        int writes, reads, maxBulk, uaRetries, stalls, resets;

        Sim(int mb) { disk = new byte[mb * 1024 * 1024]; }

        void check(int len) {
            maxBulk = Math.max(maxBulk, len);
            if (len > 16384) throw new IllegalStateException("bulk > 16384: " + len);
        }

        @Override public int bulkOut(byte[] b, int off, int len, int timeoutMs) {
            check(len);
            if (phase == 0) {
                if (len != 31 || le32(b, off) != 0x43425355) throw new IllegalStateException("плохой CBW");
                tag = (int) le32(b, off + 4);
                dataLen = (int) le32(b, off + 8);
                int flags = b[off + 12] & 0xff;
                int cl = b[off + 14] & 0xff;
                op = b[off + 15] & 0xff;
                status = 0;
                switch (op) {
                    case 0x00: // TEST UNIT READY
                        if (turFailOnce) { turFailOnce = false; fail(6, 0x28, 0); uaRetries++; }
                        phase = 3; break;
                    case 0x25: // READ CAPACITY(10)
                        inBuf = new byte[8];
                        long last = disk.length / 512 - 1;
                        put32(inBuf, 0, last); put32(inBuf, 4, 512);
                        pos = 0; phase = 2; break;
                    case 0x03: // REQUEST SENSE
                        inBuf = new byte[18]; inBuf[0] = 0x70; inBuf[2] = (byte) senseKey; inBuf[12] = (byte) asc; inBuf[13] = (byte) ascq;
                        senseKey = 0; asc = 0; ascq = 0; pos = 0; phase = 2; break;
                    case 0x28: // READ(10)
                        lba = be32(b, off + 15 + 2); count = (int) ((b[off + 15 + 7] & 0xff) << 8 | (b[off + 15 + 8] & 0xff));
                        reads++;
                        inBuf = Arrays.copyOfRange(disk, (int) (lba * 512), (int) ((lba + count) * 512));
                        pos = 0; phase = 2;
                        if (readStallOnce && reads == 5) { readStallOnce = false; stallRead = true; }
                        break;
                    case 0x2A: // WRITE(10)
                        lba = be32(b, off + 15 + 2); count = (int) ((b[off + 15 + 7] & 0xff) << 8 | (b[off + 15 + 8] & 0xff));
                        writes++;
                        outBuf = new byte[count * 512]; pos = 0; phase = 1; break;
                    case 0x35: phase = 3; break;
                    default: throw new IllegalStateException("команда 0x" + Integer.toHexString(op));
                }
                if (dataLen == 0 && phase != 3 && phase != 2) phase = 3;
                if (phase == 1 && dataLen != outBuf.length) throw new IllegalStateException("длина WRITE");
                if (phase == 2 && (flags & 0x80) == 0) throw new IllegalStateException("направление");
                return len;
            }
            if (phase == 1) {
                System.arraycopy(b, off, outBuf, pos, len);
                pos += len;
                if (pos == outBuf.length) {
                    if (writeFailOnce && writes == 7) { writeFailOnce = false; fail(6, 0x29, 0); uaRetries++; }
                    else System.arraycopy(outBuf, 0, disk, (int) (lba * 512), outBuf.length);
                    phase = 3;
                }
                return len;
            }
            throw new IllegalStateException("bulkOut в фазе " + phase);
        }

        boolean stallRead;

        @Override public int bulkIn(byte[] b, int off, int len, int timeoutMs) {
            check(len);
            if (halted) return -1;
            if (phase == 2) {
                if (stallRead && pos >= 16384) { // стоп посреди передачи
                    stallRead = false; halted = true; stalls++;
                    fail(2, 0x04, 0x01); // Not Ready
                    phase = 3;
                    return -1;
                }
                int n = Math.min(len, inBuf.length - pos);
                System.arraycopy(inBuf, pos, b, off, n);
                pos += n;
                if (pos >= inBuf.length) phase = 3;
                return n;
            }
            if (phase == 3) {
                if (len != 13) throw new IllegalStateException("CSW len " + len);
                put32le(b, off, 0x53425355); put32le(b, off + 4, tag); put32le(b, off + 8, 0);
                b[off + 12] = (byte) status;
                phase = 0;
                return 13;
            }
            throw new IllegalStateException("bulkIn в фазе " + phase);
        }

        void fail(int key, int a, int q) { status = 1; senseKey = key; asc = a; ascq = q; }

        @Override public void clearHaltIn() { halted = false; }
        @Override public void clearHaltOut() { }
        @Override public void resetRecovery() { resets++; phase = 0; halted = false; }

        static long le32(byte[] b, int o) { return (b[o] & 0xffL) | (b[o+1] & 0xffL) << 8 | (b[o+2] & 0xffL) << 16 | (b[o+3] & 0xffL) << 24; }
        static long be32(byte[] b, int o) { return (b[o] & 0xffL) << 24 | (b[o+1] & 0xffL) << 16 | (b[o+2] & 0xffL) << 8 | (b[o+3] & 0xffL); }
        static void put32(byte[] b, int o, long v) { b[o] = (byte) (v >> 24); b[o+1] = (byte) (v >> 16); b[o+2] = (byte) (v >> 8); b[o+3] = (byte) v; }
        static void put32le(byte[] b, int o, long v) { b[o] = (byte) v; b[o+1] = (byte) (v >> 8); b[o+2] = (byte) (v >> 16); b[o+3] = (byte) (v >> 24); }
    }

    public static void main(String[] a) throws Exception {
        Sim sim = new Sim(Integer.parseInt(a[2]));
        ScsiBotDevice dev = new ScsiBotDevice(sim, 1);
        System.out.println("capacity sectors: " + dev.sectorCount());
        // самотест чтения/записи с кромочными размерами
        byte[] pat = new byte[300 * 512];
        for (int i = 0; i < pat.length; i++) pat[i] = (byte) (i * 31 + 7);
        dev.write(1000, pat, 0, 300);
        byte[] back = new byte[300 * 512];
        dev.read(1000, back, 0, 300);
        if (!Arrays.equals(pat, back)) throw new AssertionError("чтение/запись не совпали");
        Arrays.fill(sim.disk, (byte) 0);
        sim.readStallOnce = true; sim.writeFailOnce = true; sim.writes = 0; sim.reads = 0; sim.uaRetries = 0; sim.stalls = 0;

        RandomAccessFile f = new RandomAccessFile(a[0], "r");
        Iso9660 iso = new Iso9660(new ChannelSource(f.getChannel()));
        new XpUsbBuilder().run(dev, iso, true, new XpUsbBuilder.Listener() {
            public void stage(String t) { }
            public void progress(long d, long t) { }
            public void log(String t) { }
        });
        System.out.printf("writes=%d reads=%d maxBulk=%d unitAttention=%d stalls=%d resets=%d%n",
                sim.writes, sim.reads, sim.maxBulk, sim.uaRetries, sim.stalls, sim.resets);
        if (sim.stalls == 0 || sim.uaRetries < 1) throw new AssertionError("сбои не были внедрены");
        try (FileOutputStream o = new FileOutputStream(a[1])) { o.write(sim.disk); }
        System.out.println("BOT-эмуляция: OK");
    }
}
