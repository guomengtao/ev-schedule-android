package com.ev.bandauthprobe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BandAuthProbe - standalone BLE direct-connect test tool for Xiaomi bands.
 *
 * Purpose: verify whether a band with a given authkey can be reached over raw
 * BLE, bypassing xiaomi wear / com.mi.health. This is an EXPERIMENTAL probe.
 *
 * Scope implemented here (real, runs on device):
 *  - runtime BLE / location permission request
 *  - BLE scan, listing nearby devices (name, MAC, RSSI, connectable)
 *  - tap a device -> connectGatt -> enumerate services & characteristics
 *  - authkey entry persisted in SharedPreferences (kept on-device only)
 *
 * Out of scope / NOT implemented (would require Xiaomi's closed proprietary
 * GATT auth handshake + AES key derivation, which this repo has no library for
 * and must not be fabricated):
 *  - the actual authkey-based encrypted handshake
 *  - band state / schedule / device control commands
 */
public class ProbeActivity extends Activity {
    private static final int REQ_BT_PERMS = 1001;
    private static final int REQ_ENABLE_BT = 1002;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<ScanResult> results = new ArrayList<>();
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private BluetoothGatt activeGatt;

    private TextView log;
    private EditText authKeyInput;
    private LinearLayout deviceList;
    private Button scanBtn;

