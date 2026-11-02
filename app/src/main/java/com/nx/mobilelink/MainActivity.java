package com.nx.mobilelink;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Home Activity:
 * Presents Apple Dark Mode dashboard with two dedicated, isolated modes:
 * 1. MobileLink (UPnP/DLNA photo & video browser & downloader)
 * 2. Remote Viewfinder (Live stream preview & shutter control)
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private LinearLayout cardWifiStatus;
    private View indicatorWifiDot;
    private TextView txtHomeWifiName;
    private TextView btnHomeWifiSettings;

    private LinearLayout cardSelectMobileLink;
    private LinearLayout cardSelectViewfinder;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        acquireLocks();
        NetworkManager.registerWifiMonitoring(this, new NetworkManager.WifiListener() {
            @Override
            public void onWifiChanged(boolean connected, String ssid, Network network) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateWifiStatusBanner();
                    }
                });
            }
        });
        checkAndRequestPermissions();
        updateWifiStatusBanner();
    }

    private void initViews() {
        cardWifiStatus = findViewById(R.id.card_wifi_status);
        indicatorWifiDot = findViewById(R.id.indicator_wifi_dot);
        txtHomeWifiName = findViewById(R.id.txt_home_wifi_name);
        btnHomeWifiSettings = findViewById(R.id.btn_home_wifi_settings);

        cardSelectMobileLink = findViewById(R.id.card_select_mobilelink);
        cardSelectViewfinder = findViewById(R.id.card_select_viewfinder);

        btnHomeWifiSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWifiSettings();
            }
        });

        // Launch MobileLink mode
        cardSelectMobileLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, MobileLinkActivity.class);
                startActivity(intent);
            }
        });

        // Launch Remote Viewfinder mode
        cardSelectViewfinder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ViewfinderActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        NetworkManager.bindProcessToWifi(this);
        updateWifiStatusBanner();
    }

    private void updateWifiStatusBanner() {
        NetworkManager.bindProcessToWifi(this);
        if (NetworkManager.isWifiConnected(this)) {
            String ssid = NetworkManager.getCurrentWifiSsid(this);
            String name = (ssid != null) ? "Connected to: " + ssid : "Connected to Wi-Fi";
            txtHomeWifiName.setText(name);
            txtHomeWifiName.setTextColor(0xFFFFFFFF);
            if (indicatorWifiDot != null) {
                indicatorWifiDot.setBackgroundResource(R.drawable.badge_connected);
            }
        } else {
            txtHomeWifiName.setText("Not connected to Wi-Fi (Connect to camera network)");
            txtHomeWifiName.setTextColor(0xFF8E8E93);
            if (indicatorWifiDot != null) {
                indicatorWifiDot.setBackgroundResource(R.drawable.badge_disconnected);
            }
        }
    }

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                startActivity(panelIntent);
                return;
            }
        } catch (Exception ignored) {}
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Wi-Fi settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NXCamLink:WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.i(TAG, "Acquired high-performance WifiLock");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WifiLock: " + e.getMessage());
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NXCamLink:WakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30 * 60 * 1000L);
                Log.i(TAG, "Acquired partial WakeLock");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WakeLock: " + e.getMessage());
        }
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.READ_MEDIA_IMAGES") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.READ_MEDIA_IMAGES");
                }
                if (checkSelfPermission("android.permission.READ_MEDIA_VIDEO") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.READ_MEDIA_VIDEO");
                }
                if (checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.NEARBY_WIFI_DEVICES");
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {}
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }

        super.onDestroy();
    }
}
