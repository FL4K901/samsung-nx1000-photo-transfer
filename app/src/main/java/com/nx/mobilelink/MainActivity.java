package com.nx.mobilelink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private TextView txtStatusBadge;
    private Button btnOpenWifi;
    private Button btnScanCamera;
    private Button btnDirectIp;
    private ProgressBar progressScan;

    private LinearLayout layoutBatchControls;
    private Button btnToggleSelectAll;
    private TextView txtSelectionCount;
    private Button btnDownloadSelected;

    private LinearLayout layoutDownloadProgress;
    private TextView txtDownloadStatus;
    private ProgressBar progressDownload;

    private ScrollView scrollEmptyInstructions;
    private Button btnInstructionScan;
    private GridView gridPhotos;

    private CameraClient.DeviceInfo connectedDevice;
    private final List<PhotoItem> photoList = new ArrayList<>();
    private PhotoAdapter photoAdapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundPool = Executors.newFixedThreadPool(3);

    private boolean isScanning = false;
    private boolean isDownloading = false;
    private boolean allSelectedState = false;

    // Power & Wi-Fi Locks (Prevents Camera Wi-Fi Disconnect)
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen awake while using the app so connection does not sleep
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        CameraClient.init(this);
        initViews();
        acquireLocks();
        registerWifiNetworkCallback();
        checkAndRequestPermissions();
        updateWifiStatusBanner();

        // Auto-scan if already connected to a Wi-Fi network
        if (isConnectedToWifi()) {
            startCameraScan(null, 0);
        }
    }

    private void initViews() {
        txtStatusBadge = findViewById(R.id.txt_status_badge);
        btnOpenWifi = findViewById(R.id.btn_open_wifi);
        btnScanCamera = findViewById(R.id.btn_scan_camera);
        btnDirectIp = findViewById(R.id.btn_direct_ip);
        progressScan = findViewById(R.id.progress_scan);

        layoutBatchControls = findViewById(R.id.layout_batch_controls);
        btnToggleSelectAll = findViewById(R.id.btn_toggle_select_all);
        txtSelectionCount = findViewById(R.id.txt_selection_count);
        btnDownloadSelected = findViewById(R.id.btn_download_selected);

        layoutDownloadProgress = findViewById(R.id.layout_download_progress);
        txtDownloadStatus = findViewById(R.id.txt_download_status);
        progressDownload = findViewById(R.id.progress_download);

        scrollEmptyInstructions = findViewById(R.id.scroll_empty_instructions);
        btnInstructionScan = findViewById(R.id.btn_instruction_scan);
        gridPhotos = findViewById(R.id.grid_photos);

        photoAdapter = new PhotoAdapter(this, photoList);
        gridPhotos.setAdapter(photoAdapter);

        // Listeners
        btnOpenWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWifiSettings();
            }
        });

        btnScanCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCameraScan(null, 0);
            }
        });

        btnInstructionScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCameraScan(null, 0);
            }
        });

        btnDirectIp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDirectIpDialog();
            }
        });

        btnToggleSelectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                allSelectedState = !allSelectedState;
                photoAdapter.selectAll(allSelectedState);
                btnToggleSelectAll.setText(allSelectedState ? R.string.btn_unselect_all : R.string.btn_select_all);
            }
        });

        photoAdapter.setOnSelectionChangeListener(new PhotoAdapter.OnSelectionChangeListener() {
            @Override
            public void onSelectionChanged(int selectedCount, int totalCount) {
                txtSelectionCount.setText(selectedCount + " מתוך " + totalCount + " נבחרו");
                btnDownloadSelected.setEnabled(selectedCount > 0);
            }
        });

        btnDownloadSelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBatchDownload();
            }
        });

        gridPhotos.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PhotoItem item = photoList.get(position);
                showPreviewDialog(item);
            }
        });
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
                wakeLock.acquire(30 * 60 * 1000L /* 30 minutes */);
                Log.i(TAG, "Acquired partial WakeLock");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WakeLock: " + e.getMessage());
        }
    }

    private void registerWifiNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build();

                    networkCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            Log.i(TAG, "Wi-Fi Network available: " + network);
                            CameraClient.setActiveWifiNetwork(network);

                            // Bind this entire app process to Wi-Fi so cellular data doesn't interfere!
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                cm.bindProcessToNetwork(network);
                                Log.i(TAG, "Process successfully bound to Wi-Fi network");
                            }

                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateWifiStatusBanner();
                                    // Automatically trigger camera scan as soon as Wi-Fi connects!
                                    if (connectedDevice == null && !isScanning) {
                                        startCameraScan(null, 0);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onLost(Network network) {
                            Log.i(TAG, "Wi-Fi Network lost: " + network);
                            CameraClient.setActiveWifiNetwork(null);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                cm.bindProcessToNetwork(null);
                            }
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    connectedDevice = null;
                                    updateWifiStatusBanner();
                                }
                            });
                        }
                    };

                    cm.registerNetworkCallback(request, networkCallback);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not register NetworkCallback: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWifiStatusBanner();
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

    private void updateWifiStatusBanner() {
        if (connectedDevice != null) {
            txtStatusBadge.setText("✓ " + connectedDevice.friendlyName + " (מחובר)");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_connected);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_green_text));
        } else if (isConnectedToWifi()) {
            String ssid = getCurrentWifiSsid();
            String name = (ssid != null) ? "רשת: " + ssid : "רשת Wi-Fi מחוברת";
            txtStatusBadge.setText(name + " (לחץ לסריקה)");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));
        } else {
            txtStatusBadge.setText(R.string.status_not_connected);
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

    private void startCameraScan(final String customIp, final int customPort) {
        if (isScanning) return;
        isScanning = true;

        progressScan.setVisibility(View.VISIBLE);
        btnScanCamera.setEnabled(false);
        txtStatusBadge.setText(R.string.status_searching);
        txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
        txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));

        CameraClient.discoverCamera(this, customIp, customPort, new CameraClient.DiscoveryCallback() {
            @Override
            public void onDeviceFound(final CameraClient.DeviceInfo device) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        connectedDevice = device;
                        // Start ultra-reliable keepalive immediately
                        CameraClient.startKeepAlive(MainActivity.this, device);
                        updateWifiStatusBanner();
                        Toast.makeText(MainActivity.this, "נמצאה מצלמה: " + device.friendlyName, Toast.LENGTH_SHORT).show();
                        loadPhotosFromCamera(device);
                    }
                });
            }

            @Override
            public void onDiscoveryFailed(final String error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        isScanning = false;
                        progressScan.setVisibility(View.GONE);
                        btnScanCamera.setEnabled(true);
                        updateWifiStatusBanner();

                        // If Wi-Fi is still connected to the camera and no device yet, auto-retry discovery
                        if (connectedDevice == null && isConnectedToWifi()) {
                            txtStatusBadge.setText("ממתין לאישור במצלמה... סורק שוב");
                            txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
                            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (connectedDevice == null && isConnectedToWifi() && !isScanning) {
                                        Log.i(TAG, "Auto-retrying camera discovery...");
                                        startCameraScan(null, 0);
                                    }
                                }
                            }, 3000);
                        } else {
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void loadPhotosFromCamera(final CameraClient.DeviceInfo device) {
        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<PhotoItem> loaded = null;
                    for (int attempt = 1; attempt <= 5; attempt++) {
                        try {
                            loaded = CameraClient.browsePhotos(device);
                            if (loaded != null && !loaded.isEmpty()) {
                                break;
                            }
                        } catch (Exception be) {
                            Log.w(TAG, "Browse attempt " + attempt + " error: " + be.getMessage());
                        }
                        if (attempt < 5) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtStatusBadge.setText("ממתין לתמונות... (ודא אישור במצלמה)");
                                }
                            });
                            try { Thread.sleep(1500); } catch (InterruptedException ignored) { break; }
                        }
                    }
                    final List<PhotoItem> finalLoaded = loaded;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            isScanning = false;
                            progressScan.setVisibility(View.GONE);
                            btnScanCamera.setEnabled(true);

                            photoList.clear();
                            if (finalLoaded != null && !finalLoaded.isEmpty()) {
                                photoList.addAll(finalLoaded);
                                scrollEmptyInstructions.setVisibility(View.GONE);
                                gridPhotos.setVisibility(View.VISIBLE);
                                layoutBatchControls.setVisibility(View.VISIBLE);
                                photoAdapter.notifyDataSetChanged();
                                txtSelectionCount.setText("0 מתוך " + photoList.size() + " נבחרו");
                                Toast.makeText(MainActivity.this, "נטענו " + photoList.size() + " תמונות וסרטונים", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "לא נמצאו תמונות בכרטיס הזיכרון של המצלמה. אם המצלמה שואלת, לחץ 'כן' לאישור", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            isScanning = false;
                            progressScan.setVisibility(View.GONE);
                            btnScanCamera.setEnabled(true);
                            Log.e(TAG, "Error browsing photos", e);
                            Toast.makeText(MainActivity.this, "שגיאה בטעינת רשימת התמונות: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void showDirectIpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ip, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();

        final EditText editIp = dialogView.findViewById(R.id.edit_camera_ip);
        final EditText editPort = dialogView.findViewById(R.id.edit_camera_port);
        Button btnCancel = dialogView.findViewById(R.id.dialog_ip_cancel);
        Button btnConnect = dialogView.findViewById(R.id.dialog_ip_connect);

        String gw = CameraClient.getWifiGatewayIp(this);
        if (gw != null && !gw.isEmpty()) {
            editIp.setText(gw);
        }

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = editIp.getText().toString().trim();
                String portStr = editPort.getText().toString().trim();
                int port = 0;
                if (!portStr.isEmpty()) {
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (Exception ignored) {}
                }
                dialog.dismiss();
                startCameraScan(ip, port);
            }
        });

        dialog.show();
    }

    private void startBatchDownload() {
        final List<PhotoItem> selected = new ArrayList<>();
        for (PhotoItem item : photoList) {
            if (item.isSelected()) {
                selected.add(item);
            }
        }

        if (selected.isEmpty()) {
            Toast.makeText(this, "אנא סמן לפחות תמונה אחת להורדה", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isDownloading) return;
        isDownloading = true;
        CameraClient.isDownloading.set(true);

        layoutDownloadProgress.setVisibility(View.VISIBLE);
        progressDownload.setMax(selected.size());
        progressDownload.setProgress(0);
        btnDownloadSelected.setEnabled(false);

        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                File targetDir = getDownloadDirectory();
                int successCount = 0;

                try {
                    for (int i = 0; i < selected.size(); i++) {
                        final int index = i;
                        final PhotoItem item = selected.get(i);

                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                txtDownloadStatus.setText(String.format(getString(R.string.downloading_progress), (index + 1), selected.size()) + " (" + item.getTitle() + ")");
                                item.setDownloadStatus(PhotoItem.STATUS_DOWNLOADING);
                                photoAdapter.notifyDataSetChanged();
                            }
                        });

                        try {
                            CameraClient.downloadPhoto(MainActivity.this, item, targetDir, null);
                            item.setDownloadStatus(PhotoItem.STATUS_COMPLETED);
                            successCount++;
                        } catch (Exception e) {
                            Log.e(TAG, "Error downloading " + item.getTitle(), e);
                            item.setDownloadStatus(PhotoItem.STATUS_ERROR);
                        }

                        // Flush delay: give camera storage and TCP stack 400ms to close socket cleanly
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {}

                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                progressDownload.setProgress(index + 1);
                                photoAdapter.notifyDataSetChanged();
                            }
                        });
                    }
                } finally {
                    CameraClient.isDownloading.set(false);
                }

                final int finalSuccess = successCount;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        isDownloading = false;
                        layoutDownloadProgress.setVisibility(View.GONE);
                        btnDownloadSelected.setEnabled(true);

                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("ההורדה הושלמה!")
                                .setMessage(finalSuccess + " מתוך " + selected.size() + " קבצים נשמרו בהצלחה בגלריה בתיקיית NX1000.")
                                .setPositiveButton("אישור", null)
                                .show();
                    }
                });
            }
        });
    }

    private void showPreviewDialog(final PhotoItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_preview, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();

        TextView txtTitle = dialogView.findViewById(R.id.dialog_preview_title);
        final ImageView imgPreview = dialogView.findViewById(R.id.dialog_preview_image);
        TextView txtInfo = dialogView.findViewById(R.id.dialog_preview_info);
        Button btnClose = dialogView.findViewById(R.id.dialog_btn_close);
        Button btnDownload = dialogView.findViewById(R.id.dialog_btn_download);

        txtTitle.setText(item.getTitle());
        txtInfo.setText("גודל: " + item.getFormattedSize() + " | תאריך: " + item.getDate());

        // Load preview
        imgPreview.setImageResource(R.drawable.thumb_placeholder);
        final String loadUrl = (item.getThumbUrl() != null) ? item.getThumbUrl() : item.getFullUrl();

        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(loadUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(6000);
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        final Bitmap bmp = BitmapFactory.decodeStream(in);
                        in.close();
                        conn.disconnect();
                        if (bmp != null) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    imgPreview.setImageBitmap(bmp);
                                }
                            });
                        }
                    } else {
                        conn.disconnect();
                    }
                } catch (Exception ignored) {}
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                downloadSinglePhoto(item);
            }
        });

        dialog.show();
    }

    private void downloadSinglePhoto(final PhotoItem item) {
        Toast.makeText(this, "מתחיל בהורדת " + item.getTitle() + "...", Toast.LENGTH_SHORT).show();
        item.setDownloadStatus(PhotoItem.STATUS_DOWNLOADING);
        photoAdapter.notifyDataSetChanged();

        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File targetDir = getDownloadDirectory();
                    CameraClient.downloadPhoto(MainActivity.this, item, targetDir, null);
                    item.setDownloadStatus(PhotoItem.STATUS_COMPLETED);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            photoAdapter.notifyDataSetChanged();
                            Toast.makeText(MainActivity.this, item.getTitle() + " נשמר בהצלחה בגלריה!", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    item.setDownloadStatus(PhotoItem.STATUS_ERROR);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            photoAdapter.notifyDataSetChanged();
                            Toast.makeText(MainActivity.this, "שגיאה בהורדה: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private File getDownloadDirectory() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NX1000");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
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
        CameraClient.stopKeepAlive();

        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cm.bindProcessToNetwork(null);
                    }
                }
            } catch (Exception ignored) {}
        }

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
