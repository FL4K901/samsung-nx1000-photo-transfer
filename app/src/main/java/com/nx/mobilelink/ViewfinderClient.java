/*
 * Copyright (c) 2026 FL4K901. All rights reserved.
 * SPDX-License-Identifier: MIT
 *
 * This software is licensed under the MIT License.
 * See LICENSE file in the project root for full license terms.
 */

package com.nx.mobilelink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Handles communication with Samsung NX1000 / Smart Camera in "Remote Viewfinder" mode.
 *
 * Implements the complete protocol specifications from Samsung Camera Remote Protocol Guide:
 * 1. SSDP Discovery & Handshake:
 *    - UDP Multicast M-SEARCH on 239.255.255.250:1900 with ST: ssdp:all
 *    - Device Description XML fetch with mandatory User-Agent: SEC_RVF_<DEVICE_ID>
 *      (where DEVICE_ID is uppercase alphanumeric MAC without colons, or SEC_RVF_UNKNOWN)
 *    - Dynamic extraction of serviceType and controlURL from the control service
 *    - SSDP NOTIFY readiness monitoring (urn:schemas-upnp-org:service:ConnectionManager:1)
 * 2. GetInfomation SOAP Action:
 *    - Queries camera parameters: resolutions, flash modes, zoom info, available shots, rotation
 *    - Extracts dynamic live stream URL from <GETIPRESULT>
 * 3. Live View Video Stream:
 *    - HTTP GET with User-Agent: Lavf52.103.0
 *    - Hardware/Software decoding of AVI (H.264 / MPEG-4 / MJPEG), MP4, or Annex-B streams
 *    - Real-time dynamic rotation handling (1=D 0°, 2=L 270°, 3=U 180°, 4=R 90° and ssdp:rotation*)
 * 4. Autofocus & Shutter Architecture (Strict Serialization & Hardware Safety):
 *    - Strict command serialization via dedicated reentrant lock (one command at a time)
 *    - Extended timeouts (30-35s) for physical lens motor movement and SD card writes
 *    - Pre-shot live stream pausing to release camera DSP/hardware memory for full-res capture
 *    - MULTIAF autofocus triggering with proper AFSTATUS inspection
 *    - Focus cancellation via ReleaseSelfTimer (<RELEASETIMER>0</RELEASETIMER>) to prevent camera lockups
 *    - Automatic full-resolution photo & thumbnail downloading to DCIM/NX_MobileLink with MediaScanner registration
 */
public class ViewfinderClient {

    private static final String TAG = "ViewfinderClient";

    public static final int SSDP_PORT_MOBILELINK = 1901;
    public static final int SSDP_PORT_STANDARD = 1900;
    public static final int SSDP_SEARCH_RESPONSE_PORT = 8009;
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    // Default UPnP service type fallback used by Samsung Remote Viewfinder
    public static final String UPNP_SERVICE_CONTENT_DIR = "urn:schemas-upnp-org:service:ContentDirectory:1";

    private static final int[] CANDIDATE_UPNP_PORTS = {
        7676, 7678, 7679, 52235, 49152, 49153, 49154, 8080, 80
    };

    private static final String[] CANDIDATE_CONTROL_PATHS = {
        "/smp_4_",
        "/smp_3_",
        "/smp_7_",
        "/upnp/control/ContentDirectory1",
        "/ContentDirectory/control",
        "/ctl/ContentDir"
    };

    public interface ConnectionCallback {
        void onConnected(String cameraIp);
        void onConnectionFailed(String error);
    }

    public interface FrameCallback {
        void onFrame(Bitmap frame);
        void onStreamError(String error);
    }

    public interface ShutterCallback {
        void onSuccess(String savedFilePath);
        void onError(String error);
    }

    public interface AutoFocusCallback {
        void onAutoFocusSuccess(String afStatus);
        void onAutoFocusFailed(String error);
    }

    public interface ActionCallback {
        void onResult(boolean success, String response);
    }

    public static class UpnpEndpoint {
        public final String host;
        public final int port;
        public final String controlPath;
        public final String serviceType;

        public UpnpEndpoint(String host, int port, String controlPath, String serviceType) {
            this.host = host;
            this.port = port;
            this.controlPath = controlPath.startsWith("/") ? controlPath : "/" + controlPath;
            this.serviceType = (serviceType != null && !serviceType.isEmpty()) ? serviceType : UPNP_SERVICE_CONTENT_DIR;
        }
    }

    // Strict command serialization: only ONE SOAP command executes at any given moment
    private static final ReentrantLock commandLock = new ReentrantLock(true);

    private static final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static Thread streamThread;
    private static volatile Socket activeStreamSocket;
    private static volatile FrameCallback lastFrameCallback;

    private static volatile Context appContext;
    private static volatile Network activeWifiNetwork;
    private static volatile String activeCameraIp;
    private static volatile String activeStreamUrl;
    private static volatile UpnpEndpoint primaryEndpoint = null;
    private static volatile List<UpnpEndpoint> activeEndpoints = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Camera state parsed from GetInfomation
    private static volatile String currentFlash = "off";
    private static volatile int currentZoom = 1;
    private static volatile int maxZoom = 1;
    private static volatile int currentWidth = 0;
    private static volatile int currentHeight = 0;
    // Rotation state: 1=D (0°), 2=L (270°), 3=U (180°), 4=R (90°)
    private static volatile int currentRotation = 1;
    private static volatile String availableShots = "";
    private static volatile String lastCapturedFileUrl = "";

    public static String getWifiGatewayIp(Context context) {
        try {
            appContext = context.getApplicationContext();
            WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                    String gw = Formatter.formatIpAddress(dhcpInfo.gateway);
                    if (gw != null && !gw.equals("0.0.0.0")) {
                        return gw;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get gateway IP", e);
        }
        List<String> candidates = NetworkManager.getCandidateCameraIps(context);
        if (candidates != null && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        return "192.168.107.1";
    }

    private static String userAgent;

    /**
     * Exact User-Agent required by Samsung Remote Viewfinder firmware:
     * "SEC_RVF_" + 12-hex-character MAC address without colons (20 chars total).
     * Generated using Samsung OUI prefix (485D60) and persisted in SharedPreferences.
     */
    public static synchronized String getUserAgent() {
        if (userAgent != null && userAgent.startsWith("SEC_RVF_") && userAgent.length() == 20) {
            return userAgent;
        }
        String id = null;
        if (appContext != null) {
            try {
                android.content.SharedPreferences sp = appContext.getSharedPreferences("nx_link", Context.MODE_PRIVATE);
                id = sp.getString("rvf_id", null);
                if (id == null || id.length() != 12) {
                    id = sp.getString("dsc_id", null);
                }
                if (id == null || id.length() != 12) {
                    id = generate12HexMac();
                    sp.edit().putString("rvf_id", id).apply();
                }
            } catch (Exception ignored) {}
        }
        if (id == null || id.length() != 12) {
            id = generate12HexMac();
        }
        userAgent = "SEC_RVF_" + id.toUpperCase(Locale.US);
        Log.i(TAG, "Remote Viewfinder User-Agent: " + userAgent);
        return userAgent;
    }

    private static String generate12HexMac() {
        try {
            NetworkInterface wlan = NetworkInterface.getByName("wlan0");
            if (wlan != null) {
                byte[] mac = wlan.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : mac) sb.append(String.format(Locale.US, "%02X", b));
                    String s = sb.toString();
                    if (!"000000000000".equals(s) && !"020000000000".equals(s)) {
                        return s;
                    }
                }
            }
        } catch (Exception ignored) {}
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder("485D60");
        for (int i = 0; i < 3; i++) {
            sb.append(String.format(Locale.US, "%02X", r.nextInt(256)));
        }
        return sb.toString();
    }

