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
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ViewfinderClient:
 * Exact reconstruction of Samsung's official Remote Viewfinder 2.2.11 APK
 * (com.samsung.app.LiveShutter + com.samsung.app.MyControlPoint + org.cybergarage.upnp.*)
 * for Samsung NX1000 and Smart Cameras.
 *
 * KEY PROTOCOL DETAILS RECONSTRUCTED VIA REA:
 * 1. CyberGarage UPnP ControlPoint Discovery:
 *    - Local HTTP Event Server on port 8059 (/evetSub).
 *    - Multicast SSDP NOTIFY listener on 239.255.255.250:1900 (and 1901).
 *    - Unicast SSDP M-SEARCH (ST: ssdp:all, MX: 3) sent from port 8009 every 1000ms.
 * 2. Mutual Authentication & Service SCPD Handshake (LiveShutter.checkDevice):
 *    - Fetches UPnP root description XML (<LOCATION>) with User-Agent: SEC_RVF_<12HexMac>,
 *      Content-Length: 0, HOST: <host>.
 *    - Fetches each <service>'s <SCPDURL> XML via HTTP GET to discover the service
 *      implementing GetInfomation, Shot, ShotWithGPS, MULTIAF, ZoomIN, ZoomOUT,
 *      SetResolution, SetFlash, SetLED, ReleaseSelfTimer.
 * 3. Single GetInfomation Handshake (LiveShutter.GetInfomation_DSC):
 *    - Calls SOAP action 'GetInfomation' ONCE upon connection (no background polling!).
 *    - Extracts <GETIPRESULT> (StreamURL) and <GETINFORMATIONRESULT> (<AVAILSHOTS>, <ROTATION>).
 * 4. Continuous Live Stream & Post-Shot Thumbnail Completion (LiveShutter.Shot_DSC & Thumbnaildown):
 *    - Opens MJPEG stream at StreamURL immediately after GetInfomation succeeds.
 *    - On 'Shot', parses <AFSHOTRESULT> (FileURL) and fetches FileURL + "_SM" via HTTP/1.0 GET
 *      so the camera completes its capture cycle and resumes live view cleanly.
 */
public class ViewfinderClient {
    private static final String TAG = "ViewfinderClient";

    public static final int SSDP_PORT_STANDARD = 1900;
    public static final int SSDP_PORT_MOBILELINK = 1901;
    public static final int SSDP_SEARCH_RESPONSE_PORT = 8009;
    public static final String SSDP_ADDRESS = "239.255.255.250";

    public interface ConnectionCallback {
        void onConnected(String cameraIp);
        void onConnectionFailed(String error);
    }

    public interface FrameCallback {
        void onFrame(Bitmap frame);
        void onStreamError(String error);
    }

    public interface ShutterCallback {
        void onSuccess();
        void onError(String error);
    }

    public static class ServiceEndpoint {
        public String serviceType;
        public String controlUrl;
        public String scpdUrl;
        public boolean hasRvfActions;
    }

    private static Context appContext;
    private static Network activeWifiNetwork;
    private static String userAgentRvf;
    private static final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private static Thread streamThread;

    // Active session parameters populated by checkDevice & GetInfomation_DSC
    private static String activeCameraIp;
    private static String activeStreamUrl;
    private static String activeControlUrl;
    private static String activeServiceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
    private static String availShots = null;
    private static final List<ServiceEndpoint> activeEndpoints = Collections.synchronizedList(new ArrayList<ServiceEndpoint>());

    public static void setActiveWifiNetwork(Network network) {
        activeWifiNetwork = network;
    }

    public static Network getActiveWifiNetwork() {
        return activeWifiNetwork;
    }

