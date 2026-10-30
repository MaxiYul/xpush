package io.xpusb.core;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.zip.CRC32;

/**
 * Создаёт загрузочную флешку для установки Windows XP/2003 (BIOS/Legacy) из ISO:
 * MBR (Rufus) + FAT32 + загрузочная запись NTLDR + файлы ISO + правки SETUPLDR.BIN.
 */
public final class XpUsbBuilder {
    public interface Listener {
        void stage(String text);

        void progress(long done, long total);

        void log(String text);
    }

    public static final class CancelledException extends IOException {
        public CancelledException() { super("Отменено пользователем"); }
    }

    private static final long MAX_PART_SECTORS = 32L * 1024 * 1024 * 1024 / 512;
    private static final int CHUNK_SECTORS = 2048; // 1 МиБ

    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    private void check() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    /** Проверка, что ISO похож на установочный диск XP/2003. Возвращает текст ошибки или null. */
    public static String validateIso(Iso9660 iso) {
        Iso9660.Node i386 = iso.find("I386");
        if (i386 == null || !i386.dir) return "В ISO нет каталога I386 — это не установочный диск Windows XP/2003.";
        for (String f : new String[]{"SETUPLDR.BIN", "NTDETECT.COM", "TXTSETUP.SIF"})
            if (iso.find("I386/" + f) == null) return "В I386 нет файла " + f + ".";
        return null;
    }

    private static VNode copyTree(Iso9660 iso, Iso9660.Node n, String name) {
        VNode v = new VNode(name, n.dir);
        if (n.dir) {
            for (Iso9660.Node c : n.children) v.children.add(copyTree(iso, c, c.name));
        } else {
            final Iso9660.Node fn = n;
            final Iso9660 fiso = iso;
            v.size = n.size;
            v.content = new VNode.Content() {
                @Override public InputStream open() { return fiso.open(fn); }
            };
        }
        return v;
    }

