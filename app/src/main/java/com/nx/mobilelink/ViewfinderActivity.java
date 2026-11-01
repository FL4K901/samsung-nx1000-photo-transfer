package com.nx.mobilelink;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Remote Viewfinder Activity:
 * Real-time camera viewfinder stream and remote shutter control.
 * Completely isolated from MobileLink mode.
 */
public class ViewfinderActivity extends Activity {
    private static final String TAG = "ViewfinderActivity";

    private ImageButton btnBackToHome;
    private TextView txtStatusBadge;
    private ProgressBar progressScan;
    private Button btnConnect;
    private Button btnWifi;

    private ImageView imgPreview;
    private LinearLayout layoutPlaceholder;
    private TextView txtInstructions;
    private ImageButton btnShutter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isConnecting = false;
    private String connectedCameraIp = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewfinder);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        updateStatusBanner();

        // Auto-connect if Wi-Fi is active
        if (isConnectedToWifi()) {
            startConnection();
        }
    }

    private void initViews() {
        btnBackToHome = findViewById(R.id.btn_rvf_back_to_home);
        txtStatusBadge = findViewById(R.id.txt_rvf_status_badge);
        progressScan = findViewById(R.id.progress_rvf_scan);
        btnConnect = findViewById(R.id.btn_rvf_connect);
        btnWifi = findViewById(R.id.btn_rvf_wifi);

        imgPreview = findViewById(R.id.img_rvf_preview);
        layoutPlaceholder = findViewById(R.id.layout_rvf_placeholder);
        txtInstructions = findViewById(R.id.txt_rvf_instructions);
        btnShutter = findViewById(R.id.btn_rvf_shutter);

        btnBackToHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWifiSettings();
            }
        });

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConnection();
            }
        });

        btnShutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShutterClicked();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusBanner();
    }

    private boolean isConnectedToWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo netInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                return netInfo != null && netInfo.isConnected();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String getCurrentWifiSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getSSID() != null) {
                    String ssid = wi.getSSID();
                    if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 2) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    if (!ssid.equals("<unknown ssid>")) {
                        return ssid;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void updateStatusBanner() {
        if (connectedCameraIp != null) {
            txtStatusBadge.setText("✓ מחובר ל-Viewfinder (" + connectedCameraIp + ")");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_connected);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_green_text));
        } else if (isConnectedToWifi()) {
            String ssid = getCurrentWifiSsid();
            String name = (ssid != null) ? "רשת: " + ssid : "רשת Wi-Fi מחוברת";
            txtStatusBadge.setText(name + " (לחץ להתחברות)");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));
        } else {
            txtStatusBadge.setText("לא מחובר לרשת המצלמה");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_disconnected);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_red_text));
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
            Toast.makeText(this, "לא ניתן לפתוח הגדרות Wi-Fi", Toast.LENGTH_SHORT).show();
        }
    }

    private void startConnection() {
        if (isConnecting) return;
        isConnecting = true;

        progressScan.setVisibility(View.VISIBLE);
        btnConnect.setEnabled(false);
        txtStatusBadge.setText("מתחבר ל-Remote Viewfinder...");
        txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
        txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));

        ViewfinderClient.connect(this, null, new ViewfinderClient.ConnectionCallback() {
            @Override
            public void onConnected(final String cameraIp) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        isConnecting = false;
                        progressScan.setVisibility(View.GONE);
                        btnConnect.setEnabled(true);
                        connectedCameraIp = cameraIp;
                        updateStatusBanner();
                        Toast.makeText(ViewfinderActivity.this, "התחבר בהצלחה ל-Remote Viewfinder!", Toast.LENGTH_SHORT).show();

                        // Start receiving live stream
                        startStream(cameraIp);
                    }
                });
            }

            @Override
            public void onConnectionFailed(final String error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        isConnecting = false;
                        progressScan.setVisibility(View.GONE);
                        btnConnect.setEnabled(true);
                        updateStatusBanner();
                        Toast.makeText(ViewfinderActivity.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void startStream(String cameraIp) {
        ViewfinderClient.startLiveStream(cameraIp, new ViewfinderClient.FrameCallback() {
            @Override
            public void onFrame(final Bitmap frame) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        layoutPlaceholder.setVisibility(View.GONE);
                        imgPreview.setVisibility(View.VISIBLE);
                        imgPreview.setImageBitmap(frame);
                    }
                });
            }

            @Override
            public void onStreamError(final String error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.w(TAG, "Stream notice: " + error);
                    }
                });
            }
        });
    }

    private void onShutterClicked() {
        if (connectedCameraIp == null) {
            Toast.makeText(this, "אנא התחבר למצלמה תחילה", Toast.LENGTH_SHORT).show();
            return;
        }

        btnShutter.setEnabled(false);
        btnShutter.setAlpha(0.5f);
        Toast.makeText(this, "מצלם תמונה...", Toast.LENGTH_SHORT).show();

        ViewfinderClient.triggerShutter(connectedCameraIp, new ViewfinderClient.ShutterCallback() {
            @Override
            public void onSuccess() {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnShutter.setEnabled(true);
                        btnShutter.setAlpha(1.0f);
                        Toast.makeText(ViewfinderActivity.this, "התמונה צולמה בהצלחה ונשמרה במצלמה!", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(final String error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnShutter.setEnabled(true);
                        btnShutter.setAlpha(1.0f);
                        Toast.makeText(ViewfinderActivity.this, "צילום: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        ViewfinderClient.disconnect();
        super.onDestroy();
    }
}