    public static String getWifiGatewayIp(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int g = dhcp.gateway;
                    return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
                }
            }
        } catch (Exception ignored) {}
        return "192.168.107.1";
    }

    public static String getLocalIpAddress(Context context) {
        if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    LinkProperties lp = cm.getLinkProperties(activeWifiNetwork);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("p2p") || name.contains("wlan0")) {
                    for (Enumeration<InetAddress> enumIp = intf.getInetAddresses(); enumIp.hasMoreElements();) {
                        InetAddress addr = enumIp.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.107.2";
    }

    /**
     * Reconstructs org.cybergarage.util.Utils.setUseragent(Context) from
     * remote-viewfinder-2-2-11-multi-android.apk:
     * Prefix "SEC_RVF_" followed by 12-hex-character MAC address without colons.
     */
    public static synchronized String getUserAgent(Context context) {
        if (userAgentRvf != null && userAgentRvf.startsWith("SEC_RVF_") && userAgentRvf.length() >= 20) {
            return userAgentRvf;
        }
        String id = null;
        if (context != null) {
            try {
                android.content.SharedPreferences sp = context.getApplicationContext().getSharedPreferences("nx_link", Context.MODE_PRIVATE);
                id = sp.getString("rvf_id", null);
                if (id == null || id.length() != 12) {
                    id = generate12HexMac();
                    sp.edit().putString("rvf_id", id).apply();
                }
            } catch (Exception ignored) {}
        }
        if (id == null || id.length() != 12) {
            id = generate12HexMac();
        }
        userAgentRvf = "SEC_RVF_" + id;
        Log.i(TAG, "Viewfinder User-Agent: " + userAgentRvf);
        return userAgentRvf;
    }

    private static String generate12HexMac() {
        try {
            NetworkInterface wlan = NetworkInterface.getByName("wlan0");
            if (wlan != null) {
                byte[] mac = wlan.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : mac) sb.append(String.format("%02X", b));
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
            sb.append(String.format("%02X", r.nextInt(256)));
        }
        return sb.toString();
    }

    private static Socket createSocket() throws Exception {
        Socket s = new Socket();
        Network net = NetworkManager.getActiveWifiNetwork();
        if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                net.bindSocket(s);
            } catch (Exception ignored) {}
        }
        return s;
    }

    private static HttpURLConnection openConnection(URL url) throws Exception {
        HttpURLConnection conn;
        Network net = NetworkManager.getActiveWifiNetwork();
        if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            conn = (HttpURLConnection) net.openConnection(url);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        return conn;
    }

    public static void connect(final Context context, final String customIp, final ConnectionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                appContext = context.getApplicationContext();
                NetworkManager.bindProcessToWifi(appContext);
                NetworkManager.acquireLocks(appContext);

                activeEndpoints.clear();
                activeStreamUrl = null;
                activeControlUrl = null;

                final List<String> candidateIps = new ArrayList<>();
                if (customIp != null && !customIp.trim().isEmpty()) {
                    candidateIps.add(customIp.trim());
                }
                List<String> discovered = NetworkManager.getCandidateCameraIps(appContext);
                for (String ip : discovered) {
                    if (!candidateIps.contains(ip)) candidateIps.add(ip);
                }

                final AtomicBoolean deviceDiscovered = new AtomicBoolean(false);
                final Set<String> attemptedLocations = Collections.synchronizedSet(new HashSet<String>());
                final long startTime = System.currentTimeMillis();
                final long deadline = startTime + 8500;

                // 1. Multicast NOTIFY listener on port 1900 (Official RVF SSDP port)
                Thread notify1900Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listenSsdpMulticastNotify(SSDP_PORT_STANDARD, 8000, deviceDiscovered, attemptedLocations);
                    }
                }, "RVF-SSDP-Notify-1900");
                notify1900Thread.start();

                // 2. Multicast NOTIFY listener on port 1901
                Thread notify1901Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listenSsdpMulticastNotify(SSDP_PORT_MOBILELINK, 8000, deviceDiscovered, attemptedLocations);
                    }
                }, "RVF-SSDP-Notify-1901");
                notify1901Thread.start();

                // 3. Active M-SEARCH sender & response listener bound to port 8009
                Thread searchThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        performSsdpSearchLoop(8000, candidateIps, deviceDiscovered, attemptedLocations);
                    }
                }, "RVF-SSDP-Search-8009");
                searchThread.start();

                // 4. Give SSDP 1.8s priority before any direct HTTP probing (unless customIp was set)
                if (customIp == null || customIp.trim().isEmpty()) {
                    long ssdpPriorityEnd = startTime + 1800;
                    while (!deviceDiscovered.get() && System.currentTimeMillis() < ssdpPriorityEnd) {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) { break; }
                    }
                }

                // 5. Fallback direct HTTP description probing if SSDP was blocked
                int[] ports = new int[]{7676, 52235, 49152};
                String[] paths = new String[]{
                        "/smp_2_",
                        "/smp_6_",
                        "/description.xml",
                        "/rootDesc.xml",
                        "/smp_4_",
                        "/smp_10_"
                };
                while (!deviceDiscovered.get() && System.currentTimeMillis() < deadline) {
                    for (String ip : candidateIps) {
                        if (deviceDiscovered.get()) break;
                        for (int p : ports) {
                            if (deviceDiscovered.get()) break;
                            if (!isPortOpen(ip, p, 300)) continue;
                            for (String path : paths) {
                                if (deviceDiscovered.get()) break;
                                String testUrl = "http://" + ip + ":" + p + path;
                                if (!attemptedLocations.add(testUrl)) continue;
                                if (fetchAndCheckDevice(testUrl)) {
                                    deviceDiscovered.set(true);
                                    break;
                                }
                            }
                        }
                    }
                    if (deviceDiscovered.get()) break;
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { break; }
                }

                notify1900Thread.interrupt();
                notify1901Thread.interrupt();
                searchThread.interrupt();

                if (activeCameraIp == null && !candidateIps.isEmpty()) {
                    activeCameraIp = candidateIps.get(0);
                }

                // If XML discovery didn't populate endpoints, add known Samsung NX fallbacks
                if (activeEndpoints.isEmpty() && activeCameraIp != null) {
                    addFallbackEndpoints(activeCameraIp);
                }

                // 6. Execute UPnP SOAP action 'GetInfomation' ONCE (LiveShutter.GetInfomation_DSC)
                //    This transitions the camera LCD to Connected and returns <GETIPRESULT> (StreamURL)!
                boolean upnpConfirmed = executeGetInformation(activeCameraIp);

                if (activeStreamUrl == null || activeStreamUrl.isEmpty()) {
                    activeStreamUrl = "http://" + activeCameraIp + ":7679/livestream.avi";
                }

                if (upnpConfirmed || deviceDiscovered.get()) {
                    Log.i(TAG, "Remote Viewfinder connected! CameraIP=" + activeCameraIp +
                            ", StreamURL=" + activeStreamUrl + ", ControlURL=" + activeControlUrl);
                    callback.onConnected(activeCameraIp);
                } else {
                    callback.onConnectionFailed("Could not connect to camera at " + activeCameraIp +
                            ". Please verify camera is in Remote Viewfinder mode and phone Wi-Fi is connected.");
                }
            }
        }).start();
    }

    private static void listenSsdpMulticastNotify(int port, int timeoutMs, AtomicBoolean deviceDiscovered, Set<String> attemptedLocations) {
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

            while (!deviceDiscovered.get() && System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mSock.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    String nts = extractHeader(msg, "NTS");
                    if (nts != null && nts.toLowerCase().contains("byebye")) {
                        continue;
                    }
                    String location = extractHeader(msg, "LOCATION");
                    if (location != null && !location.isEmpty() && attemptedLocations.add(location)) {
                        Log.i(TAG, "RVF SSDP NOTIFY on port " + port + " -> LOCATION: " + location);
                        if (fetchAndCheckDevice(location)) {
                            deviceDiscovered.set(true);
                            return;
                        }
                    }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {
            Log.d(TAG, "RVF Multicast listener on port " + port + " notice: " + e.getMessage());
        } finally {
            if (mSock != null && !mSock.isClosed()) {
                try { mSock.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void performSsdpSearchLoop(int timeoutMs, List<String> candidateIps, AtomicBoolean deviceDiscovered, Set<String> attemptedLocations) {
        DatagramSocket socket = null;
        try {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(SSDP_SEARCH_RESPONSE_PORT));
                Log.i(TAG, "RVF SSDP search socket bound to port " + SSDP_SEARCH_RESPONSE_PORT);
            } catch (Exception e) {
                socket = new DatagramSocket();
            }

            Network net = NetworkManager.getActiveWifiNetwork();
            if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try { net.bindSocket(socket); } catch (Exception ignored) {}
            }
            socket.setSoTimeout(250);

            // Exact CyberGarage SSDPSearchRequest from remote-viewfinder-2-2-11-multi-android.apk
            byte[] search1900All = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: ssdp:all\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            byte[] search1900Root = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: upnp:rootdevice\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            byte[] search1901All = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1901\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: ssdp:all\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            InetAddress multicastGroup = InetAddress.getByName(SSDP_ADDRESS);
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSendTime = 0;

            while (!deviceDiscovered.get() && System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextSendTime) {
                    nextSendTime = now + 1000;
                    try { socket.send(new DatagramPacket(search1900All, search1900All.length, multicastGroup, SSDP_PORT_STANDARD)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1900Root, search1900Root.length, multicastGroup, SSDP_PORT_STANDARD)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1901All, search1901All.length, multicastGroup, SSDP_PORT_MOBILELINK)); } catch (Exception ignored) {}

                    if (candidateIps != null) {
                        for (int i = 0; i < Math.min(candidateIps.size(), 4); i++) {
                            try {
                                InetAddress cip = InetAddress.getByName(candidateIps.get(i));
                                socket.send(new DatagramPacket(search1900All, search1900All.length, cip, SSDP_PORT_STANDARD));
                                socket.send(new DatagramPacket(search1901All, search1901All.length, cip, SSDP_PORT_MOBILELINK));
                            } catch (Exception ignored) {}
                        }
                    }
                }

                try {
                    DatagramPacket recv = new DatagramPacket(buf, buf.length);
                    socket.receive(recv);
                    String resp = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8);
                    String location = extractHeader(resp, "LOCATION");
                    if (location != null && !location.isEmpty() && attemptedLocations.add(location)) {
                        Log.i(TAG, "RVF SSDP M-SEARCH response -> LOCATION: " + location);
                        if (fetchAndCheckDevice(location)) {
                            deviceDiscovered.set(true);
                            return;
                        }
                    }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {
            Log.d(TAG, "RVF SSDP search loop notice: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Reconstructs ControlPoint.addDevice(SSDPPacket) + LiveShutter.checkDevice(Device)
     * from remote-viewfinder-2-2-11-multi-android.apk:
     * 1. Fetches <LOCATION> with User-Agent: SEC_RVF_<12HexMac>, Content-Length: 0, HOST: <host>.
     * 2. Parses all <service> entries and fetches each service's <SCPDURL> XML via HTTP GET
     *    to identify the service implementing GetInfomation / Shot.
     */
    private static boolean fetchAndCheckDevice(String locationUrl) {
        try {
            String ua = getUserAgent(appContext);
            String xml = HumbleHttpClient.httpGetXml(activeWifiNetwork, locationUrl, ua, 3000);
            if (xml == null || xml.isEmpty()) return false;

            URL u = new URL(locationUrl);
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : 80;
            List<ServiceEndpoint> parsed = parseEndpointsFromXml(host, port, xml);
            if (parsed.isEmpty()) return false;

            activeCameraIp = host;

            // Fetch each service's <SCPDURL> XML just like LiveShutter.checkDevice(Device)
            // when calling dev.getAction("GetInfomation"), dev.getAction("Shot"), etc.!
            for (ServiceEndpoint ep : parsed) {
                if (ep.scpdUrl != null) {
                    String scpdXml = HumbleHttpClient.httpGetXml(activeWifiNetwork, ep.scpdUrl, ua, 2500);
                    if (scpdXml != null && (scpdXml.contains("GetInfomation") || scpdXml.contains("Shot") || scpdXml.contains("MULTIAF"))) {
                        ep.hasRvfActions = true;
                        activeControlUrl = ep.controlUrl;
                        activeServiceType = ep.serviceType;
                        Log.i(TAG, "Verified RVF actions (GetInfomation/Shot) in SCPD: " + ep.scpdUrl +
                                " -> serviceType=" + ep.serviceType + ", controlURL=" + ep.controlUrl);
                    }
                }
            }

            // Put verified RVF action endpoints first
            for (ServiceEndpoint ep : parsed) {
                if (ep.hasRvfActions) {
                    activeEndpoints.add(0, ep);
                } else {
                    activeEndpoints.add(ep);
                }
            }

            Log.i(TAG, "Discovered " + parsed.size() + " UPnP services from " + locationUrl);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "fetchAndCheckDevice notice on " + locationUrl + ": " + e.getMessage());
            return false;
        }
    }

    private static void addFallbackEndpoints(String cameraIp) {
        ServiceEndpoint ep1 = new ServiceEndpoint();
        ep1.serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
        ep1.controlUrl = "http://" + cameraIp + ":7676/smp_4_";
        activeEndpoints.add(ep1);

        ServiceEndpoint ep2 = new ServiceEndpoint();
        ep2.serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
        ep2.controlUrl = "http://" + cameraIp + ":7676/smp_7_";
        activeEndpoints.add(ep2);

        ServiceEndpoint ep3 = new ServiceEndpoint();
        ep3.serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
        ep3.controlUrl = "http://" + cameraIp + ":7676/smp_3_";
        activeEndpoints.add(ep3);

        ServiceEndpoint ep4 = new ServiceEndpoint();
        ep4.serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
        ep4.controlUrl = "http://" + cameraIp + ":52235/upnp/control/ConnectionManager";
        activeEndpoints.add(ep4);
    }

    private static List<ServiceEndpoint> parseEndpointsFromXml(String cameraIp, int port, String xml) {
        List<ServiceEndpoint> list = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            ServiceEndpoint cur = null;
            String curTag = "";
            String baseUrl = "http://" + cameraIp + ":" + port;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    curTag = parser.getName();
                    if ("service".equalsIgnoreCase(curTag)) {
                        cur = new ServiceEndpoint();
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        if ("URLBase".equalsIgnoreCase(curTag) && (text.startsWith("http://") || text.startsWith("https://"))) {
                            baseUrl = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
                        } else if (cur != null) {
                            if ("serviceType".equalsIgnoreCase(curTag)) {
                                cur.serviceType = text;
                            } else if ("controlURL".equalsIgnoreCase(curTag)) {
                                cur.controlUrl = resolveUrl(baseUrl, text);
                            } else if ("SCPDURL".equalsIgnoreCase(curTag)) {
                                cur.scpdUrl = resolveUrl(baseUrl, text);
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("service".equalsIgnoreCase(parser.getName()) && cur != null) {
                        if (cur.controlUrl != null && cur.serviceType != null) {
                            list.add(cur);
                        }
                        cur = null;
                    }
                    curTag = "";
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing UPnP endpoints: " + e.getMessage());
        }
        return list;
    }

    private static String resolveUrl(String base, String rel) {
        if (rel == null) return null;
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!rel.startsWith("/")) rel = "/" + rel;
        return base + rel;
    }

    public static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }

    /**
     * Executes UPnP SOAP action 'GetInfomation' ONCE (reconstructing LiveShutter.GetInfomation_DSC).
     * Extracts StreamURL from <GETIPRESULT> and <AVAILSHOTS> from <GETINFORMATIONRESULT>.
     */
    private static boolean executeGetInformation(String cameraIp) {
        List<ServiceEndpoint> snapshot = new ArrayList<>(activeEndpoints);
        for (ServiceEndpoint ep : snapshot) {
            try {
                String safeServiceType = escapeXml(ep.serviceType);
                String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                        "   <s:Body>\r\n" +
                        "      <u:GetInfomation xmlns:u=\"" + safeServiceType + "\">\r\n" +
                        "      </u:GetInfomation>\r\n" +
                        "   </s:Body>\r\n" +
                        "</s:Envelope>";

                String resp = postSoap(ep.controlUrl, ep.serviceType, "GetInfomation", soap, 4000);
                if (resp != null && (resp.contains("GETINFORMATIONRESULT") || resp.contains("GETIPRESULT") || resp.contains("GetInfomationResponse"))) {
                    activeControlUrl = ep.controlUrl;
                    activeServiceType = ep.serviceType;
                    Log.i(TAG, "GetInfomation SUCCESS on " + ep.controlUrl);

                    // Extract <GETIPRESULT> (StreamURL)
                    String ipRes = extractXmlTag(resp, "GETIPRESULT");
                    if (ipRes != null && !ipRes.isEmpty()) {
                        if (ipRes.startsWith("http://") || ipRes.startsWith("https://")) {
                            activeStreamUrl = ipRes;
                        } else if (cameraIp != null) {
                            activeStreamUrl = "http://" + cameraIp + ":7679" + (ipRes.startsWith("/") ? ipRes : "/" + ipRes);
                        }
                        Log.i(TAG, "Parsed StreamURL (<GETIPRESULT>) from camera: " + activeStreamUrl);
                    }

                    // Extract <AVAILSHOTS>
                    String shots = extractXmlTag(resp, "AVAILSHOTS");
                    if (shots != null && !shots.isEmpty()) {
                        availShots = shots;
                        Log.i(TAG, "Parsed AVAILSHOTS from camera: " + availShots);
                    }
                    return true;
                }
            } catch (Exception e) {
                Log.d(TAG, "GetInfomation on " + ep.controlUrl + " returned: " + e.getMessage());
            }
        }

        if (activeControlUrl == null && !snapshot.isEmpty()) {
            activeControlUrl = snapshot.get(0).controlUrl;
            activeServiceType = snapshot.get(0).serviceType;
        }
        return false;
    }

    private static String extractXmlTag(String xml, String tagName) {
        if (xml == null || tagName == null) return null;
        try {
            String unescaped = xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
            Pattern p = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?" + tagName + "\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?" + tagName + ">", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(unescaped);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String postSoap(String urlStr, String serviceType, String action, String soapBody, int timeoutMs) throws Exception {
        return HumbleHttpClient.httpPostSoap(
                activeWifiNetwork,
                urlStr,
                serviceType,
                action,
                soapBody,
                getUserAgent(appContext),
                timeoutMs
        );
    }

    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void stopKeepAlive() {
        // Official Remote Viewfinder APK does NOT poll GetInfomation in a background loop!
        // Keeping the MJPEG stream open on StreamURL maintains the session.
    }

    /**
     * Starts live video stream from StreamURL (<GETIPRESULT>) matching
     * LiveShutter$MjpegViewThread & MjpegInputStream in remote-viewfinder-2-2-11-multi-android.apk.
     */
    public static void startLiveStream(final String cameraIp, final FrameCallback callback) {
        if (isStreaming.get()) {
            stopLiveStream();
        }
        isStreaming.set(true);

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String targetStreamUrl = (activeStreamUrl != null && !activeStreamUrl.isEmpty()) ?
                        activeStreamUrl : "http://" + cameraIp + ":7679/livestream.avi";

                Log.i(TAG, "Starting RVF live stream pipeline at: " + targetStreamUrl);

                while (isStreaming.get()) {
                    // If camera is currently executing a 'Shot' capture, wait briefly before reconnecting stream
                    if (isCapturing.get()) {
                        try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                        continue;
                    }

                    // Primary path: MjpegInputStream.read(String url) uses URL.openConnection()
                    HttpURLConnection conn = null;
                    InputStream in = null;
                    boolean gotFrames = false;
                    try {
                        URL url = new URL(targetStreamUrl);
                        conn = openConnection(url);
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(12000);
                        conn.setRequestProperty("User-Agent", getUserAgent(appContext));
                        conn.connect();

                        int code = conn.getResponseCode();
                        Log.i(TAG, "Stream response code: " + code);
                        if (code == 200) {
                            in = new BufferedInputStream(conn.getInputStream(), 32768);
                            gotFrames = readMjpegStream(in, callback);
                        }
                    } catch (Exception e) {
                        if (isStreaming.get() && !isCapturing.get()) {
                            Log.d(TAG, "HttpURLConnection stream notice: " + e.getMessage());
                        }
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Exception ignored) {}
                        }
                        if (conn != null) {
                            try { conn.disconnect(); } catch (Exception ignored) {}
                        }
                    }

                    // Fallback path: Raw HTTP/1.0 socket stream if HttpURLConnection did not yield frames
                    if (!gotFrames && isStreaming.get() && !isCapturing.get()) {
                        HumbleHttpClient rawClient = new HumbleHttpClient();
                        try {
                            InputStream rawIn = rawClient.request(activeWifiNetwork, targetStreamUrl, getUserAgent(appContext), 5000);
                            readMjpegStream(new BufferedInputStream(rawIn, 32768), callback);
                        } catch (Exception e) {
                            if (isStreaming.get() && !isCapturing.get()) {
                                Log.d(TAG, "Raw HTTP/1.0 stream notice: " + e.getMessage());
                            }
                        } finally {
                            rawClient.close();
                        }
                    }

                    if (isStreaming.get()) {
                        try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                    }
                }

                Log.i(TAG, "Live stream pipeline stopped");
            }
        }, "NX-RVF-StreamThread");
        streamThread.start();
    }

    private static boolean readMjpegStream(InputStream in, FrameCallback callback) {
        final int MAX_FRAME_SIZE = 5 * 1024 * 1024;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
        byte[] readBuf = new byte[8192];
        int prev = -1;
        boolean inFrame = false;
        boolean decodedAny = false;

        try {
            while (isStreaming.get()) {
                int bytesRead = in.read(readBuf);
                if (bytesRead < 0) {
                    break;
                }

                for (int i = 0; i < bytesRead; i++) {
                    int b = readBuf[i] & 0xFF;

                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) {
                            inFrame = true;
                            buffer.reset();
                            buffer.write(0xFF);
                            buffer.write(0xD8);
                        }
                    } else {
                        buffer.write(b);
                        if (buffer.size() > MAX_FRAME_SIZE) {
                            Log.w(TAG, "MJPEG frame size exceeded limit (5MB); discarding frame");
                            buffer.reset();
                            inFrame = false;
                        } else if (prev == 0xFF && b == 0xD9) {
                            inFrame = false;
                            byte[] frameData = buffer.toByteArray();
                            if (frameData.length > 512) {
                                Bitmap bmp = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
                                if (bmp != null) {
                                    decodedAny = true;
                                    callback.onFrame(bmp);
                                }
                            }
                            buffer.reset();
                        }
                    }
                    prev = b;
                }
            }
        } catch (Exception e) {
            if (isStreaming.get()) {
                Log.d(TAG, "Live stream frame cycle: " + e.getMessage());
            }
        }
        return decodedAny;
    }

    public static void stopLiveStream() {
        isStreaming.set(false);
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
    }

    /**
     * Triggers hardware autofocus & shutter capture using Samsung's official UPnP 'Shot' action
     * (reconstructed from LiveShutter.Shot_DSC & LiveShutter.Thumbnaildown in
     * remote-viewfinder-2-2-11-multi-android.apk).
     *
     * CRITICAL POST-SHOT SEQUENCE:
     * When 'Shot' succeeds, the camera returns <AFSHOTRESULT> containing FileURL and
     * <AVAILSHOTS>. LiveShutter.Thumbnaildown() immediately performs an HTTP/1.0 GET
     * on FileURL + "_SM" to fetch the review thumbnail, which signals the camera to
     * finish its shot cycle and resume the live viewfinder stream!
     */
    public static void triggerShutter(final String cameraIp, final ShutterCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                isCapturing.set(true);
                try {
                    boolean success = false;
                    String shotResponse = null;

                    List<ServiceEndpoint> targets = new ArrayList<>();
                    if (activeControlUrl != null && activeServiceType != null) {
                        ServiceEndpoint primary = new ServiceEndpoint();
                        primary.controlUrl = activeControlUrl;
                        primary.serviceType = activeServiceType;
                        primary.hasRvfActions = true;
                        targets.add(primary);
                    }
                    for (ServiceEndpoint ep : activeEndpoints) {
                        if (activeControlUrl == null || !activeControlUrl.equals(ep.controlUrl)) {
                            targets.add(ep);
                        }
                    }

                    for (ServiceEndpoint ep : targets) {
                        try {
                            String safeServiceType = escapeXml(ep.serviceType);
                            String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                                    "   <s:Body>\r\n" +
                                    "      <u:Shot xmlns:u=\"" + safeServiceType + "\">\r\n" +
                                    "      </u:Shot>\r\n" +
                                    "   </s:Body>\r\n" +
                                    "</s:Envelope>";

                            String resp = postSoap(ep.controlUrl, ep.serviceType, "Shot", soap, 10000);
                            if (resp != null && (resp.contains("ShotResponse") || resp.contains("AFSHOTRESULT") || resp.contains("AVAILSHOTS"))) {
                                Log.i(TAG, "UPnP Shot action SUCCESS on " + ep.controlUrl);
                                shotResponse = resp;
                                success = true;
                                break;
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "UPnP Shot on " + ep.controlUrl + " returned: " + e.getMessage());
                        }
                    }

                    // Complete post-shot protocol: parse <AFSHOTRESULT> (FileURL) and fetch FileURL + "_SM"
                    if (shotResponse != null) {
                        String fileUrl = extractXmlTag(shotResponse, "AFSHOTRESULT");
                        String shots = extractXmlTag(shotResponse, "AVAILSHOTS");
                        if (shots != null && !shots.isEmpty()) {
                            availShots = shots;
                        }
                        if (fileUrl != null && !fileUrl.isEmpty()) {
                            String thumbSmUrl = fileUrl.endsWith("_SM") ? fileUrl : (fileUrl + "_SM");
                            Log.i(TAG, "Fetching post-shot thumbnail (Thumbnaildown): " + thumbSmUrl);
                            downloadPostShotThumbnail(thumbSmUrl);
                        }
                    }

                    if (success) {
                        callback.onSuccess();
                    } else {
                        callback.onError("Camera did not acknowledge Shot command");
                    }
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                } finally {
                    isCapturing.set(false);
                }
            }
        }).start();
    }

    /**
     * Reconstructs LiveShutter.Thumbnaildown() from remote-viewfinder-2-2-11-multi-android.apk:
     * Sends raw HTTP/1.0 GET to FileURL + "_SM" with HOST and Content-Length: 0.
     */
    private static void downloadPostShotThumbnail(String thumbSmUrl) {
        HumbleHttpClient client = new HumbleHttpClient();
        try {
            InputStream in = client.request(activeWifiNetwork, thumbSmUrl, getUserAgent(appContext), 5000);
            int expected = client.getContentLength();
            byte[] buf = new byte[4096];
            int totalRead = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                totalRead += len;
                if (expected > 0 && totalRead >= expected) {
                    break;
                }
            }
            Log.i(TAG, "Completed Thumbnaildown (" + totalRead + " bytes) from " + thumbSmUrl);
        } catch (Exception e) {
            Log.d(TAG, "Thumbnaildown notice: " + e.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * Executes additional official Remote Viewfinder UPnP SOAP actions reconstructed from LiveShutter:
     * - "MULTIAF" (no args)
     * - "ZoomIN" (no args)
     * - "ZoomOUT" (no args)
     * - "ReleaseSelfTimer" (no args)
     * - "SetFlash" (arg: FLASHMODE = "OFF" | "AUTO")
     * - "SetLED" (arg: LEDTIME = "0" | "2" | "10")
     * - "SetResolution" (arg: RESOLUTION = "%dx%d")
     */
    public static boolean executeRvfControlAction(String actionName, String argName, String argValue) {
        if (activeControlUrl == null || activeServiceType == null) return false;
        try {
            String safeServiceType = escapeXml(activeServiceType);
            String safeAction = escapeXml(actionName);
            StringBuilder body = new StringBuilder();
            body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
                .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n")
                .append("   <s:Body>\r\n")
                .append("      <u:").append(safeAction).append(" xmlns:u=\"").append(safeServiceType).append("\">\r\n");
            if (argName != null && argValue != null) {
                body.append("         <").append(escapeXml(argName)).append(">")
                    .append(escapeXml(argValue))
                    .append("</").append(escapeXml(argName)).append(">\r\n");
            }
            body.append("      </u:").append(safeAction).append(">\r\n")
                .append("   </s:Body>\r\n")
                .append("</s:Envelope>");

            String resp = postSoap(activeControlUrl, activeServiceType, actionName, body.toString(), 4000);
            return resp != null && resp.contains(actionName + "Response");
        } catch (Exception e) {
            Log.d(TAG, "executeRvfControlAction (" + actionName + ") notice: " + e.getMessage());
            return false;
        }
    }

    public static void disconnect() {
        stopLiveStream();
        stopKeepAlive();
    }

    private static String extractHeader(String response, String headerName) {
        String lowerResp = response.toLowerCase();
        String lowerHeader = headerName.toLowerCase() + ":";
        int idx = lowerResp.indexOf(lowerHeader);
        if (idx != -1) {
            int lineEnd = response.indexOf("\r\n", idx);
            if (lineEnd == -1) lineEnd = response.indexOf("\n", idx);
            if (lineEnd == -1) lineEnd = response.length();
            return response.substring(idx + lowerHeader.length(), lineEnd).trim();
        }
        return null;
    }
}
