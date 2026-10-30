package io.xpusb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;

import io.xpusb.core.BlockDevice;
import io.xpusb.core.BotTransport;
import io.xpusb.core.ScsiBotDevice;

/**
 * USB Mass Storage через Android USB Host API (без root). Протокол — в {@link ScsiBotDevice}.
 */
public final class UsbMassStorage implements BlockDevice, Closeable {
    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private UsbEndpoint epIn, epOut;
    private ScsiBotDevice dev;
    private boolean closed;

    public static boolean isMassStorage(UsbDevice d) {
        return findInterface(d) != null;
    }

    private static UsbInterface findInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface in = d.getInterface(i);
            if (in.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                    && in.getInterfaceSubclass() == 6 && in.getInterfaceProtocol() == 0x50) return in;
        }
        return null;
    }

    public UsbMassStorage(UsbManager mgr, UsbDevice d) throws IOException {
        iface = findInterface(d);
        if (iface == null) throw new IOException("Это не USB-накопитель (нет интерфейса Bulk-Only).");
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint e = iface.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) epIn = e;
            else epOut = e;
        }
        if (epIn == null || epOut == null) throw new IOException("Не найдены bulk-эндпоинты накопителя.");
        conn = mgr.openDevice(d);
        if (conn == null) throw new IOException("Не удалось открыть USB-устройство (нет разрешения?).");
        try {
            if (!conn.claimInterface(iface, true)) throw new IOException("Не удалось захватить USB-интерфейс.");
            dev = new ScsiBotDevice(new BotTransport() {
                @Override public int bulkOut(byte[] b, int off, int len, int timeoutMs) {
                    return conn.bulkTransfer(epOut, b, off, len, timeoutMs);
                }

                @Override public int bulkIn(byte[] b, int off, int len, int timeoutMs) {
                    return conn.bulkTransfer(epIn, b, off, len, timeoutMs);
                }

                @Override public void clearHaltIn() { clearHalt(epIn); }

                @Override public void clearHaltOut() { clearHalt(epOut); }

                @Override public void resetRecovery() {
                    conn.controlTransfer(0x21, 0xFF, 0, iface.getId(), null, 0, 5000);
                    clearHalt(epIn);
                    clearHalt(epOut);
                }
            });
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void clearHalt(UsbEndpoint e) {
        conn.controlTransfer(0x02, 0x01, 0, e.getAddress(), null, 0, 5000);
    }

    @Override public int sectorSize() { return dev.sectorSize(); }

    @Override public long sectorCount() { return dev.sectorCount(); }

    @Override public void read(long lba, byte[] buf, int off, int sectors) throws IOException {
        dev.read(lba, buf, off, sectors);
    }

    @Override public void write(long lba, byte[] buf, int off, int sectors) throws IOException {
        dev.write(lba, buf, off, sectors);
    }

    @Override public void flush() throws IOException { dev.flush(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            conn.releaseInterface(iface);
        } catch (RuntimeException ignored) {
            // ignore
        }
        conn.close();
    }
}