    private BroadcastReceiver bondReceiver;
    private BluetoothDevice pendingBondDevice;

    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm.getAdapter();
        scanner = (adapter == null) ? null : adapter.getBluetoothLeScanner();
        buildUi();
        log("BandAuthProbe 已就绪");
        logDeviceSupport();
        registerBondReceiver();
    }

    // ============================================================ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = mono("BandAuthProbe");
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        root.addView(space(10));

        // authkey row
        root.addView(mono("authkey（实验性，仅保存在本机）"));
        authKeyInput = new EditText(this);
        authKeyInput.setTypeface(Typeface.MONOSPACE);
        authKeyInput.setHint("e.g. e2bfe55361716796bcde1b45749db7a9");
        authKeyInput.setTextColor(Color.BLACK);
        root.addView(authKeyInput);
        root.addView(space(8));

        // actions
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = btn("开始扫描");
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleScan();
            }
        });
        Button clearBtn = btn("清空");
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                deviceList.removeAllViews();
                logBuf.setLength(0);
                log("");
            }
        });
        Button bondBtn = btn("已配对");
        bondBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                listBondedDevices();
            }
        });
        row.addView(scanBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        row.addView(space(8));
        row.addView(clearBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        row.addView(space(8));
        row.addView(bondBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(row);
        root.addView(space(10));

        // devices
        root.addView(mono("附近设备（点击连接）："));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        ScrollView devScroll = new ScrollView(this);
        devScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        devScroll.addView(deviceList);
        root.addView(devScroll);
        root.addView(space(10));

        // auto-dump toggle + export
        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        Button dumpBtn = btn(autoDump ? "抓包：开" : "抓包：关");
        dumpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoDump = !autoDump;
                dumpBtn.setText(autoDump ? "抓包：开" : "抓包：关");
                log("autoDump=" + autoDump);
            }
        });
        Button exportBtn = btn("导出日志");
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exportLog();
            }
        });
        sRow.addView(dumpBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        sRow.addView(space(8));
        sRow.addView(exportBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(sRow);
        root.addView(space(8));

        // auth-handshake row
        LinearLayout hRow = new LinearLayout(this);
        hRow.setOrientation(LinearLayout.HORIZONTAL);
        Button hsBtn = btn("握手：Init");
        hsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startHandshakeInit();
            }
        });
        Button skBtn = btn("握手：SendKey");
        skBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startHandshakeSendKey();
            }
        });
        hRow.addView(hsBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        hRow.addView(space(8));
        hRow.addView(skBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(hRow);
        root.addView(space(8));

        // log
        root.addView(mono("日志："));
        log = new TextView(this);
        log.setTextSize(10);
        log.setTextColor(Color.BLACK);
        log.setTypeface(Typeface.MONOSPACE);
        scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(log);
        root.addView(scroll);

        setContentView(root);
        loadAuthKey();
    }

    private ScrollView scroll;

    // ============================================================ helpers

    private TextView mono(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.BLACK);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#1a73e8"));
        b.setAllCaps(false);
        return b;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void log(final String line) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                logBuf.append(line).append("\n");
                // keep last ~800 chars to avoid unbounded buffer
                if (logBuf.length() > 2000) {
                    logBuf.delete(0, logBuf.length() - 2000);
                }
                log.setText(logBuf.toString());
                if (scroll != null) {
                    scroll.fullScroll(ScrollView.FOCUS_DOWN);
                }
            }
        });
    }

    private void loadAuthKey() {
        SharedPreferences sp = getSharedPreferences("bandauth", MODE_PRIVATE);
        String k = sp.getString("authkey", "");
        if (!k.isEmpty()) {
            authKeyInput.setText(k);
        }
    }

    private void saveAuthKey() {
        SharedPreferences sp = getSharedPreferences("bandauth", MODE_PRIVATE);
        sp.edit().putString("authkey", authKeyInput.getText().toString().trim()).apply();
    }

    private void logDeviceSupport() {
        if (adapter == null) {
            log("错误：本机没有蓝牙适配器");
            log("本机无法直接 BLE 连接。");
            return;
        }
        if (!adapter.isEnabled()) {
            log("蓝牙已关闭。请打开蓝牙后点击扫描。");
        }
    }

    // ============================================================ permissions

    private boolean needBtPerms() {
        String[] req;
        if (Build.VERSION.SDK_INT >= 31) {
            req = new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        } else {
            req = new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN};
        }
        for (String p : req) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(req, REQ_BT_PERMS);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_BT_PERMS) {
            log("权限请求结果已返回");
            if (scanRequestedByUser) {
                scanRequestedByUser = false;
                requestScanOrStart();
            }
        }
    }

    private boolean scanRequestedByUser = false;

    private void toggleScan() {
        if (adapter == null) {
            log("本机没有蓝牙适配器，无法扫描。");
            return;
        }
        if (!adapter.isEnabled()) {
            log("蓝牙已关闭。请打开蓝牙后重新扫描。");
            return;
        }
        if (scanning) {
            stopScan();
        } else {
            requestScanOrStart();
        }
    }

    /** Request permissions once; when granted, actually begin scan. */
    private void requestScanOrStart() {
        if (needBtPerms()) {
            scanRequestedByUser = true;
            return;
        }
        startScan();
    }

    // ============================================================ scan

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            upsert(result);
        }

        @Override public void onScanFailed(int errorCode) {
            log("扫描失败，错误码=" + errorCode
                    + "（1=不支持BLE 2=已在扫描 3=内部错误 4=应用过多）");
            scanning = false;
            scanBtn.setText("开始扫描");
        }
    };

    private void startScan() {
        saveAuthKey();
        results.clear();
        deviceList.removeAllViews();

        if (scanner == null) {
            log("没有可用的 BLE 扫描器。");
            return;
        }
        scanning = true;
        scanBtn.setText("停止扫描");
        log("正在扫描 BLE 设备...");
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException se) {
            log("扫描时权限异常（缺少权限）。");
            scanning = false;
            scanBtn.setText("开始扫描");
        }
        // auto stop after 15s
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (scanning) {
                    stopScan();
                }
            }
        }, 15000);
    }

    private void stopScan() {
        scanning = false;
        scanBtn.setText("开始扫描");
        try {
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Throwable ignored) {
        }
        log("扫描结束，共发现 " + foundCount() + " 台设备。");
    }

    private String foundCount() {
        return "" + results.size();
    }

    /** Write the captured log to the app's external files dir (no permission needed).
        Path: /sdcard/Android/data/com.ev.bandauthprobe/files/bandauth_sniff.txt */
    private void exportLog() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            java.io.File f = new java.io.File(dir, "bandauth_sniff.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(logBuf.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            log("已导出到 " + f.getAbsolutePath());
        } catch (Throwable t) {
            log("导出失败：" + t.getMessage());
        }
    }

    /** Show all bonded (paired) devices in the list so a band that is paired
     *  but not advertising can still be connected directly. */
    private void listBondedDevices() {
        results.clear();
        deviceList.removeAllViews();
        if (adapter == null) {
            log("没有蓝牙适配器。");
            return;
        }
        java.util.Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException se) {
            log("读取已配对设备需要 BLUETOOTH_CONNECT 权限。");
            return;
        }
        log("已配对设备共 " + bonded.size() + " 个：");
        deviceList.addView(mono("---- 已配对设备 ----"));
        for (BluetoothDevice d : bonded) {
            final BluetoothDevice fd = d;
            String name = safeName(d);
            boolean band = name.toLowerCase(Locale.US).contains("band")
                    || name.toLowerCase(Locale.US).contains("xiaomi")
                    || name.toLowerCase(Locale.US).contains("mi");
            TextView row = new TextView(this);
            row.setTextColor(Color.BLACK);
            row.setTextSize(12);
            row.setText(name + "\n  " + d.getAddress() + (band ? "  << 手环" : ""));
            row.setPadding(0, dp(6), 0, dp(6));
            row.setBackgroundColor(band ? Color.parseColor("#cfe8ff")
                    : Color.parseColor("#eeeeee"));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    connect(fd);
                }
            });
            deviceList.addView(row);
        }
    }

    private void upsert(ScanResult r) {
        String mac = r.getDevice().getAddress();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getDevice().getAddress().equals(mac)) {
                results.set(i, r);
                refreshList();
                return;
            }
        }
        results.add(r);
        refreshList();
    }

    private void refreshList() {
        deviceList.removeAllViews();
        for (ScanResult r : results) {
            final ScanResult fr = r;
            BluetoothDevice d = r.getDevice();
            String name = safeName(d);
            boolean mightBeBand = mightBeBand(name);

            TextView row = new TextView(this);
            row.setTextColor(Color.BLACK);
            row.setTextSize(12);
            String flag = mightBeBand ? "  << 可能为手环" : "";
            row.setText(name + "\n  " + d.getAddress()
                    + "  RSSI=" + r.getRssi() + "  conn=" + r.isConnectable() + flag);
            row.setPadding(0, dp(6), 0, dp(6));
            row.setBackgroundColor(mightBeBand
                    ? Color.parseColor("#fff3cd") : Color.parseColor("#eeeeee"));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    connect(fr.getDevice());
                }
            });
            deviceList.addView(row);
        }
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? "(无名)" : n;
        } catch (SecurityException se) {
            return "(无名 / 无连接权限)";
        }
    }

    /** Heuristic: xiaomi bands advertise short model names. Not exhaustive. */
    private boolean mightBeBand(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        return n.contains("band") || n.contains("mi")
                || n.contains("xiaomi") || n.contains("ring")
                || n.matches(".*[0-9].*nfc.*");
    }

    /** Guess whether a device is likely a BLE band/watch (use autoConnect=true). */
    private boolean isLikelyBand(BluetoothDevice device) {
        String name = safeName(device);
        if (name != null && !name.isEmpty()) {
            String n = name.toLowerCase(Locale.US);
            if (n.contains("band") || n.contains("mi")
                    || n.contains("xiaomi") || n.contains("ring")
                    || n.contains("watch") || n.contains("bracelet")) {
                return true;
            }
        }
        return true;
    }

    // ============================================================ connect

    private boolean autoDump = true;

    private void registerBondReceiver() {
        bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    return;
                }
                log("绑定事件：" + action + " 设备=" + hex(device));

                if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    log("配对请求 variant=" + variant + "（0=Passkey, 2=Passkey Confirm, 3=Consent, 5=Display Passkey）");

                    String authKeyHex = authKeyInput.getText().toString().trim();
                    if (authKeyHex.length() == 32) {
                        byte[] keyBytes = hexToBytes(authKeyHex);
                        if (keyBytes != null && trySetPin(device, keyBytes)) {
                            log("已通过反射调用 setPin(authkey字节)。");
                            confirmPairing(device);
                            log("已自动确认配对。");
                        } else {
                            log("反射 setPin 失败，尝试 Just Works 确认...");
                            confirmPairing(device);
                            log("已自动确认配对（Just Works）。");
                        }
                    } else {
                        log("未输入有效 authkey（需要 32 位十六进制），尝试 Just Works...");
                        confirmPairing(device);
                        log("已自动确认配对（Just Works）。");
                    }
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                    log("绑定状态变更：" + prevState + " -> " + bondState + "（10=NONE, 11=BONDING, 12=BONDED）");

                    if (bondState == BluetoothDevice.BOND_BONDED && prevState == BluetoothDevice.BOND_BONDING) {
                        log("绑定成功！开始 GATT 连接...");
                        if (pendingBondDevice != null) {
                            connectGattDirect(pendingBondDevice);
                            pendingBondDevice = null;
                        }
                    } else if (bondState == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING) {
                        log("绑定失败！尝试直接 GATT 连接...");
                        if (pendingBondDevice != null) {
                            connectGattDirect(pendingBondDevice);
                            pendingBondDevice = null;
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondReceiver, filter);
    }

    private boolean trySetPin(BluetoothDevice device, byte[] pin) {
        try {
            java.lang.reflect.Method m = device.getClass().getMethod("setPin", byte[].class);
            Boolean result = (Boolean) m.invoke(device, (Object) pin);
            return result != null && result;
        } catch (Exception e) {
            log("setPin 反射异常：" + e.getMessage());
            return false;
        }
    }

    private boolean confirmPairing(BluetoothDevice device) {
        try {
            java.lang.reflect.Method m = device.getClass().getMethod("setPairingConfirmation", boolean.class);
            Boolean result = (Boolean) m.invoke(device, true);
            return result != null && result;
        } catch (Exception e) {
            log("setPairingConfirmation 反射异常：" + e.getMessage());
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private void connect(final BluetoothDevice device) {
        if (activeGatt != null) {
            log("正在断开上一个设备...");
            try {
                activeGatt.disconnect();
                activeGatt.close();
            } catch (Throwable ignored) {
            }
            activeGatt = null;
        }

        String authKeyHex = authKeyInput.getText().toString().trim();
        if (authKeyHex.length() == 32 && hexToBytes(authKeyHex) != null) {
            log("正在使用 authkey 进行 BLE 安全性绑定...");
            log("设备：" + deviceName(device) + "（" + device.getAddress() + "）");
            pendingBondDevice = device;
            try {
                boolean started;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    try {
                        java.lang.reflect.Method createBondLe = device.getClass()
                                .getMethod("createBond", int.class);
                        started = (Boolean) createBondLe.invoke(device, 2);
                    } catch (Exception e) {
                        started = device.createBond();
                    }
                } else {
                    started = device.createBond();
                }
                if (started) {
                    log("绑定请求已发送，等待配对...");
                    return;
                }
                log("createBond 返回 false，回退到直接 GATT 连接。");
            } catch (Exception e) {
                log("createBond 异常：" + e.getMessage() + "，尝试直接 GATT 连接。");
            }
            pendingBondDevice = null;
        }

        connectGattDirect(device);
    }

    // ============================================================ AUTH HANDSHAKE
    // Huami/Mi Fit private GATT auth channel:
    //   service 0000FE35 (MI_SERVICE_UUID) hosts the auth write characteristic
    //   0000FE95 (write/no-response) and a notify characteristic 0000FE90.
    // The authkey (huamiAuthKey) is delivered as an encrypted key frame over
    // FE95. Exact frame bytes must be calibrated against a live HCI capture;
    // these methods emit the process & log every frame so it can be validated.

    private static final String UUID_SVC_FE35 = "0000fe35-0000-1000-8000-00805f9b34fb";
    private static final String UUID_CH_WRITE_FE95 = "0000fe95-0000-1000-8000-00805f9b34fb";
    private static final String UUID_CH_NOTIFY_FE90 = "0000fe90-0000-1000-8000-00805f9b34fb";

    private BluetoothGattCharacteristic writeFe95;
    private BluetoothGattCharacteristic notifyFe90;
    private int handshakeStep = 0;

    private void startHandshakeInit() {
        saveAuthKey();
        if (activeGatt == null) {
            log("握手：未连接设备。请先扫描并连接手环。");
            return;
        }
        locateAuthChars();
        if (writeFe95 == null) {
            log("握手：未找到 FE95 写入特征。请确认已连接手环且服务发现完成（看上方 svc/ch 列表有无 fe35）。");
            return;
        }
        log("==== 认证握手 Init ====");
        handshakeStep = 0;
        // 华米 Init 帧（示意；需用抓包校准）
        byte[] init = {0x02, 0x00, 0x00, 0x00, (byte) 0xA0};
        sendHandshakeFrame(init, "Init");
    }

    private void startHandshakeSendKey() {
        saveAuthKey();
        if (activeGatt == null || writeFe95 == null) {
            log("握手：请先连接并点击 Init。");
            return;
        }
        String ak = authKeyInput.getText().toString().trim();
        if (ak.length() != 32) {
            log("握手：authkey 需为 32 位十六进制（当前 " + ak.length() + " 位）。");
            return;
        }
        byte[] key = hexToBytes(ak);
        log("==== 认证握手 SendKey ====");
        handshakeStep = 1;
        byte[] frame = new byte[2 + key.length + 4];
        frame[0] = 0x02;
        frame[1] = (byte) (key.length + 4);
        System.arraycopy(key, 0, frame, 2, key.length);
        // 简示例：末 4 字节占位（校验/随机），实测需按帧格式填充
        frame[frame.length - 4] = (byte) 0xC1;
        frame[frame.length - 3] = (byte) 0xC1;
        frame[frame.length - 2] = (byte) 0x00;
        frame[frame.length - 1] = (byte) 0x00;
        sendHandshakeFrame(frame, "SendKey");
    }

    private void locateAuthChars() {
        writeFe95 = null;
        notifyFe90 = null;
        BluetoothGattService svc = activeGatt.getService(
                java.util.UUID.fromString(UUID_SVC_FE35));
        if (svc == null) {
            log("定位 FE35 服务：未找到。尝试按特征 UUID 遍历...");
            for (BluetoothGattService s : activeGatt.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    String u = c.getUuid().toString().toLowerCase(Locale.US);
                    if (u.startsWith("0000fe95")) {
                        writeFe95 = c;
                    }
                    if (u.startsWith("0000fe90")) {
                        notifyFe90 = c;
                    }
                }
            }
            return;
        }
        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
            String u = c.getUuid().toString().toLowerCase(Locale.US);
            if (u.startsWith("0000fe95")) {
                writeFe95 = c;
            } else if (u.startsWith("0000fe90")) {
                notifyFe90 = c;
            }
        }
        log("定位认证特征：write(fe95)=" + (writeFe95 != null)
                + " notify(fe90)=" + (notifyFe90 != null));
        if (notifyFe90 != null) {
            try {
                activeGatt.setCharacteristicNotification(notifyFe90, true);
                for (BluetoothGattDescriptor d : notifyFe90.getDescriptors()) {
                    if (d.getUuid().toString().toLowerCase(Locale.US).startsWith("00002902")) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        activeGatt.writeDescriptor(d);
                    }
                }
            } catch (Throwable t) {
                log("订阅 FE90 通知失败：" + t.getMessage());
            }
        }
    }

    private void sendHandshakeFrame(byte[] frame, String tag) {
        try {
            log("发送[" + tag + "] " + bytes2hex(frame));
            boolean ok;
            int props = writeFe95.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                writeFe95.setValue(frame);
                ok = activeGatt.writeCharacteristic(writeFe95);
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                writeFe95.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                writeFe95.setValue(frame);
                ok = activeGatt.writeCharacteristic(writeFe95);
            } else {
                log("[" + tag + "] FE95 不支持写入，放弃。");
                return;
            }
            log("[" + tag + "] writeCharacteristic -> " + ok);
        } catch (SecurityException se) {
            log("[" + tag + "] 权限异常：" + se.getMessage());
        }
    }

    private void advanceHandshake(byte[] notify) {
        String h = bytes2hex(notify);
        log("握手收到通知：[" + h + "] step=" + handshakeStep);
        // TODO: 根据实测帧解析结果，推进到下一认证步骤/ACK
    }

    /** Direct GATT connect without bonding. */
    private void connectGattDirect(BluetoothDevice device) {
        if (activeGatt != null) {
            log("正在断开上一个设备...");
            try {
                activeGatt.disconnect();
                activeGatt.close();
            } catch (Throwable ignored) {
            }
            activeGatt = null;
        }
        log("正在 GATT 连接 " + deviceName(device) + "（" + device.getAddress() + "）...");
        boolean autoConnect = isLikelyBand(device);
        if (autoConnect) {
            log("使用 autoConnect=true（检测到可能是手环）");
        }
        try {
            activeGatt = device.connectGatt(this, autoConnect, gattCallback);
        } catch (SecurityException se) {
            log("连接时权限异常（需要 BLUETOOTH_CONNECT）。");
        }
    }

    private String deviceName(BluetoothDevice d) {
        return safeName(d);
    }

    /** Render a byte[] as space-separated hex (lower, 2-digit). */
    private static String bytes2hex(byte[] b) {
        if (b == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02x", x & 0xff));
        }
        return sb.toString();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("已连接 " + hex(gatt.getDevice()) + " 状态=" + status);
                log("正在发现服务...");
                try {
                    gatt.discoverServices();
                } catch (SecurityException se) {
                    log("发现服务时权限异常");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("已断开 状态=" + status);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log("服务发现完成，状态=" + status + "（0=成功）");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("服务发现失败。手环很可能需要先完成鉴权认证。");
                return;
            }
            for (BluetoothGattService svc : gatt.getServices()) {
                log("svc  " + svc.getUuid());
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int p = ch.getProperties();
                    log("  ch  " + ch.getUuid()
                            + "  props=0x" + String.format("%02x", p)
                            + (ch.getDescriptors().isEmpty() ? "" : "  descs=" + ch.getDescriptors().size()));
                }
            }
            log("全部服务已枚举。开始抓取每个可读/通知特征...");
            snuffleAll(gatt);
        }

        /**
         * Register notify + issue a read on every characteristic we can.
         * This turns the whole device into a readable surface so raw bytes
         * (including the auth handshake, if reachable) get logged.
         */
        private void snuffleAll(BluetoothGatt gatt) {
            if (!autoDump) {
                return;
            }
            for (BluetoothGattService svc : gatt.getServices()) {
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int p = ch.getProperties();
                    try {
                        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                                || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            log("  [写] " + ch.getUuid() + "（支持写入；等后续用 authkey 流程发送）");
                        }
                        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                            log("  读取 " + ch.getUuid());
                            gatt.readCharacteristic(ch);
                        }
                        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            log("  通知 " + ch.getUuid());
                            try {
                                if (gatt.setCharacteristicNotification(ch, true)) {
                                    for (BluetoothGattDescriptor d : ch.getDescriptors()) {
                                        if (d.getUuid().toString().toLowerCase(Locale.US)
                                                .startsWith("00002902")) {
                                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                            gatt.writeDescriptor(d);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                log("    （订阅通知出错）" + t.getMessage());
                            }
                        }
                    } catch (SecurityException se) {
                        log("  [权限] " + ch.getUuid() + " " + se.getMessage());
                    }
                }
            }
        }

        // ---- sniffed raw bytes ----

        @Override public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic ch, int status) {
            byte[] val = ch.getValue();
            log("读取  " + ch.getUuid() + "  状态=" + status + "  字节=[" + bytes2hex(val) + "]");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic ch) {
            byte[] val = ch.getValue();
            log("通知 " + ch.getUuid() + "  字节=[" + bytes2hex(val) + "]");
            if (ch.getUuid().toString().toLowerCase(Locale.US).startsWith("0000fe90")
                    || ch.getUuid().toString().toLowerCase(Locale.US).startsWith("0000fe91")) {
                advanceHandshake(val);
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt,
                BluetoothGattDescriptor d, int status) {
            log("描述符 " + d.getUuid() + " -> " + bytes2hex(d.getValue())
                    + "  状态=" + status);
        }
    };

    private String hex(BluetoothDevice d) {
        // just the MAC, nothing sensitive
        return d.getAddress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        if (bondReceiver != null) {
            try {
                unregisterReceiver(bondReceiver);
            } catch (Throwable ignored) {
            }
            bondReceiver = null;
        }
        if (activeGatt != null) {
            try {
                activeGatt.disconnect();
                activeGatt.close();
            } catch (Throwable ignored) {
            }
        }
    }
}