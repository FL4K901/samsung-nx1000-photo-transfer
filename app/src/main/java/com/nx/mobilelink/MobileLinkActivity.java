package com.nx.mobilelink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
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

/**
 * MobileLink Activity:
 * Pure UPnP/DLNA photo & video browser and batch downloader.
 * Completely decoupled from Remote Viewfinder mode.
 */
public class MobileLinkActivity extends Activity {
    private static final String TAG = "MobileLinkActivity";

    private ImageButton btnBackToHome;
    private TextView txtStatusBadge;
    private Button btnOpenWifi;
    private Button btnScanCamera;
    private Button btnDirectIp;
    private ProgressBar progressScan;

    private LinearLayout layoutBatchControls;
    private Button btnSelectAll;
    private TextView txtSelectionCount;
    private Button btnDownloadSelected;

    private LinearLayout layoutDownloadProgress;
    private TextView txtDownloadStatus;
    private ProgressBar progressDownload;

    private ScrollView scrollEmptyInstructions;
    private GridView gridPhotos;

    private MobileLinkClient.DeviceInfo connectedDevice;
    private final List<PhotoItem> photoList = new ArrayList<>();
    private PhotoAdapter photoAdapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundPool = Executors.newFixedThreadPool(3);

    private boolean isScanning = false;
    private boolean isDownloading = false;
    private boolean allSelectedState = false;
    private int scanRetryCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobilelink);

        // Keep screen awake during photo transfers
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        NetworkManager.bindProcessToWifi(this);
        NetworkManager.registerWifiMonitoring(this, new NetworkManager.WifiListener() {
            @Override
            public void onWifiChanged(final boolean connected, String ssid, Network network) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateStatusBanner();
                        if (connected && connectedDevice == null && !isScanning) {
                            startCameraScan(null, 0);
                        }
                    }
                });
            }
        });
        updateStatusBanner();

        // Auto-scan if Wi-Fi is connected
        if (isConnectedToWifi()) {
            startCameraScan(null, 0);
        }
    }

    private void initViews() {
        btnBackToHome = findViewById(R.id.btn_back_to_home);
        txtStatusBadge = findViewById(R.id.txt_status_badge);
        btnOpenWifi = findViewById(R.id.btn_open_wifi);
        btnScanCamera = findViewById(R.id.btn_scan_camera);
        btnDirectIp = findViewById(R.id.btn_direct_ip);
        progressScan = findViewById(R.id.progress_scan);

        layoutBatchControls = findViewById(R.id.layout_batch_controls);
        btnSelectAll = findViewById(R.id.btn_select_all);
        txtSelectionCount = findViewById(R.id.txt_selection_count);
        btnDownloadSelected = findViewById(R.id.btn_download_selected);

        layoutDownloadProgress = findViewById(R.id.layout_download_progress);
        txtDownloadStatus = findViewById(R.id.txt_download_status);
        progressDownload = findViewById(R.id.progress_download);

        scrollEmptyInstructions = findViewById(R.id.scroll_empty_instructions);
        gridPhotos = findViewById(R.id.grid_photos);

        photoAdapter = new PhotoAdapter(this, photoList);
        gridPhotos.setAdapter(photoAdapter);

        // Navigation back to Home Screen
        btnBackToHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Top actions
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

        btnDirectIp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDirectIpDialog();
            }
        });

        // Batch selection
        btnSelectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                allSelectedState = !allSelectedState;
                photoAdapter.selectAll(allSelectedState);
                btnSelectAll.setText(allSelectedState ? R.string.btn_unselect_all : R.string.btn_select_all);
            }
        });

        photoAdapter.setOnSelectionChangeListener(new PhotoAdapter.OnSelectionChangeListener() {
            @Override
            public void onSelectionChanged(int selectedCount, int totalCount) {
                txtSelectionCount.setText(selectedCount + " of " + totalCount + " selected");
                btnDownloadSelected.setEnabled(selectedCount > 0);
            }
        });

        btnDownloadSelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBatchDownload();
            }
        });

        // Photo item click -> preview dialog
        gridPhotos.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < photoList.size()) {
                    PhotoItem item = photoList.get(position);
                    showPreviewDialog(item);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        NetworkManager.bindProcessToWifi(this);
        updateStatusBanner();
        if (connectedDevice == null && isConnectedToWifi() && !isScanning) {
            startCameraScan(null, 0);
        }
    }

    private boolean isConnectedToWifi() {
        return NetworkManager.isWifiConnected(this);
    }

    private String getCurrentWifiSsid() {
        return NetworkManager.getCurrentWifiSsid(this);
    }

    private void updateStatusBanner() {
        if (connectedDevice != null) {
            txtStatusBadge.setText("✓ " + connectedDevice.friendlyName + " (Connected)");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_connected);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_green_text));
        } else if (isConnectedToWifi()) {
            String ssid = getCurrentWifiSsid();
            String name = (ssid != null) ? "Connected to: " + ssid : "Wi-Fi Connected";
            txtStatusBadge.setText(name + " (Tap Connect Camera)");
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
            Toast.makeText(this, "Could not open Wi-Fi settings", Toast.LENGTH_SHORT).show();
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

        MobileLinkClient.discoverCamera(this, customIp, customPort, new MobileLinkClient.DiscoveryCallback() {
            @Override
            public void onDeviceFound(final MobileLinkClient.DeviceInfo device) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        scanRetryCount = 0;
                        connectedDevice = device;
                        MobileLinkClient.startKeepAlive(MobileLinkActivity.this, device);
                        updateStatusBanner();
                        Toast.makeText(MobileLinkActivity.this, "Camera found: " + device.friendlyName, Toast.LENGTH_SHORT).show();
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
                        updateStatusBanner();

                        if (connectedDevice == null && isConnectedToWifi() && scanRetryCount < 3) {
                            scanRetryCount++;
                            txtStatusBadge.setText("Retrying camera discovery (" + scanRetryCount + "/3)...");
                            txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
                            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (connectedDevice == null && isConnectedToWifi() && !isScanning) {
                                        Log.i(TAG, "Auto-retrying MobileLink discovery (" + scanRetryCount + "/3)...");
                                        startCameraScan(null, 0);
                                    }
                                }
                            }, 2500);
                        } else {
                            scanRetryCount = 0;
                            Toast.makeText(MobileLinkActivity.this, error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void loadPhotosFromCamera(final MobileLinkClient.DeviceInfo device) {
        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<PhotoItem> loaded = null;
                    final int maxAttempts = 20; // 30 seconds total waiting for user confirmation on camera LCD
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            loaded = MobileLinkClient.browsePhotos(device);
                            if (loaded != null && !loaded.isEmpty()) {
                                break;
                            }
                        } catch (Exception be) {
                            Log.w(TAG, "Browse attempt " + attempt + " error: " + be.getMessage());
                        }
                        if (attempt < maxAttempts) {
                            final int curAttempt = attempt;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtStatusBadge.setText("Waiting for photos... (Confirm 'Yes' on camera) [" + curAttempt + "/" + maxAttempts + "]");
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
                                txtSelectionCount.setText("0 of " + photoList.size() + " selected");
                                Toast.makeText(MobileLinkActivity.this, "Loaded " + photoList.size() + " photos", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MobileLinkActivity.this, "No photos found on camera SD card. If camera prompts 'Allow connection', press 'Yes' on camera!", Toast.LENGTH_LONG).show();
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
                            Toast.makeText(MobileLinkActivity.this, "Error loading photos: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

        List<String> ips = NetworkManager.getCandidateCameraIps(this);
        if (ips != null && !ips.isEmpty()) {
            editIp.setText(ips.get(0));
        } else {
            String gw = MobileLinkClient.getWifiGatewayIp(this);
            if (gw != null && !gw.isEmpty()) {
                editIp.setText(gw);
            }
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
            Toast.makeText(this, "Please select at least one photo to download", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isDownloading) return;
        isDownloading = true;
        MobileLinkClient.isDownloading.set(true);

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
                            MobileLinkClient.downloadPhoto(MobileLinkActivity.this, item, targetDir, null);
                            item.setDownloadStatus(PhotoItem.STATUS_COMPLETED);
                            successCount++;
                        } catch (Exception e) {
                            Log.e(TAG, "Error downloading " + item.getTitle(), e);
                            item.setDownloadStatus(PhotoItem.STATUS_ERROR);
                        }

                        // Flush delay: give camera storage and TCP stack 300ms to close socket cleanly
                        try {
                            Thread.sleep(300);
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
                    MobileLinkClient.isDownloading.set(false);
                }

                final int finalSuccess = successCount;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        isDownloading = false;
                        layoutDownloadProgress.setVisibility(View.GONE);
                        btnDownloadSelected.setEnabled(true);

                        new AlertDialog.Builder(MobileLinkActivity.this)
                                .setTitle("Download Complete!")
                                .setMessage(finalSuccess + " of " + selected.size() + " photos saved to Gallery (NX1000 folder).")
                                .setPositiveButton("OK", null)
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
        txtInfo.setText("Size: " + item.getFormattedSize() + " | Date: " + item.getDate());

        // Load preview
        imgPreview.setImageResource(R.drawable.thumb_placeholder);
        final String loadUrl = (item.getThumbUrl() != null) ? item.getThumbUrl() : item.getFullUrl();

        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bmp = HumbleHttpClient.downloadBitmap(
                            MobileLinkClient.getActiveWifiNetwork(),
                            loadUrl,
                            MobileLinkClient.getUserAgent()
                    );
                    if (bmp != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                imgPreview.setImageBitmap(bmp);
                            }
                        });
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
        Toast.makeText(this, "Downloading " + item.getTitle() + "...", Toast.LENGTH_SHORT).show();
        item.setDownloadStatus(PhotoItem.STATUS_DOWNLOADING);
        photoAdapter.notifyDataSetChanged();

        backgroundPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File targetDir = getDownloadDirectory();
                    MobileLinkClient.downloadPhoto(MobileLinkActivity.this, item, targetDir, null);
                    item.setDownloadStatus(PhotoItem.STATUS_COMPLETED);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            photoAdapter.notifyDataSetChanged();
                            Toast.makeText(MobileLinkActivity.this, item.getTitle() + " saved to Gallery!", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    item.setDownloadStatus(PhotoItem.STATUS_ERROR);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            photoAdapter.notifyDataSetChanged();
                            Toast.makeText(MobileLinkActivity.this, "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

    @Override
    protected void onDestroy() {
        MobileLinkClient.stopKeepAlive();
        super.onDestroy();
    }
}
