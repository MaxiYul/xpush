package io.xpusb.core;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

/**
 * Правки SETUPLDR.BIN / TXTSETUP.SIF для запуска установки Windows XP/2003 с USB.
 * Повторяет SetupWinPE() из Rufus (wue.c) для случая обычного каталога \I386.
 */
public final class XpPatcher {
    private XpPatcher() { }

    private static boolean matchIgnoreCase(byte[] b, int i, String s, int n) {
        if (i + n > b.length) return false;
        for (int k = 0; k < n; k++) {
            int c = b[i + k] & 0xff;
            int d = s.charAt(k);
            if (c >= 'A' && c <= 'Z') c += 32;
            if (d >= 'A' && d <= 'Z') d += 32;
            if (c != d) return false;
        }
        return true;
    }

    private static void strcpy(byte[] b, int i, String s) {
        for (int k = 0; k < s.length(); k++) b[i + k] = (byte) s.charAt(k);
        b[i + s.length()] = 0;
    }

    /** Возвращает исправленную копию setupldr.bin (будет записан как NTLDR). */
    public static byte[] patchSetupLdr(byte[] orig, String basedir, StringBuilder log) {
        byte[] b = orig.clone();
        int size = b.length;
        if (size < 0x100) throw new IllegalArgumentException("setupldr.bin слишком мал");
        String[] org = {"\\minint\\txtsetup.sif", "\\minint\\system32\\"};
        String[] rep = {"\\" + basedir + "\\txtsetup.sif", "\\" + basedir + "\\system32\\"};

        // CRC-проверка 32-битной части setupldr.bin из Win2003
        if (size > 0x2061 && (b[0x2060] & 0xff) == 0x74 && (b[0x2061] & 0xff) == 0x03) {
            b[0x2060] = (byte) 0xEB;
            b[0x2061] = 0x1A;
            log.append("  0x2060: отключена CRC-проверка Win2003\n");
        }
        for (int i = 1; i < size - 32; i++) {
            for (int j = 0; j < org.length; j++) {
                if (matchIgnoreCase(b, i, org[j], org[j].length() - 1)) {
                    log.append(String.format(Locale.ROOT, "  0x%08X: %s -> %s\n", i, org[j], rep[j]));
                    strcpy(b, i, rep[j]);
                    i += Math.max(org[j].length(), rep[j].length());
                }
            }
        }
        // rdisk(0) -> rdisk(1) (флешка видна как второй диск), $WIN_NT$.~BT -> i386
        String rdisk = "rdisk(0)";
        String winnt = "$win_nt$.~bt";
        for (int i = 0; i < size - 32; i++) {
            if (matchIgnoreCase(b, i, rdisk, rdisk.length() - 1)) {
                b[i + 6] = '1';
                log.append(String.format(Locale.ROOT, "  0x%08X: rdisk(0) -> rdisk(1)\n", i));
            }
            if (matchIgnoreCase(b, i, winnt, winnt.length() - 1)) {
                byte next = b[i + winnt.length()];
                strcpy(b, i, basedir);
                b[i + basedir.length()] = next; // сохраняем обратную косую черту
                b[i + basedir.length() + 1] = 0;
                log.append(String.format(Locale.ROOT, "  0x%08X: $WIN_NT$.~BT -> %s\n", i, basedir));
            }
        }
        return b;
    }

    /** Вставляет SetupSourceDevice сразу после строки [SetupData]. */
    public static byte[] insertSetupSourceDevice(byte[] txtsetup) {
        final String sec = "[setupdata]";
        final String data = "SetupSourceDevice = \"\\device\\harddisk1\\partition1\"";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(txtsetup.length + 100);
        int pos = 0;
        boolean done = false;
        while (pos < txtsetup.length) {
            int end = pos;
            while (end < txtsetup.length && txtsetup[end] != '\n') end++;
            int lineEnd = end < txtsetup.length ? end + 1 : end;
            out.write(txtsetup, pos, lineEnd - pos);
            int i = pos;
            while (i < lineEnd && (txtsetup[i] == ' ' || txtsetup[i] == '\t')) i++;
            if (matchIgnoreCase(txtsetup, i, sec, sec.length()) && !done) {
                if (end >= txtsetup.length) { out.write('\r'); out.write('\n'); }
                try {
                    byte[] d = data.getBytes("ISO-8859-1");
                    out.write(d, 0, d.length);
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
                out.write('\r');
                out.write('\n');
                done = true;
            }
            pos = lineEnd;
        }
        if (!done) throw new IllegalArgumentException("В TXTSETUP.SIF нет секции [SetupData]");
        return out.toByteArray();
    }
}
