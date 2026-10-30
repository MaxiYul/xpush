package io.xpusb.core;

/** Транспорт USB Bulk-Only: тонкая обёртка над UsbDeviceConnection (или эмулятором при тестах). */
public interface BotTransport {
    /** Возвращает число переданных байт или отрицательное значение при ошибке. */
    int bulkOut(byte[] b, int off, int len, int timeoutMs);

    int bulkIn(byte[] b, int off, int len, int timeoutMs);

    void clearHaltIn();

    void clearHaltOut();

    /** Bulk-Only Mass Storage Reset + снятие halt с обоих эндпоинтов. */
    void resetRecovery();
}
