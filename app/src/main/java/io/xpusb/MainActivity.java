package io.xpusb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.xpusb.core.ChannelSource;
import io.xpusb.core.Iso9660;
import io.xpusb.core.XpUsbBuilder;

/** Единственный экран: выбрать ISO Windows XP, выбрать флешку, записать. */
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "io.xpusb.maker.USB_PERMISSION";
    private static final int REQ_ISO = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;

    private TextView tvIso, tvStage, tvLog;
    private Button btnIso, btnRefresh, btnStart, btnCancel;
    private Spinner spDev;
    private CheckBox cbVerify;
    private ProgressBar bar;
    private ScrollView scroll;

    private final List<UsbDevice> devices = new ArrayList<>();
    private ParcelFileDescriptor isoPfd;
    private FileInputStream isoStream;
    private Iso9660 iso;
    private String isoName;
    private volatile boolean busy;
    private XpUsbBuilder builder;
    private UsbDevice pendingDevice;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (ok && d != null && d.equals(pendingDevice)) beginWithDevice(d);
                else if (!ok) log("Разрешение на USB-устройство не выдано.");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (!busy) refreshDevices();
            }
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Загрузочная флешка Windows XP");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Для компьютеров с BIOS/Legacy. Нужен оригинальный ISO Windows XP/2003 и флешка "
                + "(подключается через OTG). Содержимое флешки будет полностью удалено.");
        note.setPadding(0, dp(8), 0, dp(16));
        root.addView(note);

        btnIso = new Button(this);
        btnIso.setText("1. Выбрать ISO");
        btnIso.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickIso(); }
        });
        root.addView(btnIso);
        tvIso = new TextView(this);
        tvIso.setText("ISO не выбран");
        tvIso.setPadding(0, dp(4), 0, dp(12));
        root.addView(tvIso);

        btnRefresh = new Button(this);
        btnRefresh.setText("2. Найти флешку");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshDevices(); }
        });
        root.addView(btnRefresh);
        spDev = new Spinner(this);
        root.addView(spDev);

        cbVerify = new CheckBox(this);
        cbVerify.setText("Проверить запись (дольше, но надёжнее)");
        cbVerify.setChecked(true);
        root.addView(cbVerify);

        btnStart = new Button(this);
        btnStart.setText("3. Создать загрузочную флешку");
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startFlow(); }
        });
        root.addView(btnStart);

        btnCancel = new Button(this);
        btnCancel.setText("Отмена");
        btnCancel.setVisibility(View.GONE);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (builder != null) builder.cancel();
            }
        });
        root.addView(btnCancel);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);
        tvStage = new TextView(this);
        root.addView(tvStage);

        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(12);
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(0, dp(12), 0, 0);
        root.addView(tvLog);

        scroll = sv;
        setContentView(sv);

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, f);
        refreshDevices();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        closeIso();
    }

    // ---------------------------------------------------------------- вспомогательное

    private void log(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                tvLog.append(s + "\n");
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private void setBusy(final boolean b) {
        busy = b;
        ui.post(new Runnable() {
            @Override public void run() {
                btnIso.setEnabled(!b);
                btnRefresh.setEnabled(!b);
                btnStart.setEnabled(!b);
                spDev.setEnabled(!b);
                cbVerify.setEnabled(!b);
                btnCancel.setVisibility(b ? View.VISIBLE : View.GONE);
                if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    private void closeIso() {
        try {
            if (isoStream != null) isoStream.close();
            if (isoPfd != null) isoPfd.close();
        } catch (IOException ignored) {
            // ignore
        }
        isoStream = null;
        isoPfd = null;
        iso = null;
    }

    // ---------------------------------------------------------------- ISO

    private void pickIso() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_ISO);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ISO || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        setBusy(true);
        tvIso.setText("Читаю ISO...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    closeIso();
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd == null) throw new IOException("Не удалось открыть файл.");
                    FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                    Iso9660 parsed;
                    try {
                        parsed = new Iso9660(new ChannelSource(fis.getChannel()));
                    } catch (IOException e) {
                        fis.close();
                        pfd.close();
                        throw new IOException("Файл не читается как ISO с возможностью перемотки. "
                                + "Скопируйте ISO во внутреннюю память и выберите его оттуда. (" + e.getMessage() + ")");
                    }
                    String problem = XpUsbBuilder.validateIso(parsed);
                    if (problem != null) {
                        fis.close();
                        pfd.close();
                        throw new IOException(problem);
                    }
                    isoPfd = pfd;
                    isoStream = fis;
                    iso = parsed;
                    isoName = displayName(uri);
                    final String t = isoName + "\nМетка тома: " + parsed.volumeLabel
                            + (parsed.joliet ? " (Joliet)" : "");
                    ui.post(new Runnable() {
                        @Override public void run() { tvIso.setText(t); }
                    });
                } catch (final Exception e) {
                    log("ISO: " + e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { tvIso.setText("ISO не выбран"); }
                    });
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    private String displayName(Uri uri) {
        Cursor c = getContentResolver().query(uri, null, null, null, null);
        try {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) return c.getString(ix);
            }
        } finally {
            if (c != null) c.close();
        }
        return uri.getLastPathSegment();
    }

    // ---------------------------------------------------------------- USB

    private void refreshDevices() {
        devices.clear();
        List<String> names = new ArrayList<>();
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (!UsbMassStorage.isMassStorage(d)) continue;
            devices.add(d);
            String n = d.getProductName() != null ? d.getProductName() : "USB-накопитель";
            names.add(n + String.format(" (%04X:%04X)", d.getVendorId(), d.getProductId()));
        }
        if (names.isEmpty()) names.add("Флешка не найдена — подключите через OTG и нажмите «Найти флешку»");
        spDev.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private void startFlow() {
        if (iso == null) {
            log("Сначала выберите ISO.");
            return;
        }
        if (devices.isEmpty()) {
            log("Флешка не найдена.");
            return;
        }
        UsbDevice d = devices.get(Math.max(0, spDev.getSelectedItemPosition()));
        pendingDevice = d;
        if (usb.hasPermission(d)) {
            beginWithDevice(d);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            usb.requestPermission(d, pi);
        }
    }

    private void beginWithDevice(final UsbDevice d) {
        if (busy) return;
        setBusy(true);
        ui.post(new Runnable() {
            @Override public void run() { tvStage.setText("Открываю флешку..."); }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                final UsbMassStorage dev;
                try {
                    dev = new UsbMassStorage(usb, d);
                } catch (IOException e) {
                    log("Флешка: " + e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { tvStage.setText(""); }
                    });
                    setBusy(false);
                    return;
                }
                final long mb = dev.sectorCount() * dev.sectorSize() / (1024 * 1024);
                ui.post(new Runnable() {
                    @Override public void run() { confirm(dev, d, mb); }
                });
            }
        }).start();
    }

    private void confirm(final UsbMassStorage dev, UsbDevice d, long mb) {
        String size = mb >= 1024 ? String.format("%.1f ГБ", mb / 1024.0) : mb + " МБ";
        String warn = mb > 64 * 1024 ? "\n\nВНИМАНИЕ: объём больше 64 ГБ — это точно флешка, а не внешний диск?" : "";
        new AlertDialog.Builder(this)
                .setTitle("Стереть флешку?")
                .setMessage("Устройство: " + (d.getProductName() != null ? d.getProductName() : "USB") + ", " + size
                        + "\n\nВСЕ ДАННЫЕ НА НЁМ БУДУТ УДАЛЕНЫ." + warn)
                .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface x, int w) {
                        dev.close();
                        tvStage.setText("");
                        setBusy(false);
                    }
                })
                .setPositiveButton("Стереть и записать", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface x, int w) { runBuild(dev); }
                })
                .setCancelable(false)
                .show();
    }

    private void runBuild(final UsbMassStorage dev) {
        final boolean verify = cbVerify.isChecked();
        builder = new XpUsbBuilder();
        tvLog.setText("");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    builder.run(dev, iso, verify, new XpUsbBuilder.Listener() {
                        @Override public void stage(final String t) {
                            ui.post(new Runnable() {
                                @Override public void run() { tvStage.setText(t); }
                            });
                            log("» " + t);
                        }

                        @Override public void progress(final long done, final long total) {
                            ui.post(new Runnable() {
                                @Override public void run() { bar.setProgress((int) (done * 1000 / Math.max(1, total))); }
                            });
                        }

                        @Override public void log(String t) { MainActivity.this.log(t); }
                    });
                    log("Готово. Флешку можно извлекать.\nЗагрузка с неё — в режиме Legacy/CSM (не UEFI).");
                } catch (XpUsbBuilder.CancelledException e) {
                    log("Отменено. Флешка в неполном состоянии — запишите заново.");
                    ui.post(new Runnable() {
                        @Override public void run() { tvStage.setText("Отменено"); }
                    });
                } catch (Exception e) {
                    log("ОШИБКА: " + e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { tvStage.setText("Ошибка"); }
                    });
                } finally {
                    dev.close();
                    setBusy(false);
                }
            }
        }).start();
    }
}
