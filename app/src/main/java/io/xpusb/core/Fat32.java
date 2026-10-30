package io.xpusb.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Планирование и построение тома FAT32: параметры, BPB, FAT, каталоги (с LFN).
 * Файлы размещаются подряд (без фрагментации): сначала все каталоги, затем файлы.
 */
public final class Fat32 {
    public static final int SS = 512;

    public final long partStart;      // LBA начала раздела
    public final long partSectors;
    public final int spc;             // секторов в кластере
    public final int reserved;
    public final int fatSectors;
    public final long clusterCount;   // число кластеров данных
    public final long dataStart;      // сектор начала данных относительно раздела
    public final int clusterBytes;
    public final long volId;
    public final String label;

    public final VNode root;
    /** Порядок записи области данных: каталоги, затем файлы. */
    public final List<VNode> dataOrder = new ArrayList<>();
    private int[] fat;
    private long usedClusters;
    private int nextFree;

    private Fat32(long partStart, long partSectors, VNode root, String label, long volId) throws IOException {
        this.partStart = partStart;
        this.partSectors = partSectors;
        this.root = root;
        this.volId = volId;
        this.label = label;

        int c = partSectors <= 16777216L ? 8 : (partSectors <= 33554432L ? 16 : 32);
        long fs, cl;
        int res = 32;
        while (true) {
            fs = 1;
            for (int it = 0; it < 20; it++) {
                cl = (partSectors - res - 2 * fs) / c;
                long nf = ((cl + 2) * 4 + SS - 1) / SS;
                if (nf == fs) break;
                fs = nf;
            }
            // выравнивание начала данных на 1 МиБ
            long pad = (2048 - ((partStart + res + 2 * fs) % 2048)) % 2048;
            int res2 = (int) (res + pad);
            cl = (partSectors - res2 - 2 * fs) / c;
            if (cl >= 65525 || c == 1) {
                res = res2;
                break;
            }
            c /= 2;
        }
        cl = (partSectors - res - 2 * fs) / c;
        if (cl < 65525) throw new IOException("Раздел слишком мал для FAT32");
        if (cl > 0x0FFFFFF0L) throw new IOException("Раздел слишком велик для FAT32");
        this.spc = c;
        this.reserved = res;
        this.fatSectors = (int) fs;
        this.clusterCount = cl;
        this.dataStart = res + 2 * fs;
        this.clusterBytes = c * SS;
    }

    public static Fat32 plan(long partStart, long partSectors, VNode root, String label, long volId) throws IOException {
        Fat32 f = new Fat32(partStart, partSectors, root, label, volId);
        f.allocate();
        return f;
    }

    // ---------------------------------------------------------------- размещение

    private void allocate() throws IOException {
        // 1. обход: имена и список каталогов (BFS)
        List<VNode> dirs = new ArrayList<>();
        List<VNode> files = new ArrayList<>();
        dirs.add(root);
        for (int i = 0; i < dirs.size(); i++) {
            VNode d = dirs.get(i);
            makeNames(d);
            for (VNode ch : d.children) {
                if (ch.dir) dirs.add(ch);
                else files.add(ch);
            }
        }
        // 2. размеры каталогов и раскладка кластеров
        int next = 2;
        long need = 0;
        for (VNode d : dirs) {
            int entries = (d == root ? 1 : 2);
            for (VNode ch : d.children) entries += 1 + (ch.lfnEntries == null ? 0 : ch.lfnEntries.length / 32);
            if (entries > 65534) throw new IOException("Слишком много записей в каталоге " + d.name);
            d.clusters = Math.max(1, (entries * 32 + clusterBytes - 1) / clusterBytes);
            d.firstCluster = next;
            next += d.clusters;
            dataOrder.add(d);
        }
        for (VNode f : files) {
            if (f.size >= 0xFFFFFFFFL) throw new IOException("Файл больше 4 ГБ не помещается в FAT32: " + f.name);
            f.clusters = (int) ((f.size + clusterBytes - 1) / clusterBytes);
            f.firstCluster = f.clusters == 0 ? 0 : next;
            next += f.clusters;
            dataOrder.add(f);
        }
        usedClusters = next - 2;
        if (usedClusters > clusterCount)
            throw new IOException("Недостаточно места: нужно " + (usedClusters * clusterBytes >> 20)
                    + " МБ, доступно " + (clusterCount * clusterBytes >> 20) + " МБ");
        nextFree = next;
        // 3. FAT
        fat = new int[(int) (clusterCount + 2)];
        fat[0] = 0x0FFFFFF8;
        fat[1] = 0x0FFFFFFF;
        for (VNode n : dataOrder) {
            for (int i = 0; i < n.clusters; i++) {
                fat[n.firstCluster + i] = (i == n.clusters - 1) ? 0x0FFFFFFF : n.firstCluster + i + 1;
            }
        }
        // 4. образы каталогов
        long now = System.currentTimeMillis();
        for (VNode d : dirs) d.image = buildDir(d, now);
    }