    public void run(BlockDevice dev, Iso9660 iso, boolean verify, Listener l) throws IOException {
        cancelled = false;
        if (dev.sectorSize() != 512)
            throw new IOException("Поддерживаются только устройства с секторами 512 байт (у этого " + dev.sectorSize() + ").");
        String err = validateIso(iso);
        if (err != null) throw new IOException(err);
        BootCode bc = BootCode.load();

        // ---- дерево файлов
        l.stage("Подготовка файлов");
        VNode root = copyTree(iso, iso.root, "");
        StringBuilder plog = new StringBuilder();
        byte[] ldr = XpPatcher.patchSetupLdr(iso.readAll(iso.find("I386/SETUPLDR.BIN")), "i386", plog);
        byte[] txt = XpPatcher.insertSetupSourceDevice(iso.readAll(iso.find("I386/TXTSETUP.SIF")));
        byte[] det = iso.readAll(iso.find("I386/NTDETECT.COM"));
        l.log("Правки SETUPLDR.BIN:\n" + plog);
        root.put(VNode.file("NTLDR", ldr));
        root.put(VNode.file("NTDETECT.COM", det));
        root.put(VNode.file("TXTSETUP.SIF", txt));

        // ---- геометрия
        long total = dev.sectorCount();
        long partStart = 2048;
        long avail = total - partStart - 64;
        if (avail < 65525L * 2) throw new IOException("Флешка слишком мала.");
        long partSectors = Math.min(avail, MAX_PART_SECTORS) & ~7L;
        String label = "WINXP";
        int diskSig = new SecureRandom().nextInt();
        long volId = new SecureRandom().nextInt() & 0xFFFFFFFFL;
        Fat32 fat = Fat32.plan(partStart, partSectors, root, label, volId);
        l.log(String.format("Раздел: старт LBA %d, %d МБ, кластер %d КБ, использовано %d МБ",
                partStart, partSectors / 2048, fat.clusterBytes / 1024, fat.usedBytes() >> 20));

        long fileBytes = 0;
        for (VNode n : fat.dataOrder) if (!n.dir) fileBytes += n.size;
        long totalWork = fileBytes * (verify ? 2 : 1) + 1;
        final long[] done = {0};

        // ---- очистка начала и конца
        l.stage("Очистка таблицы разделов");
        zero(dev, 0, partStart);
        long tail = Math.min(64, total - (partStart + partSectors));
        if (tail > 0) zero(dev, total - tail, tail);
        check();

        // ---- резерв и загрузочная запись FAT32
        l.stage("Форматирование FAT32");
        byte[] resImg = fat.reservedImage(bc.br52, bc.br3f0, bc.br1800);
        writeChunked(dev, partStart, resImg);
        check();
        for (int copy = 0; copy < 2; copy++) {
            long base = partStart + fat.reserved + (long) copy * fat.fatSectors;
            for (int s = 0; s < fat.fatSectors; s += CHUNK_SECTORS) {
                int n = Math.min(CHUNK_SECTORS, fat.fatSectors - s);
                byte[] b = fat.fatChunk(s, n);
                dev.write(base + s, b, 0, n);
                check();
            }
        }

        // ---- данные
        l.stage("Копирование файлов");
        long dataLba = partStart + fat.dataStart;
        SeqWriter w = new SeqWriter(dev, dataLba);
        CRC32 crc = new CRC32();
        byte[] buf = new byte[256 * 1024];
        byte[] zeros = new byte[fat.clusterBytes];
        for (VNode n : fat.dataOrder) {
            check();
            if (n.dir) {
                w.write(n.image, 0, n.image.length);
                crc.update(n.image, 0, n.image.length);
                continue;
            }
            if (n.size == 0) continue;
            long left = n.size;
            InputStream in = n.content.open();
            try {
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r <= 0) throw new IOException("Неожиданный конец файла " + n.name);
                    w.write(buf, 0, r);
                    crc.update(buf, 0, r);
                    left -= r;
                    done[0] += r;
                    l.progress(done[0], totalWork);
                    check();
                }
            } finally {
                in.close();
            }
            int pad = (int) (n.clusters * (long) fat.clusterBytes - n.size);
            if (pad > 0) {
                w.write(zeros, 0, pad);
                crc.update(zeros, 0, pad);
            }
        }
        w.flush();
        long streamSectors = fat.dataSectorsUsed();
        if (w.sectorsWritten() != streamSectors)
            throw new IllegalStateException("Внутренняя ошибка: записано " + w.sectorsWritten() + " секторов вместо " + streamSectors);

        // ---- MBR последним: до завершения флешка не загрузочная
        l.stage("Запись MBR");
        byte[] mbr = Fat32.makeMbr(bc.mbr, partStart, partSectors, diskSig, 0x81);
        dev.write(0, mbr, 0, 1);
        l.stage("Сброс кэша устройства");
        dev.flush();

        // ---- проверка
        if (verify) {
            l.stage("Проверка записанных данных");
            byte[] chk = new byte[CHUNK_SECTORS * 512];
            CRC32 c2 = new CRC32();
            long remain = streamSectors, lba = dataLba;
            long perSector = Math.max(1, fileBytes / Math.max(1, streamSectors));
            while (remain > 0) {
                int n = (int) Math.min(CHUNK_SECTORS, remain);
                dev.read(lba, chk, 0, n);
                c2.update(chk, 0, n * 512);
                lba += n;
                remain -= n;
                done[0] += (long) n * perSector;
                l.progress(Math.min(done[0], totalWork - 1), totalWork);
                check();
            }
            if (c2.getValue() != crc.getValue())
                throw new IOException("Проверка не прошла: данные на флешке отличаются от записанных (контрольная сумма).");
            byte[] back = new byte[16 * 512];
            dev.read(partStart, back, 0, 16);
            for (int i = 0; i < 3 * 512; i++)
                if (back[i] != resImg[i]) throw new IOException("Проверка не прошла: загрузочный сектор повреждён.");
            for (int i = 12 * 512; i < 13 * 512; i++)
                if (back[i] != resImg[i]) throw new IOException("Проверка не прошла: второй загрузочный сектор повреждён.");
            byte[] m2 = new byte[512];
            dev.read(0, m2, 0, 1);
            for (int i = 0; i < 512; i++)
                if (m2[i] != mbr[i]) throw new IOException("Проверка не прошла: MBR повреждён.");
        }
        l.progress(totalWork, totalWork);
        l.stage("Готово");
    }

    private void zero(BlockDevice dev, long lba, long sectors) throws IOException {
        byte[] z = new byte[(int) Math.min(sectors, CHUNK_SECTORS) * 512];
        while (sectors > 0) {
            int n = (int) Math.min(sectors, CHUNK_SECTORS);
            dev.write(lba, z, 0, n);
            lba += n;
            sectors -= n;
            check();
        }
    }

    private void writeChunked(BlockDevice dev, long lba, byte[] img) throws IOException {
        int sectors = img.length / 512;
        for (int s = 0; s < sectors; s += CHUNK_SECTORS) {
            int n = Math.min(CHUNK_SECTORS, sectors - s);
            dev.write(lba + s, img, s * 512, n);
        }
    }

    /** Последовательная запись через буфер 1 МиБ. */
    private static final class SeqWriter {
        private final BlockDevice dev;
        private final byte[] buf = new byte[CHUNK_SECTORS * 512];
        private int fill;
        private long lba;
        private long written;

        SeqWriter(BlockDevice dev, long lba) {
            this.dev = dev;
            this.lba = lba;
        }

        void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int c = Math.min(len, buf.length - fill);
                System.arraycopy(b, off, buf, fill, c);
                fill += c;
                off += c;
                len -= c;
                if (fill == buf.length) flushFull();
            }
        }

        private void flushFull() throws IOException {
            dev.write(lba, buf, 0, CHUNK_SECTORS);
            lba += CHUNK_SECTORS;
            written += CHUNK_SECTORS;
            fill = 0;
        }

        void flush() throws IOException {
            if (fill == 0) return;
            if (fill % 512 != 0) throw new IllegalStateException("поток не кратен сектору");
            int n = fill / 512;
            dev.write(lba, buf, 0, n);
            lba += n;
            written += n;
            fill = 0;
        }

        long sectorsWritten() { return written; }
    }
}
