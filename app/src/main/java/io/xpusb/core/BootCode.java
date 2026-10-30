package io.xpusb.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Загрузочный код из проекта Rufus / ms-sys (GPL): MBR "Rufus" и загрузочная запись
 * FAT32 для NTLDR. Лежит в resources/boot/*.bin.
 */
public final class BootCode {
    public final byte[] mbr, br52, br3f0, br1800;

    private BootCode(byte[] a, byte[] b, byte[] c, byte[] d) {
        mbr = a;
        br52 = b;
        br3f0 = c;
        br1800 = d;
    }

    public static BootCode load() throws IOException {
        return new BootCode(res("mbr_rufus.bin", 440), res("fat32nt_0x52.bin", 918),
                res("fat32nt_0x3f0.bin", 528), res("fat32nt_0x1800.bin", 512));
    }

    private static byte[] res(String n, int expect) throws IOException {
        InputStream in = BootCode.class.getClassLoader().getResourceAsStream("boot/" + n);
        if (in == null) throw new IOException("Нет ресурса boot/" + n);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            byte[] b = bo.toByteArray();
            if (b.length != expect) throw new IOException("Ресурс " + n + " повреждён: " + b.length);
            return b;
        } finally {
            in.close();
        }
    }
}