    public long usedBytes() { return usedClusters * clusterBytes; }

    /** Длина области данных в секторах (каталоги + файлы, выровнено по кластеру). */
    public long dataSectorsUsed() { return usedClusters * spc; }

    // ---------------------------------------------------------------- имена

    private static final String SHORT_OK = "$%'-_@~`!(){}^#&";

    private static boolean okShortChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || SHORT_OK.indexOf(c) >= 0;
    }

    private static boolean isPlain83(String n) {
        int dot = n.lastIndexOf('.');
        String b = dot < 0 ? n : n.substring(0, dot);
        String e = dot < 0 ? "" : n.substring(dot + 1);
        if (b.isEmpty() || b.length() > 8 || e.length() > 3) return false;
        if (n.indexOf('.') != dot) return false;
        for (char c : b.toCharArray()) if (!okShortChar(c)) return false;
        for (char c : e.toCharArray()) if (!okShortChar(c)) return false;
        return true;
    }

    private static byte[] pack11(String b, String e) {
        byte[] r = new byte[11];
        java.util.Arrays.fill(r, (byte) ' ');
        for (int i = 0; i < b.length() && i < 8; i++) r[i] = (byte) b.charAt(i);
        for (int i = 0; i < e.length() && i < 3; i++) r[8 + i] = (byte) e.charAt(i);
        return r;
    }

    private void makeNames(VNode dir) throws IOException {
        Set<String> used = new HashSet<>();
        // сначала простые 8.3-имена, чтобы алиасы LFN не заняли их место
        for (VNode ch : dir.children) {
            if (isPlain83(ch.name)) {
                int dot = ch.name.lastIndexOf('.');
                ch.short11 = pack11(dot < 0 ? ch.name : ch.name.substring(0, dot), dot < 0 ? "" : ch.name.substring(dot + 1));
                ch.lfnEntries = null;
                if (!used.add(new String(ch.short11, "ISO-8859-1")))
                    throw new IOException("Дублирующееся имя: " + ch.name);
            }
        }
        for (VNode ch : dir.children) {
            if (ch.short11 != null && ch.lfnEntries == null && isPlain83(ch.name)) continue;
            String name = ch.name;
            if (name.length() > 255) throw new IOException("Слишком длинное имя: " + name);
            // базовое имя и расширение для алиаса
            int dot = name.lastIndexOf('.');
            String b = dot <= 0 ? name : name.substring(0, dot);
            String e = dot <= 0 ? "" : name.substring(dot + 1);
            b = cleanAlias(b);
            e = cleanAlias(e);
            if (b.isEmpty()) b = "_";
            if (e.length() > 3) e = e.substring(0, 3);
            byte[] s11 = null;
            for (int n = 1; n < 1000000; n++) {
                String tail = "~" + n;
                int keep = Math.min(b.length(), 8 - tail.length());
                String cand = b.substring(0, keep) + tail;
                byte[] p = pack11(cand, e);
                if (used.add(new String(p, "ISO-8859-1"))) {
                    s11 = p;
                    break;
                }
            }
            if (s11 == null) throw new IOException("Не удалось создать короткое имя для " + name);
            ch.short11 = s11;
            ch.lfnEntries = lfn(name, s11);
        }
    }

    private static String cleanAlias(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == ' ' || c == '.') continue;
            sb.append(okShortChar(c) ? c : '_');
        }
        return sb.toString();
    }

    private static int checksum(byte[] s11) {
        int sum = 0;
        for (byte b : s11) sum = (((sum & 1) << 7) + (sum >> 1) + (b & 0xff)) & 0xff;
        return sum;
    }

    private static byte[] lfn(String name, byte[] s11) {
        int n = name.length();
        int cnt = (n + 12) / 13;
        byte[] out = new byte[cnt * 32];
        int ck = checksum(s11);
        for (int i = 0; i < cnt; i++) {
            int seq = cnt - i; // первая в потоке - старшая
            byte[] e = new byte[32];
            e[0] = (byte) (seq | (i == 0 ? 0x40 : 0));
            e[11] = 0x0F;
            e[13] = (byte) ck;
            int base = (seq - 1) * 13;
            int[] offs = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
            for (int k = 0; k < 13; k++) {
                int idx = base + k;
                int ch;
                if (idx < n) ch = name.charAt(idx);
                else if (idx == n) ch = 0;
                else ch = 0xFFFF;
                e[offs[k]] = (byte) ch;
                e[offs[k] + 1] = (byte) (ch >> 8);
            }
            System.arraycopy(e, 0, out, i * 32, 32);
        }
        return out;
    }

    // ---------------------------------------------------------------- каталоги

    private static int dosDate(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int y = Math.max(1980, c.get(Calendar.YEAR)) - 1980;
        return (y << 9) | ((c.get(Calendar.MONTH) + 1) << 5) | c.get(Calendar.DAY_OF_MONTH);
    }

    private static int dosTime(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5) | (c.get(Calendar.SECOND) / 2);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static byte[] shortEntry(byte[] n11, int attr, int cluster, long size, long now) {
        byte[] e = new byte[32];
        System.arraycopy(n11, 0, e, 0, 11);
        e[11] = (byte) attr;
        int d = dosDate(now), t = dosTime(now);
        put16(e, 14, t);
        put16(e, 16, d);
        put16(e, 18, d);
        put16(e, 20, cluster >>> 16);
        put16(e, 22, t);
        put16(e, 24, d);
        put16(e, 26, cluster & 0xffff);
        put32(e, 28, size);
        return e;
    }

    private byte[] buildDir(VNode d, long now) {
        byte[] img = new byte[d.clusters * clusterBytes];
        int pos = 0;
        if (d == root) {
            byte[] lab = pack11(label.toUpperCase(java.util.Locale.ROOT), "");
            byte[] e = shortEntry(lab, 0x08, 0, 0, now);
            System.arraycopy(e, 0, img, pos, 32);
            pos += 32;
        } else {
            // найти родителя: он передаётся через поле image-less обход — используем сохранённую ссылку
            VNode parent = parentOf(d);
            byte[] dot = pack11(".", "");
            byte[] dd = pack11("..", "");
            // pack11 с '.'-символами: имя "." -> '.' + пробелы; ".." -> '..' + пробелы
            dot = new byte[11];
            dd = new byte[11];
            java.util.Arrays.fill(dot, (byte) ' ');
            java.util.Arrays.fill(dd, (byte) ' ');
            dot[0] = '.';
            dd[0] = '.';
            dd[1] = '.';
            byte[] e1 = shortEntry(dot, 0x10, d.firstCluster, 0, now);
            byte[] e2 = shortEntry(dd, 0x10, parent == root ? 0 : parent.firstCluster, 0, now);
            System.arraycopy(e1, 0, img, pos, 32);
            pos += 32;
            System.arraycopy(e2, 0, img, pos, 32);
            pos += 32;
        }
        for (VNode ch : d.children) {
            if (ch.lfnEntries != null) {
                System.arraycopy(ch.lfnEntries, 0, img, pos, ch.lfnEntries.length);
                pos += ch.lfnEntries.length;
            }
            byte[] e = shortEntry(ch.short11, ch.dir ? 0x10 : 0x20, ch.firstCluster, ch.dir ? 0 : ch.size, now);
            System.arraycopy(e, 0, img, pos, 32);
            pos += 32;
        }
        return img;
    }

    private VNode parentOf(VNode target) {
        List<VNode> q = new ArrayList<>();
        q.add(root);
        for (int i = 0; i < q.size(); i++) {
            VNode d = q.get(i);
            for (VNode c : d.children) {
                if (c == target) return d;
                if (c.dir) q.add(c);
            }
        }
        throw new IllegalStateException("parent not found");
    }

    // ---------------------------------------------------------------- служебные структуры

    /** Образ первых секторов раздела (загрузочные + FSInfo + резерв), reserved секторов. */
    public byte[] reservedImage(byte[] nt52, byte[] nt3f0, byte[] nt1800) {
        byte[] img = new byte[reserved * SS];
        // загрузочный код (структура секторов 0..2 и 12 такая же, как у ms-sys)
        System.arraycopy(new byte[]{(byte) 0xEB, 0x58, (byte) 0x90, 'M', 'S', 'W', 'I', 'N', '4', '.', '1'}, 0, img, 0, 11);
        System.arraycopy(nt52, 0, img, 0x52, nt52.length);
        System.arraycopy(nt3f0, 0, img, 0x3f0, nt3f0.length);
        System.arraycopy(nt1800, 0, img, 0x1800, nt1800.length);
        // BPB
        put16(img, 0x0b, SS);
        img[0x0d] = (byte) spc;
        put16(img, 0x0e, reserved);
        img[0x10] = 2;
        put16(img, 0x11, 0);
        put16(img, 0x13, 0);
        img[0x15] = (byte) 0xF8;
        put16(img, 0x16, 0);
        put16(img, 0x18, 63);
        put16(img, 0x1a, 255);
        put32(img, 0x1c, partStart);
        put32(img, 0x20, partSectors);
        put32(img, 0x24, fatSectors);
        put16(img, 0x28, 0);
        put16(img, 0x2a, 0);
        put32(img, 0x2c, 2);
        put16(img, 0x30, 1);
        put16(img, 0x32, 6);
        img[0x40] = (byte) 0x80;
        img[0x41] = 0;
        img[0x42] = 0x29;
        put32(img, 0x43, volId);
        byte[] lab = pack11(label.toUpperCase(java.util.Locale.ROOT), "");
        System.arraycopy(lab, 0, img, 0x47, 11);
        // FSInfo: свободные кластеры / следующий свободный
        put32(img, 0x200 + 0x1e8, clusterCount - usedClusters);
        put32(img, 0x200 + 0x1ec, nextFree);
        // резервные копии секторов 0..2 в секторах 6..8
        System.arraycopy(img, 0, img, 6 * SS, 3 * SS);
        return img;
    }

    /** Содержимое одной копии FAT, по кусочкам (для записи на устройство). */
    public byte[] fatChunk(int startSector, int sectors) {
        byte[] b = new byte[sectors * SS];
        int first = startSector * 128;
        for (int i = 0; i < sectors * 128; i++) {
            int idx = first + i;
            if (idx >= fat.length) break;
            put32(b, i * 4, fat[idx] & 0xFFFFFFFFL);
        }
        return b;
    }

    public static byte[] makeMbr(byte[] mbrCode, long partStart, long partSectors, int diskSig, int bootFlag) {
        byte[] m = new byte[512];
        System.arraycopy(mbrCode, 0, m, 0, Math.min(440, mbrCode.length));
        put32(m, 440, diskSig & 0xFFFFFFFFL);
        int o = 446;
        m[o] = (byte) bootFlag;
        chs(m, o + 1, partStart);
        m[o + 4] = 0x0C;
        chs(m, o + 5, partStart + partSectors - 1);
        put32(m, o + 8, partStart);
        put32(m, o + 12, partSectors);
        m[510] = 0x55;
        m[511] = (byte) 0xAA;
        return m;
    }

    private static void chs(byte[] b, int o, long lba) {
        long c = lba / (255 * 63);
        int h = (int) ((lba / 63) % 255);
        int s = (int) (lba % 63) + 1;
        if (c > 1023) {
            c = 1023;
            h = 254;
            s = 63;
        }
        b[o] = (byte) h;
        b[o + 1] = (byte) (((c >> 8) & 3) << 6 | s);
        b[o + 2] = (byte) c;
    }
}
