package io.xpusb.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Минимальный читатель ISO9660 (+Joliet). Только чтение каталогов и файлов. */
public final class Iso9660 {
    private static final int SS = 2048;

    public static final class Node {
        public final String name;
        public final boolean dir;
        public final long extent;
        public final long size;
        public final List<Node> children = new ArrayList<>();

        Node(String name, boolean dir, long extent, long size) {
            this.name = name;
            this.dir = dir;
            this.extent = extent;
            this.size = size;
        }

        public Node child(String n) {
            for (Node c : children) if (c.name.equalsIgnoreCase(n)) return c;
            return null;
        }
    }

    private final RandomAccessSource src;
    public final Node root;
    public final boolean joliet;
    public final String volumeLabel;

    public Iso9660(RandomAccessSource src) throws IOException {
        this.src = src;
        byte[] d = new byte[SS];
        long rootExt = -1, rootLen = 0, jExt = -1, jLen = 0;
        String label = "";
        for (int i = 0; i < 64; i++) {
            long pos = (16L + i) * SS;
            if (pos + SS > src.length()) break;
            src.readFully(pos, d, 0, SS);
            int type = d[0] & 0xff;
            if (!(d[1] == 'C' && d[2] == 'D' && d[3] == '0' && d[4] == '0' && d[5] == '1')) break;
            if (type == 255) break;
            if (type == 1 && rootExt < 0) {
                rootExt = le32(d, 156 + 2);
                rootLen = le32(d, 156 + 10);
                label = new String(d, 40, 32, "ISO-8859-1").trim();
            } else if (type == 2) {
                // Joliet: escape sequences %/@ %/C %/E
                if (d[88] == '%' && d[89] == '/' && (d[90] == '@' || d[90] == 'C' || d[90] == 'E')) {
                    jExt = le32(d, 156 + 2);
                    jLen = le32(d, 156 + 10);
                }
            }
        }
        if (rootExt < 0) throw new IOException("Это не ISO9660: нет Primary Volume Descriptor");
        boolean useJ = jExt >= 0;
        joliet = useJ;
        volumeLabel = label;
        root = new Node("", true, useJ ? jExt : rootExt, useJ ? jLen : rootLen);
        readDir(root, 0);
    }

    private void readDir(Node dir, int depth) throws IOException {
        if (depth > 32) throw new IOException("Слишком глубокая вложенность каталогов ISO");
        if (dir.size > 64L * 1024 * 1024) throw new IOException("Подозрительно большой каталог ISO");
        byte[] buf = new byte[(int) dir.size];
        src.readFully(dir.extent * SS, buf, 0, buf.length);
        int pos = 0;
        while (pos < buf.length) {
            int rl = buf[pos] & 0xff;
            if (rl == 0) {
                pos = (pos / SS + 1) * SS;
                continue;
            }
            if (pos + rl > buf.length || rl < 34) throw new IOException("Повреждена запись каталога ISO");
            int flags = buf[pos + 25] & 0xff;
            int nl = buf[pos + 32] & 0xff;
            boolean special = nl == 1 && (buf[pos + 33] == 0 || buf[pos + 33] == 1);
            if (!special) {
                String name = joliet
                        ? new String(buf, pos + 33, nl, "UTF-16BE")
                        : new String(buf, pos + 33, nl, "ISO-8859-1");
                int sc = name.lastIndexOf(';');
                if (sc >= 0) name = name.substring(0, sc);
                if (!joliet && name.endsWith(".")) name = name.substring(0, name.length() - 1);
                boolean isDir = (flags & 2) != 0;
                if ((flags & 0x80) != 0)
                    throw new IOException("Файл из нескольких extent'ов не поддерживается: " + name);
                long ext = le32(buf, pos + 2);
                long size = le32(buf, pos + 10);
                if (dir.child(name) == null) {
                    Node n = new Node(name, isDir, ext, size);
                    dir.children.add(n);
                    if (isDir) readDir(n, depth + 1);
                }
            }
            pos += rl;
        }
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    /** Поиск по пути вида "I386/SETUPLDR.BIN" без учёта регистра. */
    public Node find(String path) {
        Node n = root;
        for (String p : path.split("/")) {
            if (p.isEmpty()) continue;
            n = n.child(p);
            if (n == null) return null;
        }
        return n;
    }

    public InputStream open(final Node n) {
        return new InputStream() {
            private long off = 0;
            private final byte[] buf = new byte[256 * 1024];
            private int bpos = 0, blen = 0;

            private boolean fill() throws IOException {
                if (off >= n.size) return false;
                int len = (int) Math.min(buf.length, n.size - off);
                src.readFully(n.extent * SS + off, buf, 0, len);
                off += len;
                bpos = 0;
                blen = len;
                return true;
            }

            @Override public int read() throws IOException {
                if (bpos >= blen && !fill()) return -1;
                return buf[bpos++] & 0xff;
            }

            @Override public int read(byte[] b, int o, int l) throws IOException {
                if (l == 0) return 0;
                if (bpos >= blen && !fill()) return -1;
                int c = Math.min(l, blen - bpos);
                System.arraycopy(buf, bpos, b, o, c);
                bpos += c;
                return c;
            }
        };
    }

    public byte[] readAll(Node n) throws IOException {
        if (n.size > 64L * 1024 * 1024) throw new IOException("Файл слишком большой для чтения в память: " + n.name);
        byte[] b = new byte[(int) n.size];
        src.readFully(n.extent * SS, b, 0, b.length);
        return b;
    }
}