    public static Socket createSocket() throws Exception {
        Socket s = new Socket();
        Network net = NetworkManager.getActiveWifiNetwork();
        if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                net.bindSocket(s);
            } catch (Exception ignored) {}
        }
        return s;
    }

    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        try {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getLocalWifiIp(Context context) {
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.107.100";
    }

    public static synchronized void setActiveWifiNetwork(Network network) {
        activeWifiNetwork = network;
    }

    public static void connect(final Context context, final String cameraIp, final ConnectionCallback callback) {
        appContext = context.getApplicationContext();
        activeWifiNetwork = NetworkManager.bindProcessToWifi(appContext);
        NetworkManager.acquireLocks(appContext);
        NetworkManager.ensureUpnpEventServer();
        connect(cameraIp, callback);
    }

    public static void connect(final String cameraIp, final ConnectionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (appContext != null) {
                    activeWifiNetwork = NetworkManager.bindProcessToWifi(appContext);
                    NetworkManager.acquireLocks(appContext);
                    NetworkManager.ensureUpnpEventServer();
                }

                primaryEndpoint = null;
                activeEndpoints.clear();
                activeStreamUrl = null;

                final AtomicBoolean resolved = new AtomicBoolean(false);
                final Set<String> attemptedLocations = Collections.synchronizedSet(new java.util.HashSet<String>());

                final List<String> candidateIps = new ArrayList<>();
                if (cameraIp != null && !cameraIp.trim().isEmpty()) {
                    candidateIps.add(cameraIp.trim());
                }
                if (appContext != null) {
                    for (String ip : NetworkManager.getCandidateCameraIps(appContext)) {
                        if (!candidateIps.contains(ip)) candidateIps.add(ip);
                    }
                }
                if (candidateIps.isEmpty()) {
                    candidateIps.add("192.168.107.1");
                }
                activeCameraIp = candidateIps.get(0);

                final int[] candidatePorts = new int[]{7676, 52235, 49152, 7678, 80};
                final long startTime = System.currentTimeMillis();
                final long deadline = startTime + 10000;

                // 1. Multicast NOTIFY listener on port 1901 (Samsung MobileLink/RVF port)
                Thread notify1901Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listenSsdpMulticastNotify(SSDP_PORT_MOBILELINK, 8500, resolved, attemptedLocations);
                    }
                }, "NX-RVF-SSDP-Notify-1901");
                notify1901Thread.start();

                // 2. Multicast NOTIFY listener on port 1900 (Standard UPnP port)
                Thread notify1900Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listenSsdpMulticastNotify(SSDP_PORT_STANDARD, 8500, resolved, attemptedLocations);
                    }
                }, "NX-RVF-SSDP-Notify-1900");
                notify1900Thread.start();

                // 3. Active M-SEARCH sender & response listener on port 8009
                Thread searchThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        performSsdpSearchLoop(8500, candidateIps, resolved, attemptedLocations);
                    }
                }, "NX-RVF-SSDP-Search-8009");
                searchThread.start();

                // 4. Give SSDP priority for 1.8s (unless user specified custom cameraIp)
                if (cameraIp == null || cameraIp.trim().isEmpty()) {
                    long ssdpPriorityEnd = startTime + 1800;
                    while (!resolved.get() && System.currentTimeMillis() < ssdpPriorityEnd) {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) { break; }
                    }
                }

                // 5. Active TCP probe fallback
                while (!resolved.get() && System.currentTimeMillis() < deadline) {
                    for (String ip : candidateIps) {
                        if (resolved.get()) break;
                        for (int p : candidatePorts) {
                            if (resolved.get()) break;
                            if (isPortOpen(ip, p, 250)) {
                                Log.i(TAG, "Port " + p + " is open on " + ip + "! Probing UPnP description...");
                                if (probeHttpEndpoint(ip, p, attemptedLocations, resolved)) {
                                    break;
                                }
                            }
                        }
                    }
                    if (resolved.get()) break;
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { break; }
                }

                notify1901Thread.interrupt();
                notify1900Thread.interrupt();
                searchThread.interrupt();

                // 6. Execute GetInfomation SOAP Action in a retry loop
                boolean infoSuccess = false;
                if (resolved.get() || !activeEndpoints.isEmpty() || primaryEndpoint != null) {
                    for (int attempt = 1; attempt <= 15; attempt++) {
                        if (executeGetInformation(activeCameraIp)) {
                            infoSuccess = true;
                            Log.i(TAG, "GetInfomation succeeded on attempt " + attempt + " (streamUrl=" + activeStreamUrl + ")");
                            break;
                        }
                        try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                    }
                }

                if (infoSuccess && activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                    callback.onConnected(activeCameraIp);
                } else if (infoSuccess) {
                    activeStreamUrl = "http://" + activeCameraIp + ":7678/stream.avi";
                    callback.onConnected(activeCameraIp);
                } else {
                    callback.onConnectionFailed("Camera not found in Remote Viewfinder mode. Please make sure the camera is in Remote Viewfinder mode and your phone is connected to its Wi-Fi.");
                }
            }
        }, "NX-RVF-ConnectThread").start();
    }

    private static void listenSsdpMulticastNotify(int port, int timeoutMs, AtomicBoolean resolved, Set<String> attemptedLocations) {
        MulticastSocket mSock = null;
        try {
            mSock = new MulticastSocket(null);
            mSock.setReuseAddress(true);
            mSock.bind(new InetSocketAddress(port));

            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            NetworkInterface wifiIf = NetworkManager.getWifiNetworkInterface(appContext);
            if (wifiIf != null) {
                try {
                    mSock.joinGroup(new InetSocketAddress(group, port), wifiIf);
                } catch (Exception e) {
                    mSock.joinGroup(group);
                }
            } else {
                mSock.joinGroup(group);
            }

            mSock.setSoTimeout(500);
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;

            while (!resolved.get() && System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mSock.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    checkSsdpRotation(msg);
                    String nts = extractHttpHeader(msg, "NTS");
                    if (nts != null && nts.toLowerCase(Locale.US).contains("byebye")) {
                        continue;
                    }
                    String location = extractHttpHeader(msg, "LOCATION");
                    if (location != null && !location.isEmpty() && attemptedLocations.add(location)) {
                        Log.i(TAG, "SSDP NOTIFY on port " + port + " -> LOCATION: " + location);
                        if (fetchAndParseDeviceDescription(location)) {
                            resolved.set(true);
                            return;
                        }
                    }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {
            Log.d(TAG, "Multicast listener on port " + port + " notice: " + e.getMessage());
        } finally {
            if (mSock != null && !mSock.isClosed()) {
                try { mSock.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void performSsdpSearchLoop(int timeoutMs, List<String> candidateIps, AtomicBoolean resolved, Set<String> attemptedLocations) {
        DatagramSocket socket = null;
        try {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(SSDP_SEARCH_RESPONSE_PORT));
            } catch (Exception e) {
                socket = new DatagramSocket();
            }

            Network net = NetworkManager.getActiveWifiNetwork();
            if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { net.bindSocket(socket); } catch (Exception ignored) {}
            }
            socket.setSoTimeout(500);

            String ua = getUserAgent();
            byte[] search1901All = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT_MOBILELINK + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: ssdp:all\r\n" +
                    "USER-AGENT: " + ua + "\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            byte[] search1901Root = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT_MOBILELINK + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: upnp:rootdevice\r\n" +
                    "USER-AGENT: " + ua + "\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            byte[] search1900All = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT_STANDARD + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: ssdp:all\r\n" +
                    "USER-AGENT: " + ua + "\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            byte[] search1900Root = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT_STANDARD + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: upnp:rootdevice\r\n" +
                    "USER-AGENT: " + ua + "\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            InetAddress multicastGroup = InetAddress.getByName(SSDP_ADDRESS);
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSendTime = 0;

            while (!resolved.get() && System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextSendTime) {
                    nextSendTime = now + 1000;
                    try { socket.send(new DatagramPacket(search1901All, search1901All.length, multicastGroup, SSDP_PORT_MOBILELINK)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1901Root, search1901Root.length, multicastGroup, SSDP_PORT_MOBILELINK)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1900All, search1900All.length, multicastGroup, SSDP_PORT_STANDARD)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1900Root, search1900Root.length, multicastGroup, SSDP_PORT_STANDARD)); } catch (Exception ignored) {}

                    if (candidateIps != null) {
                        for (int i = 0; i < Math.min(candidateIps.size(), 4); i++) {
                            try {
                                InetAddress cip = InetAddress.getByName(candidateIps.get(i));
                                socket.send(new DatagramPacket(search1901All, search1901All.length, cip, SSDP_PORT_MOBILELINK));
                                socket.send(new DatagramPacket(search1900All, search1900All.length, cip, SSDP_PORT_STANDARD));
                            } catch (Exception ignored) {}
                        }
                    }
                }

                try {
                    DatagramPacket recv = new DatagramPacket(buf, buf.length);
                    socket.receive(recv);
                    String resp = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8);
                    checkSsdpRotation(resp);
                    String location = extractHttpHeader(resp, "LOCATION");
                    if (location != null && !location.isEmpty() && attemptedLocations.add(location)) {
                        Log.i(TAG, "SSDP M-SEARCH response -> LOCATION: " + location);
                        if (fetchAndParseDeviceDescription(location)) {
                            resolved.set(true);
                            return;
                        }
                    }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {
            Log.d(TAG, "SSDP search loop notice: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean fetchAndParseDeviceDescription(String locationUrl) {
        if (!isSafeCameraUrl(locationUrl)) return false;
        try {
            URL url = new URL(locationUrl);
            String host = url.getHost();
            int port = url.getPort() != -1 ? url.getPort() : 80;
            activeCameraIp = host;
            String localIp = getLocalWifiIp(appContext);

            String xml = HumbleHttpClient.httpGetXml(activeWifiNetwork, locationUrl, getUserAgent(), 3000);
            if (xml != null && !xml.isEmpty()) {
                parseDeviceDescriptionXml(xml, locationUrl, host, port, localIp);
                return !activeEndpoints.isEmpty() || primaryEndpoint != null;
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchAndParseDeviceDescription notice: " + e.getMessage());
        }
        return false;
    }

    private static boolean probeHttpEndpoint(String ip, int port, Set<String> attemptedLocations, AtomicBoolean resolved) {
        String[] paths = new String[]{
                "/smp_2_",
                "/smp_6_",
                "/description.xml",
                "/rootDesc.xml",
                "/smp_4_",
                "/smp_10_",
                "/upnp/ContentDirectory.xml"
        };
        for (String p : paths) {
            String testUrl = "http://" + ip + ":" + port + p;
            if (!attemptedLocations.add(testUrl)) continue;
            if (fetchAndParseDeviceDescription(testUrl)) {
                resolved.set(true);
                return true;
            }
        }
        // Direct SOAP probing if description paths failed
        String[] directControlPaths = {"/smp_4_", "/smp_3_", "/smp_7_", "/smp_2_"};
        String serviceType = UPNP_SERVICE_CONTENT_DIR;
        String soapBody = buildSoapEnvelope(serviceType, "GetInfomation", null);
        String soapAction = "\"" + serviceType + "#GetInfomation\"";
        for (String cp : directControlPaths) {
            String url = "http://" + ip + ":" + port + cp;
            String response = sendSoapRequest(url, soapAction, soapBody, 1500);
            if (response != null && parseGetInformationResponse(response, ip, port)) {
                UpnpEndpoint ep = new UpnpEndpoint(ip, port, cp, serviceType);
                primaryEndpoint = ep;
                activeEndpoints.add(0, ep);
                resolved.set(true);
                return true;
            }
        }
        return false;
    }

    private static void checkSsdpRotation(String msg) {
        if (msg == null) return;
        if (msg.contains("ssdp:rotationD")) {
            currentRotation = 1;
        } else if (msg.contains("ssdp:rotationL")) {
            currentRotation = 2;
        } else if (msg.contains("ssdp:rotationU")) {
            currentRotation = 3;
        } else if (msg.contains("ssdp:rotationR")) {
            currentRotation = 4;
        }
    }

    private static NetworkInterface findWifiNetworkInterface(String localIp) {
        try {
            if (localIp != null && !localIp.isEmpty()) {
                InetAddress addr = InetAddress.getByName(localIp);
                NetworkInterface nif = NetworkInterface.getByInetAddress(addr);
                if (nif != null) return nif;
            }
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface nif = en.nextElement();
                if (nif.isUp() && !nif.isLoopback() && nif.getName().toLowerCase(Locale.US).contains("wlan")) {
                    return nif;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static DocumentBuilderFactory createSecureDocumentBuilderFactory() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
        } catch (Exception ignored) {}
        return dbf;
    }

    private static boolean isSafeCameraUrl(String candidateUrl) {
        if (candidateUrl == null || candidateUrl.isEmpty()) return false;
        try {
            URL cand = new URL(candidateUrl);
            if (!"http".equalsIgnoreCase(cand.getProtocol())) return false;
            String host = cand.getHost();
            if (host == null || host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("0.0.0.0")) {
                return false;
            }
            return host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean handleSsdpPacket(String packetText, String senderIp, String localIp) {
        String location = extractHttpHeader(packetText, "LOCATION");
        if (location == null || location.isEmpty() || !isSafeCameraUrl(location)) {
            return false;
        }
        Log.i(TAG, "SSDP packet from " + senderIp + " LOCATION: " + location);
        try {
            URL url = new URL(location);
            String host = url.getHost();
            int port = url.getPort() != -1 ? url.getPort() : 80;
            activeCameraIp = host;

            // Fetch Device Description XML using exact User-Agent: SEC_RVF_<DEVICE_ID>
            // The Samsung camera's HTTP server checks this User-Agent to transition out of the "Waiting" screen!
            String xml = HumbleHttpClient.httpGetXml(activeWifiNetwork, location, getUserAgent(), 4000);
            if (xml != null && !xml.isEmpty()) {
                parseDeviceDescriptionXml(xml, location, host, port, localIp);
                return !activeEndpoints.isEmpty();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to process SSDP LOCATION " + location + ": " + e.getMessage());
        }
        return false;
    }

    private static String extractHttpHeader(String rawHttp, String headerName) {
        String[] lines = rawHttp.split("\r\n|\n");
        String prefix = headerName.toLowerCase(Locale.US) + ":";
        for (String line : lines) {
            if (line.toLowerCase(Locale.US).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * Parses UPnP Device Description XML using DOM (matching Samsung RVF MyControlPoint.java),
     * fetches every <SCPDURL> with User-Agent: SEC_RVF_<DEVICE_ID>, sends UPnP SUBSCRIBE to <eventSubURL>,
     * and prioritizes the service containing GetInfomation / Shot / MULTIAF.
     */
    private static void parseDeviceDescriptionXml(String xml, String locationUrl, String defaultHost, int defaultPort, String localIp) {
        try {
            String cleanXml = xml.trim();
            int firstBracket = cleanXml.indexOf('<');
            if (firstBracket > 0) {
                cleanXml = cleanXml.substring(firstBracket);
            }

            DocumentBuilderFactory factory = createSecureDocumentBuilderFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(cleanXml.getBytes(StandardCharsets.UTF_8)));

            String baseHost = defaultHost;
            int basePort = defaultPort;

            NodeList urlBaseNodes = doc.getElementsByTagName("URLBase");
            if (urlBaseNodes.getLength() > 0) {
                String urlBase = urlBaseNodes.item(0).getTextContent().trim();
                if (!urlBase.isEmpty() && isSafeCameraUrl(urlBase)) {
                    try {
                        URL parsedBase = new URL(urlBase);
                        baseHost = parsedBase.getHost();
                        if (parsedBase.getPort() != -1) {
                            basePort = parsedBase.getPort();
                        }
                    } catch (Exception ignored) {}
                }
            }

            String baseUrl = "http://" + baseHost + ":" + basePort;
            List<UpnpEndpoint> prioritized = new ArrayList<>();
            List<UpnpEndpoint> fallbacks = new ArrayList<>();

            NodeList serviceNodes = doc.getElementsByTagName("service");
            for (int i = 0; i < serviceNodes.getLength(); i++) {
                Element serviceEl = (Element) serviceNodes.item(i);
                String serviceType = getChildElementText(serviceEl, "serviceType");
                String controlUrl = getChildElementText(serviceEl, "controlURL");
                String scpdUrl = getChildElementText(serviceEl, "SCPDURL");
                String eventSubUrl = getChildElementText(serviceEl, "eventSubURL");

                boolean hasGetInfoOrShot = false;

                // Mandatory: Fetch SCPDURL so the camera's HTTP server sees that our control point inspected the service
                if (scpdUrl != null && !scpdUrl.isEmpty()) {
                    String fullScpd = resolveRelativeUrl(baseUrl, locationUrl, scpdUrl);
                    if (isSafeCameraUrl(fullScpd)) {
                        try {
                            String scpdXml = HumbleHttpClient.httpGetXml(activeWifiNetwork, fullScpd, getUserAgent(), 3000);
                            if (scpdXml != null && (scpdXml.contains("GetInfomation") || scpdXml.contains("Shot") || scpdXml.contains("MULTIAF"))) {
                                hasGetInfoOrShot = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // Send UPnP SUBSCRIBE to eventSubURL (port 8059 listener)
                if (eventSubUrl != null && !eventSubUrl.isEmpty()) {
                    String fullEventSub = resolveRelativeUrl(baseUrl, locationUrl, eventSubUrl);
                    if (isSafeCameraUrl(fullEventSub)) {
                        try {
                            sendUpnpSubscribe(fullEventSub, localIp, 8059);
                        } catch (Exception ignored) {}
                    }
                }

                if (controlUrl != null && !controlUrl.isEmpty()) {
                    String normControl = controlUrl;
                    int epPort = basePort;
                    String epHost = baseHost;
                    if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) {
                        try {
                            URL u = new URL(controlUrl);
                            epHost = u.getHost();
                            epPort = (u.getPort() != -1) ? u.getPort() : 80;
                            normControl = u.getPath();
                        } catch (Exception ignored) {}
                    } else if (!controlUrl.startsWith("/")) {
                        normControl = "/" + controlUrl;
                    }

                    String st = (serviceType != null && !serviceType.isEmpty()) ? serviceType : UPNP_SERVICE_CONTENT_DIR;
                    UpnpEndpoint ep = new UpnpEndpoint(epHost, epPort, normControl, st);

                    if (hasGetInfoOrShot || st.contains("ContentDirectory")) {
                        prioritized.add(ep);
                    } else if (!st.contains("ConnectionManager")) {
                        fallbacks.add(ep);
                    }
                    Log.i(TAG, "Discovered UPnP service: " + st + " control=" + normControl + " port=" + epPort + " primary=" + hasGetInfoOrShot);
                }
            }

            activeEndpoints.clear();
            activeEndpoints.addAll(prioritized);
            activeEndpoints.addAll(fallbacks);
            if (!prioritized.isEmpty()) {
                primaryEndpoint = prioritized.get(0);
            } else if (!activeEndpoints.isEmpty()) {
                primaryEndpoint = activeEndpoints.get(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "DOM parseDeviceDescriptionXml notice: " + e.getMessage());
        }
    }

    private static String getChildElementText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element el = (Element) children.item(i);
                String name = el.getTagName();
                if (name.equalsIgnoreCase(tagName) || name.toLowerCase(Locale.US).endsWith(":" + tagName.toLowerCase(Locale.US))) {
                    return el.getTextContent().trim();
                }
            }
        }
        return null;
    }

    private static String resolveRelativeUrl(String baseUrl, String locationUrl, String relativePath) {
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath;
        }
        if (relativePath.startsWith("/")) {
            return baseUrl + relativePath;
        }
        if (locationUrl != null) {
            int lastSlash = locationUrl.lastIndexOf('/');
            if (lastSlash > 7) {
                return locationUrl.substring(0, lastSlash + 1) + relativePath;
            }
        }
        return baseUrl + "/" + relativePath;
    }

    /**
     * Constructs a valid UPnP SOAP 1.1 request envelope.
     */
    public static String buildSoapEnvelope(String serviceType, String actionName, String argsXml) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
               "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
               "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
               "  <s:Body>\r\n" +
               "    <u:" + actionName + " xmlns:u=\"" + serviceType + "\">\r\n" +
               (argsXml != null && !argsXml.isEmpty() ? ("      " + argsXml + "\r\n") : "") +
               "    </u:" + actionName + ">\r\n" +
               "  </s:Body>\r\n" +
               "</s:Envelope>\r\n";
    }

    /**
     * Executes the exact SOAP action "GetInfomation" (Samsung's spelling without 'r')
     * on the discovered control endpoint.
     */
    private static boolean executeGetInformation(String cameraIp) {
        commandLock.lock();
        try {
            List<UpnpEndpoint> snapshot = new ArrayList<>();
            if (primaryEndpoint != null) {
                snapshot.add(primaryEndpoint);
            }
            for (UpnpEndpoint ep : activeEndpoints) {
                if (!snapshot.contains(ep)) snapshot.add(ep);
            }

            for (UpnpEndpoint ep : snapshot) {
                String serviceType = ep.serviceType != null ? ep.serviceType : UPNP_SERVICE_CONTENT_DIR;
                String soapBody = buildSoapEnvelope(serviceType, "GetInfomation", null);
                String soapAction = "\"" + serviceType + "#GetInfomation\"";
                String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;

                String response = sendSoapRequest(url, soapAction, soapBody, 5000);
                if (response != null && parseGetInformationResponse(response, ep.host, ep.port)) {
                    primaryEndpoint = ep;
                    return true;
                }
            }

            // Fallback probe if SSDP was delayed
            if (cameraIp != null && !cameraIp.isEmpty()) {
                String serviceType = UPNP_SERVICE_CONTENT_DIR;
                String soapBody = buildSoapEnvelope(serviceType, "GetInfomation", null);
                String soapAction = "\"" + serviceType + "#GetInfomation\"";
                for (int port : CANDIDATE_UPNP_PORTS) {
                    if (!isPortOpen(cameraIp, port, 250)) continue;
                    for (String path : CANDIDATE_CONTROL_PATHS) {
                        String url = "http://" + cameraIp + ":" + port + path;
                        String response = sendSoapRequest(url, soapAction, soapBody, 1500);
                        if (response != null && parseGetInformationResponse(response, cameraIp, port)) {
                            UpnpEndpoint discovered = new UpnpEndpoint(cameraIp, port, path, serviceType);
                            primaryEndpoint = discovered;
                            activeEndpoints.add(0, discovered);
                            return true;
                        }
                    }
                }
            }

            return false;
        } finally {
            commandLock.unlock();
        }
    }

    private static boolean parseGetInformationResponse(String xml, String cameraIp, int cameraPort) {
        if (xml == null || xml.isEmpty()) return false;
        String unescaped = unescapeXml(xml);

        boolean matched = false;
        if (unescaped.contains("GetInfomationResponse") ||
            unescaped.contains("GETINFORMATIONRESULT") ||
            unescaped.contains("GETIPRESULT") ||
            unescaped.contains("Resolutions")) {
            matched = true;
        }

        // Extract <GETIPRESULT> (the live stream URL, e.g. http://192.168.107.1:7678/stream.avi)
        String streamUrl = extractXmlTag(unescaped, "GETIPRESULT");
        if (streamUrl != null && !streamUrl.isEmpty()) {
            String candidate = streamUrl.trim();
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                if (!candidate.startsWith("/")) {
                    candidate = "/" + candidate;
                }
                int streamPort = (cameraPort > 0) ? cameraPort : 7678;
                candidate = "http://" + cameraIp + ":" + streamPort + candidate;
            }
            if (isSafeCameraUrl(candidate)) {
                activeStreamUrl = candidate;
                Log.i(TAG, "Parsed StreamURL from <GETIPRESULT>: " + activeStreamUrl);
            }
            matched = true;
        }

        // Extract <AVAILSHOTS>
        String shots = extractXmlTag(unescaped, "AVAILSHOTS");
        if (shots != null && !shots.isEmpty()) {
            availableShots = shots.trim();
        }

        // Extract <Flash>
        String flash = extractXmlTag(unescaped, "Flash");
        if (flash != null && !flash.isEmpty()) {
            currentFlash = flash.trim();
        }

        // Extract <DefaultZoom> / <MaxZoom>
        String defZoom = extractXmlTag(unescaped, "DefaultZoom");
        if (defZoom != null) {
            try { currentZoom = Integer.parseInt(defZoom.trim()); } catch (Exception ignored) {}
        }
        String mZoom = extractXmlTag(unescaped, "MaxZoom");
        if (mZoom != null) {
            try { maxZoom = Integer.parseInt(mZoom.trim()); } catch (Exception ignored) {}
        }

        // Extract <ROTATION> (1=D, 2=L, 3=U, 4=R)
        String rot = extractXmlTag(unescaped, "ROTATION");
        if (rot != null) {
            rot = rot.trim();
            try {
                currentRotation = Integer.parseInt(rot);
            } catch (Exception ignored) {
                if ("D".equalsIgnoreCase(rot)) currentRotation = 1;
                else if ("L".equalsIgnoreCase(rot)) currentRotation = 2;
                else if ("U".equalsIgnoreCase(rot)) currentRotation = 3;
                else if ("R".equalsIgnoreCase(rot)) currentRotation = 4;
            }
        }

        // Extract first <Width> and <Height> from <Resolutions>
        String wStr = extractXmlTag(unescaped, "Width");
        String hStr = extractXmlTag(unescaped, "Height");
        if (wStr != null && hStr != null) {
            try {
                currentWidth = Integer.parseInt(wStr.trim());
                currentHeight = Integer.parseInt(hStr.trim());
            } catch (Exception ignored) {}
        }

        if (matched && (activeStreamUrl == null || activeStreamUrl.isEmpty())) {
            activeStreamUrl = "http://" + cameraIp + ":7678/stream.avi";
        }

        return matched;
    }

    /**
     * Starts streaming live preview frames from the camera.
     *
     * Protocol implementation matching Samsung RVF libffplay-jni:
     * 1. Sends HTTP GET to StreamURL with User-Agent: Lavf52.103.0, Range: bytes=0-, Connection: close
     * 2. Demuxes AVI container ("RIFF....AVI "), MP4, or raw Annex-B / MJPEG stream
     * 3. Decodes H.264 / MPEG-4 / H.263 video frames via Android MediaCodec into Bitmaps
     */
    public static void startLiveStream(final String cameraIp, final FrameCallback frameCallback) {
        stopLiveStream();
        lastFrameCallback = frameCallback;
        isStreaming.set(true);

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> streamUrls = new ArrayList<>();
                if (activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                    streamUrls.add(activeStreamUrl);
                }
                String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                            (activeCameraIp != null ? activeCameraIp : "192.168.107.1");
                String[] fallbacks = {
                    "http://" + ip + ":7678/stream.avi",
                    "http://" + ip + ":7679/livestream.avi",
                    "http://" + ip + ":7678/live",
                    "http://" + ip + ":7678/"
                };
                for (String fb : fallbacks) {
                    if (!streamUrls.contains(fb)) {
                        streamUrls.add(fb);
                    }
                }

                int consecutiveErrors = 0;

                while (isStreaming.get() && !Thread.currentThread().isInterrupted()) {
                    boolean gotAnyFrames = false;

                    if (activeStreamUrl == null || activeStreamUrl.isEmpty()) {
                        if (executeGetInformation(ip) && activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                            if (!streamUrls.contains(activeStreamUrl)) {
                                streamUrls.add(0, activeStreamUrl);
                            }
                        }
                    }

                    for (String urlStr : streamUrls) {
                        if (!isStreaming.get()) break;

                        Socket sock = null;
                        try {
                            URL url = new URL(urlStr);
                            String host = url.getHost();
                            int port = url.getPort() != -1 ? url.getPort() : 7678;
                            String path = url.getFile();
                            if (path == null || path.isEmpty()) path = "/";

                            sock = new Socket();
                            activeStreamSocket = sock;
                            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= 21) {
                                try {
                                    activeWifiNetwork.bindSocket(sock);
                                } catch (Exception ignored) {}
                            }
                            sock.setTcpNoDelay(true);
                            sock.setKeepAlive(true);
                            sock.setReceiveBufferSize(256 * 1024);
                            sock.setSoTimeout(8000);
                            sock.connect(new InetSocketAddress(host, port), 4000);

                            // Exact HTTP request sent by libffplay-jni.so (Lavf52.103.0)
                            String hostHeader = (port == 80) ? host : (host + ":" + port);
                            String req = "GET " + path + " HTTP/1.1\r\n" +
                                         "User-Agent: Lavf52.103.0\r\n" +
                                         "Accept: */*\r\n" +
                                         "Range: bytes=0-\r\n" +
                                         "Connection: close\r\n" +
                                         "Host: " + hostHeader + "\r\n\r\n";

                            OutputStream out = sock.getOutputStream();
                            out.write(req.getBytes(StandardCharsets.US_ASCII));
                            out.flush();

                            InputStream rawIn = sock.getInputStream();
                            HttpStreamHeader respHeader = readHttpStreamHeader(rawIn);
                            Log.i(TAG, "Stream HTTP status=" + respHeader.statusCode + " chunked=" + respHeader.isChunked + " url=" + urlStr);

                            if (respHeader.statusCode >= 200 && respHeader.statusCode < 300) {
                                InputStream bodyIn = respHeader.isChunked
                                        ? new ChunkedInputStream(rawIn)
                                        : rawIn;
                                BufferedInputStream bufferedIn = new BufferedInputStream(bodyIn, 65536);

                                int frames = decodeCameraStream(bufferedIn, urlStr, frameCallback);
                                if (frames > 0) {
                                    gotAnyFrames = true;
                                    consecutiveErrors = 0;
                                    break;
                                }
                            } else if (respHeader.statusCode == 0) {
                                // Raw stream directly without HTTP status line
                                InputStream combined = new SequencePrependInputStream(respHeader.rawHeaderBytes, rawIn);
                                BufferedInputStream bufferedIn = new BufferedInputStream(combined, 65536);
                                int frames = decodeCameraStream(bufferedIn, urlStr, frameCallback);
                                if (frames > 0) {
                                    gotAnyFrames = true;
                                    consecutiveErrors = 0;
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            if (isStreaming.get()) {
                                Log.w(TAG, "Stream notice on " + urlStr + ": " + e.getMessage());
                            }
                        } finally {
                            activeStreamSocket = null;
                            if (sock != null) {
                                try { sock.close(); } catch (Exception ignored) {}
                            }
                        }
                    }

                    if (!isStreaming.get()) break;

                    if (!gotAnyFrames) {
                        consecutiveErrors++;
                        if (consecutiveErrors % 2 == 0) {
                            if (executeGetInformation(ip) && activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                                if (!streamUrls.contains(activeStreamUrl)) {
                                    streamUrls.add(0, activeStreamUrl);
                                }
                            }
                        }
                        if (consecutiveErrors == 4) {
                            frameCallback.onStreamError("Waiting for camera live stream...");
                        }
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }, "NX-RVF-LiveStreamThread");
        streamThread.start();
    }

    public static void stopLiveStream() {
        isStreaming.set(false);
        Socket sock = activeStreamSocket;
        activeStreamSocket = null;
        if (sock != null) {
            try { sock.close(); } catch (Exception ignored) {}
        }
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
    }

    private static class HttpStreamHeader {
        int statusCode = 0;
        boolean isChunked = false;
        byte[] rawHeaderBytes = new byte[0];
    }

    private static HttpStreamHeader readHttpStreamHeader(InputStream in) throws IOException {
        HttpStreamHeader result = new HttpStreamHeader();
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(512);
        int b;
        int matchState = 0;

        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if (headerBuf.size() == 4) {
                byte[] first4 = headerBuf.toByteArray();
                String prefix = new String(first4, StandardCharsets.US_ASCII);
                if (!prefix.startsWith("HTTP")) {
                    result.statusCode = 0;
                    result.rawHeaderBytes = first4;
                    return result;
                }
            }

            if ((matchState == 0 || matchState == 2) && b == '\r') {
                matchState++;
            } else if ((matchState == 1 || matchState == 3) && b == '\n') {
                matchState++;
                if (matchState == 4) break;
            } else if (b == '\n' && matchState == 1) {
                matchState = 2;
            } else {
                matchState = (b == '\r') ? 1 : 0;
            }

            if (headerBuf.size() > 8192) break;
        }

        byte[] raw = headerBuf.toByteArray();
        result.rawHeaderBytes = raw;
        String headerStr = new String(raw, StandardCharsets.US_ASCII);
        String[] lines = headerStr.split("\r\n|\n");
        if (lines.length > 0 && lines[0].startsWith("HTTP/")) {
            String[] parts = lines[0].split(" ");
            if (parts.length >= 2) {
                try {
                    result.statusCode = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) {}
            }
        }
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                result.isChunked = true;
            }
        }
        return result;
    }

    private static class SequencePrependInputStream extends InputStream {
        private final byte[] prefix;
        private int pos = 0;
        private final InputStream tail;

        SequencePrependInputStream(byte[] prefix, InputStream tail) {
            this.prefix = prefix != null ? prefix : new byte[0];
            this.tail = tail;
        }

        @Override
        public int read() throws IOException {
            if (pos < prefix.length) {
                return prefix[pos++] & 0xFF;
            }
            return tail.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos < prefix.length) {
                int toCopy = Math.min(len, prefix.length - pos);
                System.arraycopy(prefix, pos, b, off, toCopy);
                pos += toCopy;
                return toCopy;
            }
            return tail.read(b, off, len);
        }
    }

    private static class ChunkedInputStream extends FilterInputStream {
        private int chunkRemaining = 0;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return (n == -1) ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (chunkRemaining <= 0) {
                if (!nextChunk()) {
                    eof = true;
                    return -1;
                }
            }
            int toRead = Math.min(len, chunkRemaining);
            int n = in.read(b, off, toRead);
            if (n == -1) {
                eof = true;
                return -1;
            }
            chunkRemaining -= n;
            if (chunkRemaining == 0) {
                int c1 = in.read();
                if (c1 == '\r') {
                    in.read();
                }
            }
            return n;
        }

        private boolean nextChunk() throws IOException {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\r') continue;
                if (ch == '\n') {
                    if (sb.length() > 0) break;
                    continue;
                }
                sb.append((char) ch);
                if (sb.length() > 32) break;
            }
            if (sb.length() == 0) return false;
            String line = sb.toString().trim();
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi).trim();
            try {
                chunkRemaining = Integer.parseInt(line, 16);
            } catch (NumberFormatException e) {
                return false;
            }
            return chunkRemaining > 0;
        }
    }

    private static int decodeCameraStream(BufferedInputStream in, String streamUrl, FrameCallback frameCallback) throws IOException {
        in.mark(32);
        byte[] probe = new byte[16];
        int probeLen = readUpTo(in, probe, 0, probe.length);
        in.reset();

        if (probeLen < 4) return 0;

        // RIFF / AVI container
        if (probe[0] == 'R' && probe[1] == 'I' && probe[2] == 'F' && probe[3] == 'F') {
            Log.i(TAG, "Detected RIFF/AVI live stream container from camera");
            return decodeAviStream(in, frameCallback);
        }

        // MP4 / MOV container
        if (probeLen >= 8) {
            String boxType = new String(probe, 4, 4, StandardCharsets.US_ASCII);
            if ("ftyp".equals(boxType) || "moov".equals(boxType) || "mdat".equals(boxType) ||
                "free".equals(boxType) || "wide".equals(boxType) ||
                (streamUrl != null && streamUrl.toLowerCase(Locale.US).contains(".mp4"))) {
                Log.i(TAG, "Detected MP4/MOV live stream container from camera");
                return decodeAnnexBOrMp4Stream(in, frameCallback);
            }
        }

        // Raw Annex-B start code
        if ((probe[0] == 0 && probe[1] == 0 && probe[2] == 0 && probe[3] == 1) ||
            (probe[0] == 0 && probe[1] == 0 && probe[2] == 1)) {
            Log.i(TAG, "Detected raw Annex-B elementary video stream from camera");
            return decodeAnnexBOrMp4Stream(in, frameCallback);
        }

        return decodeHybridOrMjpegStream(in, frameCallback);
    }

    private static int decodeAviStream(BufferedInputStream in, FrameCallback frameCallback) throws IOException {
        LiveVideoDecoder videoDecoder = new LiveVideoDecoder(frameCallback);
        int decodedFrames = 0;

        try {
            byte[] riffHeader = new byte[12];
            if (!readFully(in, riffHeader, 0, 12)) {
                return 0;
            }

            String currentStreamType = "";
            String videoFourCC = "H264";
            int videoWidth = 640;
            int videoHeight = 480;
            byte[] headerBuf = new byte[8];

            while (isStreaming.get() && !Thread.currentThread().isInterrupted()) {
                if (!readNextAviChunkHeader(in, headerBuf)) {
                    break;
                }

                String fourCC = new String(headerBuf, 0, 4, StandardCharsets.US_ASCII);
                int chunkSize = readInt32LE(headerBuf, 4);

                if ("LIST".equals(fourCC) || "RIFF".equals(fourCC)) {
                    byte[] listTypeBytes = new byte[4];
                    if (!readFully(in, listTypeBytes, 0, 4)) break;
                    continue;
                }

                if (chunkSize < 0 || chunkSize > 4 * 1024 * 1024) {
                    if (!resyncAviStream(in, headerBuf)) break;
                    continue;
                }

                if ("strh".equals(fourCC)) {
                    byte[] strh = new byte[chunkSize];
                    if (!readFully(in, strh, 0, chunkSize)) break;
                    consumeAviPadIfPresent(in, chunkSize);

                    if (chunkSize >= 8) {
                        currentStreamType = new String(strh, 0, 4, StandardCharsets.US_ASCII);
                        String handler = new String(strh, 4, 4, StandardCharsets.US_ASCII).trim();
                        if ("vids".equalsIgnoreCase(currentStreamType) && !handler.isEmpty()) {
                            videoFourCC = handler;
                            Log.i(TAG, "AVI strh video fccHandler=" + videoFourCC);
                        }
                    }
                } else if ("strf".equals(fourCC)) {
                    byte[] strf = new byte[chunkSize];
                    if (!readFully(in, strf, 0, chunkSize)) break;
                    consumeAviPadIfPresent(in, chunkSize);

                    if ("vids".equalsIgnoreCase(currentStreamType) && chunkSize >= 40) {
                        int w = readInt32LE(strf, 4);
                        int h = Math.abs(readInt32LE(strf, 8));
                        String compression = new String(strf, 16, 4, StandardCharsets.US_ASCII).trim();
                        if (w > 0 && w <= 4096) videoWidth = w;
                        if (h > 0 && h <= 4096) videoHeight = h;
                        if (!compression.isEmpty() && compression.charAt(0) != 0) {
                            videoFourCC = compression;
                        }
                        byte[] extraData = null;
                        if (chunkSize > 40) {
                            extraData = Arrays.copyOfRange(strf, 40, chunkSize);
                        }
                        Log.i(TAG, "AVI strf video " + videoWidth + "x" + videoHeight + " compression=" + videoFourCC);
                        videoDecoder.configureFromHeader(videoFourCC, videoWidth, videoHeight, extraData);
                    }
                } else if (isVideoChunkFourCC(fourCC)) {
                    if (chunkSize == 0) continue;
                    byte[] packet = new byte[chunkSize];
                    if (!readFully(in, packet, 0, chunkSize)) break;
                    consumeAviPadIfPresent(in, chunkSize);

                    if (videoDecoder.decodePacket(packet, 0, chunkSize)) {
                        decodedFrames++;
                    }
                } else {
                    if (chunkSize > 0) {
                        if (!skipFully(in, chunkSize)) break;
                        consumeAviPadIfPresent(in, chunkSize);
                    }
                }
            }
        } finally {
            videoDecoder.release();
        }

        return decodedFrames;
    }

    private static boolean isVideoChunkFourCC(String fourCC) {
        if (fourCC == null || fourCC.length() != 4) return false;
        char c0 = fourCC.charAt(0);
        char c1 = fourCC.charAt(1);
        char c2 = Character.toLowerCase(fourCC.charAt(2));
        char c3 = Character.toLowerCase(fourCC.charAt(3));
        return (c0 >= '0' && c0 <= '9') && (c1 >= '0' && c1 <= '9') &&
               ((c2 == 'd' && (c3 == 'c' || c3 == 'b')) || (c2 == 'v' && c3 == 'c'));
    }

    private static boolean isKnownAviFourCC(byte b0, byte b1, byte b2, byte b3) {
        if (b0 >= '0' && b0 <= '9' && b1 >= '0' && b1 <= '9') {
            if ((b2 == 'd' || b2 == 'D') && (b3 == 'c' || b3 == 'C' || b3 == 'b' || b3 == 'B')) return true;
            if ((b2 == 'w' || b2 == 'W') && (b3 == 'b' || b3 == 'B')) return true;
        }
        if (b0 == 'L' && b1 == 'I' && b2 == 'S' && b3 == 'T') return true;
        if (b0 == 'J' && b1 == 'U' && b2 == 'N' && b3 == 'K') return true;
        if (b0 == 'a' && b1 == 'v' && b2 == 'i' && b3 == 'h') return true;
        if (b0 == 's' && b1 == 't' && b2 == 'r' && (b3 == 'h' || b3 == 'f' || b3 == 'n' || b3 == 'd')) return true;
        if (b0 == 'i' && b1 == 'd' && b2 == 'x' && b3 == '1') return true;
        if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F') return true;
        return false;
    }

    private static boolean isPrintableFourCC(byte b0, byte b1, byte b2, byte b3) {
        return (b0 >= 0x20 && b0 <= 0x7E) &&
               (b1 >= 0x20 && b1 <= 0x7E) &&
               (b2 >= 0x20 && b2 <= 0x7E) &&
               (b3 >= 0x20 && b3 <= 0x7E);
    }

    private static void consumeAviPadIfPresent(BufferedInputStream in, int chunkSize) throws IOException {
        if ((chunkSize & 1) == 0) return;
        in.mark(8);
        byte[] peek = new byte[5];
        int n = readUpTo(in, peek, 0, 5);
        in.reset();
        if (n >= 5) {
            if (isKnownAviFourCC(peek[0], peek[1], peek[2], peek[3]) && !isKnownAviFourCC(peek[1], peek[2], peek[3], peek[4])) {
                return;
            }
        }
        in.read();
    }

    private static boolean readNextAviChunkHeader(BufferedInputStream in, byte[] headerBuf) throws IOException {
        if (!readFully(in, headerBuf, 0, 8)) return false;
        if (isKnownAviFourCC(headerBuf[0], headerBuf[1], headerBuf[2], headerBuf[3]) ||
            isPrintableFourCC(headerBuf[0], headerBuf[1], headerBuf[2], headerBuf[3])) {
            return true;
        }
        return resyncAviStream(in, headerBuf);
    }

    private static boolean resyncAviStream(BufferedInputStream in, byte[] headerBuf) throws IOException {
        int scanned = 0;
        while (scanned < 256 * 1024 && isStreaming.get()) {
            if (isKnownAviFourCC(headerBuf[0], headerBuf[1], headerBuf[2], headerBuf[3])) {
                int size = readInt32LE(headerBuf, 4);
                if (size >= 0 && size <= 2 * 1024 * 1024) {
                    return true;
                }
            }
            int next = in.read();
            if (next == -1) return false;
            System.arraycopy(headerBuf, 1, headerBuf, 0, 7);
            headerBuf[7] = (byte) next;
            scanned++;
        }
        return false;
    }

    private static int decodeAnnexBOrMp4Stream(BufferedInputStream in, FrameCallback frameCallback) throws IOException {
        LiveVideoDecoder decoder = new LiveVideoDecoder(frameCallback);
        decoder.configureFromHeader("H264", 640, 480, null);
        int frames = 0;

        try {
            byte[] readBuf = new byte[32768];
            ByteArrayOutputStream acc = new ByteArrayOutputStream(131072);
            int n;

            while (isStreaming.get() && (n = in.read(readBuf)) != -1) {
                acc.write(readBuf, 0, n);
                byte[] data = acc.toByteArray();

                int auStart = findStartCode(data, 0, data.length);
                if (auStart < 0) {
                    if (data.length > 256 * 1024) acc.reset();
                    continue;
                }

                int pos = auStart + 4;
                int lastConsumed = 0;

                while (pos < data.length - 5) {
                    int nextSc = findStartCode(data, pos, data.length);
                    if (nextSc < 0) break;

                    int nalOffset = (data[nextSc + 2] == 1) ? (nextSc + 3) : (nextSc + 4);
                    if (nalOffset < data.length) {
                        int nalType = data[nalOffset] & 0x1F;
                        boolean prevHasSlice = containsVclSlice(data, auStart, nextSc);
                        if (prevHasSlice && (nalType == 7 || nalType == 9 || nalType == 5 || nalType == 1)) {
                            int auLen = nextSc - auStart;
                            if (decoder.decodePacket(data, auStart, auLen)) {
                                frames++;
                            }
                            auStart = nextSc;
                            lastConsumed = nextSc;
                        }
                    }
                    pos = nextSc + 4;
                }

                if (lastConsumed > 0) {
                    acc.reset();
                    acc.write(data, lastConsumed, data.length - lastConsumed);
                } else if (data.length > 512 * 1024) {
                    acc.reset();
                }
            }
        } finally {
            decoder.release();
        }
        return frames;
    }

    private static boolean containsVclSlice(byte[] data, int start, int end) {
        int p = start;
        while (p < end - 4) {
            int sc = findStartCode(data, p, end);
            if (sc < 0) break;
            int nalOff = (data[sc + 2] == 1) ? (sc + 3) : (sc + 4);
            if (nalOff < end) {
                int nalType = data[nalOff] & 0x1F;
                if (nalType == 1 || nalType == 5) return true;
            }
            p = nalOff + 1;
        }
        return false;
    }

    private static int findStartCode(byte[] data, int from, int to) {
        for (int i = from; i <= to - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 0 && data[i + 3] == 1) return i;
                if (data[i + 2] == 1) return i;
            }
        }
        return -1;
    }

    private static int decodeHybridOrMjpegStream(BufferedInputStream in, FrameCallback frameCallback) throws IOException {
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(65536);
        byte[] readBuf = new byte[16384];
        boolean inJpeg = false;
        int prevByte = -1;
        int framesDecoded = 0;

        int bytesRead;
        while (isStreaming.get() && (bytesRead = in.read(readBuf)) != -1) {
            for (int i = 0; i <= bytesRead - 8; i++) {
                if (readBuf[i] == 'R' && readBuf[i + 1] == 'I' && readBuf[i + 2] == 'F' && readBuf[i + 3] == 'F') {
                    byte[] remaining = Arrays.copyOfRange(readBuf, i, bytesRead);
                    BufferedInputStream subIn = new BufferedInputStream(new SequencePrependInputStream(remaining, in), 65536);
                    return decodeAviStream(subIn, frameCallback);
                }
            }

            for (int i = 0; i < bytesRead; i++) {
                int b = readBuf[i] & 0xFF;
                if (!inJpeg) {
                    if (prevByte == 0xFF && b == 0xD8) {
                        inJpeg = true;
                        frameBuffer.reset();
                        frameBuffer.write(0xFF);
                        frameBuffer.write(0xD8);
                    }
                } else {
                    frameBuffer.write(b);
                    if (prevByte == 0xFF && b == 0xD9) {
                        inJpeg = false;
                        byte[] jpegBytes = frameBuffer.toByteArray();
                        Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                        if (bmp != null) {
                            framesDecoded++;
                            frameCallback.onFrame(applyCameraRotation(bmp));
                        }
                        frameBuffer.reset();
                    } else if (frameBuffer.size() > 2 * 1024 * 1024) {
                        inJpeg = false;
                        frameBuffer.reset();
                    }
                }
                prevByte = b;
            }
        }
        return framesDecoded;
    }

    /**
     * Applies camera rotation to live preview frames according to Samsung RVF specification:
     * 1 = D (0° normal)
     * 2 = L (270° counter-clockwise)
     * 3 = U (180° upside down)
     * 4 = R (90° clockwise)
     * Exif fallback values 6 (90°) and 8 (270°) are also supported.
     */
    public static Bitmap applyCameraRotation(Bitmap bmp) {
        if (bmp == null) return null;
        int deg = 0;
        if (currentRotation == 2 || currentRotation == 8 || currentRotation == 'L') {
            deg = 270;
        } else if (currentRotation == 3 || currentRotation == 'U') {
            deg = 180;
        } else if (currentRotation == 4 || currentRotation == 6 || currentRotation == 'R') {
            deg = 90;
        }

        if (deg == 0) return bmp;
        try {
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        } catch (Exception e) {
            return bmp;
        }
    }

    private static class LiveVideoDecoder {
        private final FrameCallback frameCallback;
        private MediaCodec codec;
        private String mimeType = "video/avc";
        private int width = 640;
        private int height = 480;
        private byte[] extraData;
        private byte[] csd0;
        private byte[] csd1;
        private boolean configured = false;
        private long ptsUs = 0;

        private int[] argbPixels;
        private final Bitmap[] bitmapPool = new Bitmap[2];
        private int poolIndex = 0;

        LiveVideoDecoder(FrameCallback frameCallback) {
            this.frameCallback = frameCallback;
        }

        void configureFromHeader(String fourCC, int w, int h, byte[] extra) {
            if (w > 0) this.width = w;
            if (h > 0) this.height = h;
            this.extraData = extra;

            String upper = fourCC != null ? fourCC.toUpperCase(Locale.US) : "H264";
            if (upper.contains("264") || upper.contains("AVC")) {
                this.mimeType = "video/avc";
            } else if (upper.contains("MP4") || upper.contains("DIV") || upper.contains("XVID") || upper.contains("FMP4")) {
                this.mimeType = "video/mp4v-es";
            } else if (upper.contains("263")) {
                this.mimeType = "video/3gpp";
            }

            if (extra != null && extra.length > 0) {
                extractCsdFromBytes(extra, 0, extra.length);
            }
        }

        boolean decodePacket(byte[] data, int offset, int length) {
            if (data == null || length <= 4) return false;

            if ((data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xFF) == 0xD8) {
                Bitmap bmp = BitmapFactory.decodeByteArray(data, offset, length);
                if (bmp != null) {
                    frameCallback.onFrame(applyCameraRotation(bmp));
                    return true;
                }
            }

            byte[] normalized = normalizePacketStartCodes(data, offset, length);
            extractCsdFromBytes(normalized, 0, normalized.length);

            if (!configured) {
                if ("video/avc".equals(mimeType) && csd0 == null) {
                    return false;
                }
                if (!initMediaCodec()) {
                    return false;
                }
            }

            try {
                int inIdx = codec.dequeueInputBuffer(15000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf;
                    if (Build.VERSION.SDK_INT >= 21) {
                        inBuf = codec.getInputBuffer(inIdx);
                    } else {
                        inBuf = codec.getInputBuffers()[inIdx];
                    }
                    if (inBuf != null) {
                        inBuf.clear();
                        int toPut = Math.min(normalized.length, inBuf.remaining());
                        inBuf.put(normalized, 0, toPut);
                        codec.queueInputBuffer(inIdx, 0, toPut, ptsUs, 0);
                        ptsUs += 33333; // ~30 fps
                    }
                }

                return drainOutputFrames();
            } catch (Exception e) {
                Log.w(TAG, "MediaCodec decode step notice: " + e.getMessage());
                release();
                return false;
            }
        }

        private byte[] normalizePacketStartCodes(byte[] data, int offset, int length) {
            if (length >= 4 && data[offset] == 0 && data[offset + 1] == 0 &&
                (data[offset + 2] == 1 || (data[offset + 2] == 0 && data[offset + 3] == 1))) {
                if (length >= 4 && data[offset + 2] == 1) {
                    byte b3 = data[offset + 3];
                    if ((b3 & 0xFF) == 0xB0 || (b3 & 0xFF) == 0xB6) {
                        mimeType = "video/mp4v-es";
                    }
                }
                if (offset == 0 && length == data.length) return data;
                return Arrays.copyOfRange(data, offset, offset + length);
            }

            if ("video/avc".equals(mimeType) && length > 4) {
                int nalLen = ((data[offset] & 0xFF) << 24) |
                             ((data[offset + 1] & 0xFF) << 16) |
                             ((data[offset + 2] & 0xFF) << 8) |
                             (data[offset + 3] & 0xFF);
                if (nalLen > 0 && nalLen <= length - 4) {
                    byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
                    int p = 0;
                    while (p <= copy.length - 4) {
                        int nLen = ((copy[p] & 0xFF) << 24) |
                                   ((copy[p + 1] & 0xFF) << 16) |
                                   ((copy[p + 2] & 0xFF) << 8) |
                                   (copy[p + 3] & 0xFF);
                        if (nLen <= 0 || p + 4 + nLen > copy.length) break;
                        copy[p] = 0;
                        copy[p + 1] = 0;
                        copy[p + 2] = 0;
                        copy[p + 3] = 1;
                        p += 4 + nLen;
                    }
                    return copy;
                }
            }

            if (offset == 0 && length == data.length) return data;
            return Arrays.copyOfRange(data, offset, offset + length);
        }

        private void extractCsdFromBytes(byte[] buf, int off, int len) {
            if ("video/avc".equals(mimeType)) {
                int end = off + len;
                int p = off;
                while (p < end - 4) {
                    int sc = findStartCode(buf, p, end);
                    if (sc < 0) break;
                    int nalStart = (buf[sc + 2] == 1) ? (sc + 3) : (sc + 4);
                    if (nalStart >= end) break;
                    int nextSc = findStartCode(buf, nalStart, end);
                    int nalEnd = (nextSc >= 0) ? nextSc : end;
                    int nalType = buf[nalStart] & 0x1F;
                    if (nalType == 7) {
                        csd0 = buildAnnexBNal(buf, nalStart, nalEnd - nalStart);
                    } else if (nalType == 8) {
                        csd1 = buildAnnexBNal(buf, nalStart, nalEnd - nalStart);
                    }
                    p = nalEnd;
                }
            } else if ("video/mp4v-es".equals(mimeType) && csd0 == null) {
                int end = off + len;
                for (int i = off; i < end - 4; i++) {
                    if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1 && (buf[i + 3] & 0xFF) == 0xB0) {
                        int vopPos = end;
                        for (int j = i + 4; j < end - 4; j++) {
                            if (buf[j] == 0 && buf[j + 1] == 0 && buf[j + 2] == 1 && (buf[j + 3] & 0xFF) == 0xB6) {
                                vopPos = j;
                                break;
                            }
                        }
                        csd0 = Arrays.copyOfRange(buf, i, vopPos);
                        break;
                    }
                }
            }
        }

        private byte[] buildAnnexBNal(byte[] buf, int nalStart, int nalLen) {
            byte[] out = new byte[4 + nalLen];
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            out[3] = 1;
            System.arraycopy(buf, nalStart, out, 4, nalLen);
            return out;
        }

        private boolean initMediaCodec() {
            try {
                release();
                MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
                if (csd0 != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
                }
                if (csd1 != null) {
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
                }
                try {
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                } catch (Exception ignored) {}

                codec = MediaCodec.createDecoderByType(mimeType);
                codec.configure(format, null, null, 0);
                codec.start();
                configured = true;
                Log.i(TAG, "MediaCodec initialized for " + mimeType + " (" + width + "x" + height + ")");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "MediaCodec init failed for " + mimeType + ": " + e.getMessage());
                release();
                return false;
            }
        }

        private boolean drainOutputFrames() {
            boolean renderedAny = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeoutUs = 8000;

            while (true) {
                int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
                timeoutUs = 0;
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    }
                    timeoutUs = 8000;
                    Log.i(TAG, "MediaCodec output format changed: " + newFormat);
                } else if (outIdx >= 0) {
                    Bitmap frameBmp = null;
                    try {
                        if (info.size > 0 && Build.VERSION.SDK_INT >= 21) {
                            Image image = codec.getOutputImage(outIdx);
                            if (image != null) {
                                try {
                                    frameBmp = yuv420ImageToBitmap(image);
                                } finally {
                                    image.close();
                                }
                            } else {
                                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                                if (outBuf != null) {
                                    frameBmp = rawYuvBufferToBitmap(outBuf, info, codec.getOutputFormat());
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "YUV frame conversion notice: " + e.getMessage());
                    } finally {
                        codec.releaseOutputBuffer(outIdx, false);
                    }

                    if (frameBmp != null) {
                        renderedAny = true;
                        frameCallback.onFrame(applyCameraRotation(frameBmp));
                    }
                } else {
                    break;
                }
            }
            return renderedAny;
        }

        private Bitmap yuv420ImageToBitmap(Image image) {
            android.graphics.Rect crop = image.getCropRect();
            int cropLeft = (crop != null && crop.width() > 0) ? crop.left : 0;
            int cropTop = (crop != null && crop.height() > 0) ? crop.top : 0;
            int imgW = (crop != null && crop.width() > 0) ? crop.width() : image.getWidth();
            int imgH = (crop != null && crop.height() > 0) ? crop.height() : image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return null;

            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();

            int yRowStride = planes[0].getRowStride();
            int yPixelStride = planes[0].getPixelStride();
            int uRowStride = planes[1].getRowStride();
            int uPixelStride = planes[1].getPixelStride();
            int vRowStride = planes[2].getRowStride();
            int vPixelStride = planes[2].getPixelStride();

            int totalPixels = imgW * imgH;
            if (argbPixels == null || argbPixels.length != totalPixels) {
                argbPixels = new int[totalPixels];
            }

            int yLimit = yBuf.limit();
            int uLimit = uBuf.limit();
            int vLimit = vBuf.limit();

            int outOffset = 0;
            for (int row = 0; row < imgH; row++) {
                int srcRow = cropTop + row;
                int yRowStart = srcRow * yRowStride;
                int uvRow = srcRow >> 1;
                int uRowStart = uvRow * uRowStride;
                int vRowStart = uvRow * vRowStride;

                for (int col = 0; col < imgW; col++) {
                    int srcCol = cropLeft + col;
                    int yIdx = yRowStart + srcCol * yPixelStride;
                    int uvCol = srcCol >> 1;
                    int uIdx = uRowStart + uvCol * uPixelStride;
                    int vIdx = vRowStart + uvCol * vPixelStride;

                    int y = (yIdx < yLimit ? (yBuf.get(yIdx) & 0xFF) : 16) - 16;
                    int u = (uIdx < uLimit ? (uBuf.get(uIdx) & 0xFF) : 128) - 128;
                    int v = (vIdx < vLimit ? (vBuf.get(vIdx) & 0xFF) : 128) - 128;
                    if (y < 0) y = 0;

                    int y1192 = 1192 * y;
                    int r = (y1192 + 1634 * v) >> 10;
                    int g = (y1192 - 833 * v - 400 * u) >> 10;
                    int b = (y1192 + 2066 * u) >> 10;

                    if (r < 0) r = 0; else if (r > 255) r = 255;
                    if (g < 0) g = 0; else if (g > 255) g = 255;
                    if (b < 0) b = 0; else if (b > 255) b = 255;

                    argbPixels[outOffset++] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            poolIndex ^= 1;
            Bitmap bmp = bitmapPool[poolIndex];
            if (bmp == null || bmp.getWidth() != imgW || bmp.getHeight() != imgH || !bmp.isMutable()) {
                bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
                bitmapPool[poolIndex] = bmp;
            }
            bmp.setPixels(argbPixels, 0, imgW, 0, 0, imgW, imgH);
            return bmp;
        }

        private Bitmap rawYuvBufferToBitmap(ByteBuffer buf, MediaCodec.BufferInfo info, MediaFormat fmt) {
            int imgW = fmt.containsKey(MediaFormat.KEY_WIDTH) ? fmt.getInteger(MediaFormat.KEY_WIDTH) : width;
            int imgH = fmt.containsKey(MediaFormat.KEY_HEIGHT) ? fmt.getInteger(MediaFormat.KEY_HEIGHT) : height;
            int stride = fmt.containsKey(MediaFormat.KEY_STRIDE) ? fmt.getInteger(MediaFormat.KEY_STRIDE) : imgW;
            int sliceHeight = fmt.containsKey(MediaFormat.KEY_SLICE_HEIGHT) ? fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT) : imgH;
            if (stride < imgW) stride = imgW;
            if (sliceHeight < imgH) sliceHeight = imgH;

            int totalPixels = imgW * imgH;
            if (argbPixels == null || argbPixels.length != totalPixels) {
                argbPixels = new int[totalPixels];
            }

            buf.position(info.offset);
            int limit = info.offset + info.size;
            int uvBase = info.offset + stride * sliceHeight;

            int outOffset = 0;
            for (int row = 0; row < imgH; row++) {
                int yRow = info.offset + row * stride;
                int uvRow = uvBase + (row >> 1) * stride;
                for (int col = 0; col < imgW; col++) {
                    int yIdx = yRow + col;
                    int uvIdx = uvRow + (col & ~1);
                    int y = (yIdx < limit ? (buf.get(yIdx) & 0xFF) : 16) - 16;
                    int u = (uvIdx < limit ? (buf.get(uvIdx) & 0xFF) : 128) - 128;
                    int v = (uvIdx + 1 < limit ? (buf.get(uvIdx + 1) & 0xFF) : 128) - 128;
                    if (y < 0) y = 0;

                    int y1192 = 1192 * y;
                    int r = (y1192 + 1634 * v) >> 10;
                    int g = (y1192 - 833 * v - 400 * u) >> 10;
                    int b = (y1192 + 2066 * u) >> 10;

                    if (r < 0) r = 0; else if (r > 255) r = 255;
                    if (g < 0) g = 0; else if (g > 255) g = 255;
                    if (b < 0) b = 0; else if (b > 255) b = 255;

                    argbPixels[outOffset++] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            poolIndex ^= 1;
            Bitmap bmp = bitmapPool[poolIndex];
            if (bmp == null || bmp.getWidth() != imgW || bmp.getHeight() != imgH || !bmp.isMutable()) {
                bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
                bitmapPool[poolIndex] = bmp;
            }
            bmp.setPixels(argbPixels, 0, imgW, 0, 0, imgW, imgH);
            return bmp;
        }

        void release() {
            configured = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                codec = null;
            }
        }
    }

    private static int readUpTo(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    private static boolean readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) return false;
            total += n;
        }
        return true;
    }

    private static boolean skipFully(InputStream in, int len) throws IOException {
        int remaining = len;
        byte[] discard = new byte[Math.min(len, 8192)];
        while (remaining > 0) {
            int n = in.read(discard, 0, Math.min(remaining, discard.length));
            if (n < 0) return false;
            remaining -= n;
        }
        return true;
    }

    private static int readInt32LE(byte[] b, int off) {
        return (b[off] & 0xFF) |
               ((b[off + 1] & 0xFF) << 8) |
               ((b[off + 2] & 0xFF) << 16) |
               ((b[off + 3] & 0xFF) << 24);
    }

    private static UpnpEndpoint getPrimaryEndpoint(String cameraIp) {
        if (primaryEndpoint != null) {
            return primaryEndpoint;
        }
        if (!activeEndpoints.isEmpty()) {
            return activeEndpoints.get(0);
        }
        if (cameraIp != null && !cameraIp.isEmpty()) {
            return new UpnpEndpoint(cameraIp, 7676, "/smp_4_", UPNP_SERVICE_CONTENT_DIR);
        }
        return null;
    }

    /**
     * Executes the MULTIAF autofocus command with strict command serialization
     * and extended 30-second timeout to allow physical motor movement.
     */
    public static void triggerAutoFocus(final String cameraIp, final AutoFocusCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                commandLock.lock();
                try {
                    final String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                                      (activeCameraIp != null ? activeCameraIp : "192.168.107.1");

                    UpnpEndpoint ep = getPrimaryEndpoint(ip);
                    String serviceType = (ep != null && ep.serviceType != null) ? ep.serviceType : UPNP_SERVICE_CONTENT_DIR;
                    String soapBody = buildSoapEnvelope(serviceType, "MULTIAF", null);
                    String soapAction = "\"" + serviceType + "#MULTIAF\"";

                    String afResponse = null;
                    if (ep != null) {
                        String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                        afResponse = sendSoapRequest(url, soapAction, soapBody, 30000);
                    }

                    if (afResponse == null) {
                        for (UpnpEndpoint e : activeEndpoints) {
                            String url = "http://" + e.host + ":" + e.port + e.controlPath;
                            afResponse = sendSoapRequest(url, soapAction, soapBody, 30000);
                            if (afResponse != null) {
                                primaryEndpoint = e;
                                break;
                            }
                        }
                    }

                    if (afResponse != null) {
                        String unescaped = unescapeXml(afResponse);
                        String afStatus = extractXmlTag(unescaped, "AFSTATUS");
                        Log.i(TAG, "MULTIAF completed. AFSTATUS=" + afStatus);
                        if (afStatus != null && !afStatus.contains("AFFAIL")) {
                            if (callback != null) callback.onAutoFocusSuccess(afStatus);
                        } else {
                            if (callback != null) callback.onAutoFocusFailed("AFSTATUS=" + afStatus);
                        }
                    } else {
                        Log.w(TAG, "Camera did not respond to MULTIAF");
                        if (callback != null) {
                            callback.onAutoFocusFailed("Camera did not respond to autofocus command");
                        }
                    }
                } finally {
                    commandLock.unlock();
                }
            }
        }, "NX-RVF-AutoFocus").start();
    }

    /**
     * Releases camera autofocus lock / self-timer state by sending UPnP SOAP Action "ReleaseSelfTimer":
     * <RELEASETIMER>0</RELEASETIMER>
     *
     * Protocol Rule: Must be called whenever focus was engaged without taking a photo,
     * or when canceling / exiting the viewfinder activity.
     */
    public static void releaseFocus(final String cameraIp) {
        releaseFocus(cameraIp, null);
    }

    public static void releaseFocus(final String cameraIp, final Runnable onDone) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                commandLock.lock();
                try {
                    final String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                                      (activeCameraIp != null ? activeCameraIp : "192.168.107.1");
                    UpnpEndpoint ep = getPrimaryEndpoint(ip);
                    if (ep != null) {
                        releaseFocusInternal(ep, ep.serviceType);
                    }
                } finally {
                    commandLock.unlock();
                    if (onDone != null) onDone.run();
                }
            }
        }, "NX-RVF-ReleaseFocus").start();
    }

    private static void releaseFocusInternal(UpnpEndpoint ep, String serviceType) {
        try {
            if (ep == null) return;
            String st = (serviceType != null) ? serviceType : UPNP_SERVICE_CONTENT_DIR;
            String soapBody = buildSoapEnvelope(st, "ReleaseSelfTimer", "<RELEASETIMER>0</RELEASETIMER>");
            String soapAction = "\"" + st + "#ReleaseSelfTimer\"";
            String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
            sendSoapRequest(url, soapAction, soapBody, 5000);
            Log.i(TAG, "ReleaseSelfTimer(0) dispatched successfully");
        } catch (Exception e) {
            Log.w(TAG, "releaseFocusInternal notice: " + e.getMessage());
        }
    }

    public static void triggerShutter(final String cameraIp, final ShutterCallback callback) {
        triggerShutter(cameraIp, true, callback);
    }

    /**
     * Full, reliable capture sequence from Samsung Camera Remote Protocol Guide:
     * 1. Autofocus via MULTIAF (with 30s timeout). On failure: releases focus and aborts cleanly.
     * 2. Pauses live video stream socket and waits 350ms for camera DSP to unbind video encoder.
     * 3. Dispatches parameterless Shot action with 35s timeout.
     * 4. In finally block, automatically resumes live video streaming.
     * 5. Downloads captured thumbnail and FULL-resolution photo to DCIM/NX_MobileLink,
     *    and registers it with Android MediaScanner.
     */
    public static void triggerShutter(final String cameraIp, final boolean autoFocusFirst, final ShutterCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                commandLock.lock();
                String savedPath = null;
                try {
                    final boolean wasStreaming = isStreaming.get();
                    final FrameCallback savedFrameCallback = lastFrameCallback;
                    final String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                                      (activeCameraIp != null ? activeCameraIp : "192.168.107.1");

                    UpnpEndpoint ep = getPrimaryEndpoint(ip);
                    String serviceType = (ep != null && ep.serviceType != null) ? ep.serviceType : UPNP_SERVICE_CONTENT_DIR;

                    // 1. Autofocus first if requested
                    if (autoFocusFirst) {
                        Log.i(TAG, "triggerShutter: Executing pre-capture MULTIAF...");
                        String afBody = buildSoapEnvelope(serviceType, "MULTIAF", null);
                        String afAction = "\"" + serviceType + "#MULTIAF\"";
                        String afResp = null;
                        if (ep != null) {
                            String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                            afResp = sendSoapRequest(url, afAction, afBody, 30000);
                        }
                        if (afResp != null) {
                            String unescaped = unescapeXml(afResp);
                            String afStatus = extractXmlTag(unescaped, "AFSTATUS");
                            Log.i(TAG, "Pre-capture MULTIAF completed. AFSTATUS=" + afStatus);
                            if (afStatus != null && afStatus.contains("AFFAIL")) {
                                Log.w(TAG, "Pre-capture autofocus failed; releasing focus");
                                releaseFocusInternal(ep, serviceType);
                                if (callback != null) callback.onError("Autofocus failed (AFFAIL)");
                                return;
                            }
                        }
                    }

                    // 2. Pause live stream before Shot (Rule 3 in protocol guide)
                    // The camera hardware requires full sensor & DSP resources for still capture.
                    if (wasStreaming) {
                        stopLiveStream();
                        try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                    }

                    try {
                        // 3. Send Shot action
                        String shotBody = buildSoapEnvelope(serviceType, "Shot", null);
                        String shotAction = "\"" + serviceType + "#Shot\"";
                        String shotResponse = null;

                        if (ep != null) {
                            String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                            shotResponse = sendSoapRequest(url, shotAction, shotBody, 35000);
                        }
                        if (shotResponse == null) {
                            for (UpnpEndpoint e : activeEndpoints) {
                                String url = "http://" + e.host + ":" + e.port + e.controlPath;
                                shotResponse = sendSoapRequest(url, shotAction, shotBody, 35000);
                                if (shotResponse != null) {
                                    primaryEndpoint = e;
                                    break;
                                }
                            }
                        }

                        if (shotResponse != null) {
                            String unescaped = unescapeXml(shotResponse);
                            String fileUrl = extractXmlTag(unescaped, "AFSHOTRESULT");
                            if (fileUrl == null || fileUrl.trim().isEmpty()) {
                                fileUrl = extractXmlTag(unescaped, "AFATGPSRESULT");
                            }
                            if (fileUrl != null && !fileUrl.trim().isEmpty()) {
                                lastCapturedFileUrl = fileUrl.trim();
                                Log.i(TAG, "Shot returned FileURL=" + lastCapturedFileUrl);
                                savedPath = downloadCapturedPhoto(lastCapturedFileUrl, appContext);
                            }
                            String avail = extractXmlTag(unescaped, "AVAILSHOTS");
                            if (avail == null || avail.trim().isEmpty()) {
                                avail = extractXmlTag(unescaped, "AVAILSHOTSRESULT");
                            }
                            if (avail != null && !avail.trim().isEmpty()) {
                                availableShots = avail.trim();
                            }
                            if (callback != null) callback.onSuccess(savedPath);
                        } else {
                            if (callback != null) callback.onError("Camera did not respond to Shot command");
                        }
                    } finally {
                        // 4. Restart live stream automatically after Shot completes
                        if (wasStreaming && savedFrameCallback != null) {
                            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                            startLiveStream(ip, savedFrameCallback);
                        }
                    }
                } finally {
                    commandLock.unlock();
                }
            }
        }, "NX-RVF-TriggerShutter").start();
    }

    /**
     * Downloads both thumbnail and the FULL-resolution photo from the camera,
     * saving it to DCIM/NX_MobileLink and notifying MediaScanner.
     */
    private static String downloadCapturedPhoto(String fileUrl, Context context) {
        if (fileUrl == null || fileUrl.isEmpty() || !isSafeCameraUrl(fileUrl)) {
            return null;
        }
        try {
            // First fetch thumbnail if available
            String thumbUrl = fileUrl.endsWith("_SM") ? fileUrl : (fileUrl + "_SM");
            downloadSingleFile(thumbUrl, null);

            // Fetch full resolution image
            String fullUrl = fileUrl.endsWith("_SM") ? fileUrl.substring(0, fileUrl.length() - 3) : fileUrl;
            File dcimDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NX_MobileLink");
            if (!dcimDir.exists()) dcimDir.mkdirs();

            String fileName = "NX_RVF_" + System.currentTimeMillis() + ".JPG";
            File targetFile = new File(dcimDir, fileName);

            boolean downloaded = downloadSingleFile(fullUrl, targetFile);
            if (!downloaded && !fullUrl.equals(fileUrl)) {
                downloaded = downloadSingleFile(fileUrl, targetFile);
            }

            if (downloaded && targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "Full photo downloaded successfully: " + targetFile.getAbsolutePath() + " (" + targetFile.length() + " bytes)");
                if (context != null) {
                    try {
                        MediaScannerConnection.scanFile(
                            context.getApplicationContext(),
                            new String[]{ targetFile.getAbsolutePath() },
                            new String[]{ "image/jpeg" },
                            null
                        );
                    } catch (Exception ignored) {}
                }
                return targetFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to download captured photo: " + e.getMessage(), e);
        }
        return null;
    }

    private static boolean downloadSingleFile(String downloadUrl, File destFile) {
        if (downloadUrl == null || !isSafeCameraUrl(downloadUrl)) return false;
        Socket sock = null;
        try {
            URL u = new URL(downloadUrl);
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : 80;
            String path = u.getFile();
            if (path == null || path.isEmpty()) path = "/";

            sock = new Socket();
            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= 21) {
                try { activeWifiNetwork.bindSocket(sock); } catch (Exception ignored) {}
            }
            sock.setSoTimeout(30000);
            sock.connect(new InetSocketAddress(host, port), 5000);

            String req = "GET " + path + " HTTP/1.0\r\n" +
                         "Host: " + host + ":" + port + "\r\n" +
                         "User-Agent: " + getUserAgent() + "\r\n" +
                         "Connection: close\r\n\r\n";
            OutputStream out = sock.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = sock.getInputStream();
            HttpStreamHeader hdr = readHttpStreamHeader(in);
            if (hdr.statusCode >= 200 && hdr.statusCode < 300) {
                if (destFile != null) {
                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                    }
                } else {
                    byte[] buf = new byte[8192];
                    while (in.read(buf) != -1) {}
                }
                return true;
            }
        } catch (Exception e) {
            Log.d(TAG, "downloadSingleFile notice (" + downloadUrl + "): " + e.getMessage());
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    public static void zoomIn(final ActionCallback callback) {
        dispatchAction("ZoomIN", null, callback);
    }

    public static void zoomOut(final ActionCallback callback) {
        dispatchAction("ZoomOUT", null, callback);
    }

    public static void setFlash(final String flashMode, final ActionCallback callback) {
        dispatchAction("SetFlash", "<FLASHMODE>" + flashMode + "</FLASHMODE>", callback);
    }

    public static void setResolution(final String resolution, final ActionCallback callback) {
        dispatchAction("SetResolution", "<RESOLUTION>" + resolution + "</RESOLUTION>", callback);
    }

    public static void setLedTimer(final int seconds, final ActionCallback callback) {
        dispatchAction("SetLED", "<LEDTIME>" + seconds + "</LEDTIME>", callback);
    }

    private static void dispatchAction(final String actionName, final String argsXml, final ActionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                commandLock.lock();
                try {
                    UpnpEndpoint ep = getPrimaryEndpoint(activeCameraIp);
                    if (ep != null) {
                        String st = ep.serviceType != null ? ep.serviceType : UPNP_SERVICE_CONTENT_DIR;
                        String body = buildSoapEnvelope(st, actionName, argsXml);
                        String action = "\"" + st + "#" + actionName + "\"";
                        String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                        String res = sendSoapRequest(url, action, body, 10000);
                        if (callback != null) {
                            callback.onResult(res != null, res);
                        }
                    } else if (callback != null) {
                        callback.onResult(false, "No active endpoint");
                    }
                } finally {
                    commandLock.unlock();
                }
            }
        }, "NX-RVF-Action-" + actionName).start();
    }

    private static void sendUpnpSubscribe(String eventSubUrl, String localIp, int callbackPort) {
        Socket sock = null;
        try {
            URL u = new URL(eventSubUrl);
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : 80;
            String path = u.getFile();
            if (path == null || path.isEmpty()) path = "/";

            sock = new Socket();
            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= 21) {
                try { activeWifiNetwork.bindSocket(sock); } catch (Exception ignored) {}
            }
            sock.setSoTimeout(2500);
            sock.connect(new InetSocketAddress(host, port), 2000);

            String req = "SUBSCRIBE " + path + " HTTP/1.0\r\n" +
                         "User-Agent: " + getUserAgent() + "\r\n" +
                         "HOST: " + host + ":" + port + "\r\n" +
                         "CALLBACK: <http://" + localIp + ":" + callbackPort + "/evetSub>\r\n" +
                         "NT: upnp:event\r\n" +
                         "TIMEOUT: Second-180\r\n" +
                         "Content-Length: 0\r\n\r\n";
            OutputStream out = sock.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[512];
            in.read(buf);
        } catch (Exception ignored) {
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String sendSoapRequest(String urlString, String soapAction, String soapXml, int timeoutMs) {
        try {
            String cleanAction = soapAction != null ? soapAction.replace("\"", "") : "";
            String serviceType = UPNP_SERVICE_CONTENT_DIR;
            String actionName = cleanAction;
            int hashIdx = cleanAction.indexOf('#');
            if (hashIdx > 0) {
                serviceType = cleanAction.substring(0, hashIdx);
                actionName = cleanAction.substring(hashIdx + 1);
            }
            return HumbleHttpClient.httpPostSoap(
                    activeWifiNetwork, urlString, serviceType, actionName, soapXml, getUserAgent(), timeoutMs);
        } catch (Exception ignored) {}
        return null;
    }

    private static String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String extractXmlTag(String xml, String tag) {
        if (xml == null || tag == null) return null;
        String lowerXml = xml.toLowerCase(Locale.US);
        String openTag = "<" + tag.toLowerCase(Locale.US) + ">";
        String closeTag = "</" + tag.toLowerCase(Locale.US) + ">";
        int start = lowerXml.indexOf(openTag);
        if (start >= 0) {
            start += openTag.length();
            int end = lowerXml.indexOf(closeTag, start);
            if (end > start) {
                return xml.substring(start, end);
            }
        }
        return null;
    }

    public static String getAvailableShots() {
        return availableShots;
    }

    public static String getCurrentFlash() {
        return currentFlash;
    }

    public static int getCurrentZoom() {
        return currentZoom;
    }

    public static int getMaxZoom() {
        return maxZoom;
    }

    public static int getCurrentRotation() {
        return currentRotation;
    }

    public static void disconnect() {
        stopLiveStream();
        lastFrameCallback = null;
        primaryEndpoint = null;
        activeEndpoints.clear();
        activeStreamUrl = null;
    }

    public static void disconnect(Object owner) {
        disconnect();
    }
}
