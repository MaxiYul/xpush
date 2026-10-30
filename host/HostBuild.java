import io.xpusb.core.*;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/** Проверка на ПК: java -cp out HostBuild in.iso out.img sizeMB [noverify] */
public class HostBuild {
    public static void main(String[] a) throws Exception {
        RandomAccessFile f = new RandomAccessFile(a[0], "r");
        Iso9660 iso = new Iso9660(new ChannelSource(f.getChannel()));
        System.out.println("Joliet: " + iso.joliet + ", label: " + iso.volumeLabel);
        FileBlockDevice dev = new FileBlockDevice(a[1], Long.parseLong(a[2]) * 1024 * 1024);
        boolean verify = a.length < 4;
        new XpUsbBuilder().run(dev, iso, verify, new XpUsbBuilder.Listener() {
            long last;
            public void stage(String t) { System.out.println("[stage] " + t); }
            public void progress(long d, long t) {
                long now = System.currentTimeMillis();
                if (now - last > 1500 || d == t) { last = now; System.out.printf("  %d%%%n", d * 100 / t); }
            }
            public void log(String t) { System.out.println(t); }
        });
        dev.close();
    }
}
