/*
 * Copyright (c) 2026 FL4K901. All rights reserved.
 * SPDX-License-Identifier: MIT
 *
 * This software is licensed under the MIT License.
 * See LICENSE file in the project root for full license terms.
 */

package com.nx.mobilelink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

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
    private Button btnDirectIp;

    private ImageView imgPreview;
    private LinearLayout layoutPlaceholder;
    private TextView txtInstructions;
    private FrameLayout btnShutter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isConnecting = false;
    private String connectedCameraIp = null;
    private boolean isFocusLocked = false;

    private final Runnable focusReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFocusLocked && connectedCameraIp != null) {
                isFocusLocked = false;
                ViewfinderClient.releaseFocus(connectedCameraIp);
                updateStatusBanner();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewfinder);

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
                        if (connected && connectedCameraIp == null && !isConnecting) {
                            startConnection(null);
                        }
                    }
                });
            }
        });
        updateStatusBanner();

        // Auto-connect if Wi-Fi is active
        if (isConnectedToWifi()) {
            startConnection(null);
        }
    }

    private void initViews() {
        btnBackToHome = findViewById(R.id.btn_rvf_back_to_home);
        txtStatusBadge = findViewById(R.id.txt_rvf_status_badge);
        progressScan = findViewById(R.id.progress_rvf_scan);
        btnConnect = findViewById(R.id.btn_rvf_connect);
        btnWifi = findViewById(R.id.btn_rvf_wifi);
        btnDirectIp = findViewById(R.id.btn_rvf_ip);

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
                startConnection(null);
            }
        });

        if (btnDirectIp != null) {
            btnDirectIp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDirectIpDialog();
                }
            });
        }

        btnShutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShutterClicked();
            }
        });

        // Tap on viewfinder viewport triggers autofocus
        imgPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (connectedCameraIp == null) return;

                mainHandler.removeCallbacks(focusReleaseRunnable);
                txtStatusBadge.setText("🔍 Autofocusing...");
                txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
                txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));

                ViewfinderClient.triggerAutoFocus(connectedCameraIp, new ViewfinderClient.AutoFocusCallback() {
                    @Override
                    public void onAutoFocusSuccess(final String afStatus) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                isFocusLocked = true;
                                txtStatusBadge.setText("🟢 Focus Locked");
                                txtStatusBadge.setBackgroundResource(R.drawable.badge_connected);
                                txtStatusBadge.setTextColor(getResources().getColor(R.color.status_green_text));
                                Toast.makeText(ViewfinderActivity.this, "Focus locked", Toast.LENGTH_SHORT).show();

                                // Protocol rule: Auto-release focus after 8 seconds if no photo is taken
                                mainHandler.postDelayed(focusReleaseRunnable, 8000);
                            }
                        });
                    }

                    @Override
                    public void onAutoFocusFailed(final String error) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                isFocusLocked = false;
                                txtStatusBadge.setText("🟡 Focus Hunt");
                                ViewfinderClient.releaseFocus(connectedCameraIp);
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        NetworkManager.bindProcessToWifi(this);
        updateStatusBanner();
        if (connectedCameraIp == null && isConnectedToWifi() && !isConnecting) {
            startConnection(null);
        }
    }

    private boolean isConnectedToWifi() {
        return NetworkManager.isWifiConnected(this);
    }

    private String getCurrentWifiSsid() {
        return NetworkManager.getCurrentWifiSsid(this);
    }

    private void updateStatusBanner() {
        if (connectedCameraIp != null) {
            String shots = ViewfinderClient.getAvailableShots();
            String shotsInfo = (shots != null && !shots.isEmpty()) ? " | Shots: " + shots : "";
            txtStatusBadge.setText("✓ Connected - Live Stream Active (" + connectedCameraIp + shotsInfo + ")");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_connected);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_green_text));
        } else if (isConnectedToWifi()) {
            String ssid = getCurrentWifiSsid();
            String name = (ssid != null) ? "Connected to: " + ssid : "Wi-Fi Connected";
            txtStatusBadge.setText(name + " (Tap Connect Camera)");
            txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
            txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));
        } else {
            txtStatusBadge.setText("Not connected to camera Wi-Fi");
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

    private void showDirectIpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ip, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();

        final EditText editIp = dialogView.findViewById(R.id.edit_camera_ip);
        final EditText editPort = dialogView.findViewById(R.id.edit_camera_port);
        Button btnCancel = dialogView.findViewById(R.id.dialog_ip_cancel);
        Button btnDialogConnect = dialogView.findViewById(R.id.dialog_ip_connect);

        List<String> ips = NetworkManager.getCandidateCameraIps(this);
        if (ips != null && !ips.isEmpty()) {
            editIp.setText(ips.get(0));
        } else {
            String gw = ViewfinderClient.getWifiGatewayIp(this);
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

        btnDialogConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = editIp.getText().toString().trim();
                dialog.dismiss();
                if (!ip.isEmpty()) {
                    startConnection(ip);
                }
            }
        });

        dialog.show();
    }

    private void startConnection(final String customIp) {
        if (isConnecting) return;
        isConnecting = true;

        progressScan.setVisibility(View.VISIBLE);
        btnConnect.setEnabled(false);
        txtStatusBadge.setText("Connecting to Remote Viewfinder...");
        txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
        txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));

        // Safety watchdog: reset isConnecting after 15 seconds if neither callback fired
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnecting && connectedCameraIp == null) {
                    isConnecting = false;
                    progressScan.setVisibility(View.GONE);
                    btnConnect.setEnabled(true);
                    updateStatusBanner();
                }
            }
        }, 15000);

        ViewfinderClient.connect(this, customIp, new ViewfinderClient.ConnectionCallback() {
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
                        Toast.makeText(ViewfinderActivity.this, "Connected to Remote Viewfinder!", Toast.LENGTH_SHORT).show();

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
            Toast.makeText(this, "Please connect to camera first", Toast.LENGTH_SHORT).show();
            return;
        }

        mainHandler.removeCallbacks(focusReleaseRunnable);
        isFocusLocked = false;

        btnShutter.setEnabled(false);
        btnShutter.setAlpha(0.5f);
        txtStatusBadge.setText("🔍 Focusing & Capturing photo...");
        txtStatusBadge.setBackgroundResource(R.drawable.badge_searching);
        txtStatusBadge.setTextColor(getResources().getColor(R.color.status_orange_text));
        Toast.makeText(this, "Focusing & Capturing photo...", Toast.LENGTH_SHORT).show();

        // Performs full, synchronized capture flow per protocol guide:
        // MULTIAF -> Stop Live Stream -> Shot -> Resume Live Stream -> Download full image
        ViewfinderClient.triggerShutter(connectedCameraIp, true, new ViewfinderClient.ShutterCallback() {
            @Override
            public void onSuccess(final String savedFilePath) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnShutter.setEnabled(true);
                        btnShutter.setAlpha(1.0f);
                        String fileName = (savedFilePath != null) ? new File(savedFilePath).getName() : "Success";
                        txtStatusBadge.setText("🟢 Saved: " + fileName);
                        txtStatusBadge.setBackgroundResource(R.drawable.badge_connected);
                        txtStatusBadge.setTextColor(getResources().getColor(R.color.status_green_text));
                        Toast.makeText(ViewfinderActivity.this, "Photo captured & saved to Gallery!", Toast.LENGTH_LONG).show();
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
                        txtStatusBadge.setText("⚠️ Capture: " + error);
                        txtStatusBadge.setBackgroundResource(R.drawable.badge_disconnected);
                        txtStatusBadge.setTextColor(getResources().getColor(R.color.status_red_text));
                        Toast.makeText(ViewfinderActivity.this, "Capture notice: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(focusReleaseRunnable);
        if (isFocusLocked && connectedCameraIp != null) {
            isFocusLocked = false;
            ViewfinderClient.releaseFocus(connectedCameraIp);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(focusReleaseRunnable);
        if (connectedCameraIp != null) {
            ViewfinderClient.releaseFocus(connectedCameraIp);
        }
        ViewfinderClient.disconnect();
        super.onDestroy();
    }
}
