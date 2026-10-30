package io.xpusb.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Узел виртуального дерева файлов, которое будет записано на FAT32. */
public final class VNode {
    public interface Content {
        InputStream open() throws IOException;
    }

    public String name;
    public final boolean dir;
    public long size;
    public Content content;
    public final List<VNode> children = new ArrayList<>();

    // заполняется Fat32.plan()
    int firstCluster;
    int clusters;
    byte[] short11;
    byte[] lfnEntries; // уже готовые LFN-записи (может быть null)
    byte[] image;      // содержимое каталога

    public VNode(String name, boolean dir) {
        this.name = name;
        this.dir = dir;
    }

    public static VNode file(String name, final byte[] data) {
        VNode n = new VNode(name, false);
        n.size = data.length;
        n.content = new Content() {
            @Override public InputStream open() {
                return new java.io.ByteArrayInputStream(data);
            }
        };
        return n;
    }

    public VNode child(String n) {
        for (VNode c : children) if (c.name.equalsIgnoreCase(n)) return c;
        return null;
    }

    /** Создаёт (или возвращает существующий) подкаталог. */
    public VNode mkdir(String n) {
        VNode c = child(n);
        if (c == null) {
            c = new VNode(n, true);
            children.add(c);
        }
        return c;
    }

    /** Добавляет файл, заменяя существующий с тем же именем. */
    public void put(VNode f) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).name.equalsIgnoreCase(f.name)) {
                children.set(i, f);
                return;
            }
        }
        children.add(f);
    }
}
