/*
 * Copyright (c) 2026 FL4K901. All rights reserved.
 * SPDX-License-Identifier: MIT
 *
 * This software is licensed under the MIT License.
 * See LICENSE file in the project root for full license terms.
 */

package com.nx.mobilelink;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MobileLinkClient:
 * Exact reconstruction of Samsung's official MobileLink 1.7.13 APK
 * (com.samsung.app.BrowseManager + org.cybergarage.upnp.*) for Samsung NX1000
 * and Smart Cameras.
 *
 * KEY PROTOCOL DETAILS RECONSTRUCTED VIA REA:
 * 1. Non-standard SSDP Multicast Port 1901 (org.cybergarage.upnp.ssdp.SSDP.PORT = 1901)
 *    in addition to standard port 1900, with M-SEARCH (ST: ssdp:all, MX: 3) sent from
 *    unicast port 8009 every 1000ms and local UPnP Event Server on port 8059 (/evetSub).
 * 2. Mutual Authentication Handshake on GET <LOCATION> using HTTP/1.0 with
 *    User-Agent: SEC_DSC_<12HexMac>, HOST: <host>:<port>, Content-Length: 0,
 *    followed by fetching each service's <SCPDURL> XML.
 * 3. ContentDirectory#Browse pagination matching BrowseManager.doBrowse():
 *    RequestedCount=100 for root ObjectID="0", and RequestedCount=20 for child containers.
 * 4. Sequential HTTP/1.0 downloads with 200ms inter-file delay (matching SaveAllFileTask).
 */
public class MobileLinkClient {
    private static final String TAG = "MobileLinkClient";

    /**
     * Samsung MobileLink custom SSDP port (1901) and standard SSDP port (1900),
     * plus CyberGarage's default search response port (8009).
     */
    public static final int SSDP_PORT_MOBILELINK = 1901;
    public static final int SSDP_PORT_STANDARD = 1900;
    public static final int SSDP_SEARCH_RESPONSE_PORT = 8009;
    public static final String SSDP_ADDRESS = "239.255.255.250";

    public static class ServiceInfo {
        public String serviceType;
        public String serviceId;
        public String scpdUrl;
        public String controlUrl;
        public String eventSubUrl;
        public boolean hasBrowseAction;
    }

    public static class DeviceInfo {
        public String friendlyName = "Samsung Camera (MobileLink)";
        public String modelName = "NX Series";
        public String manufacturer = "Samsung";
        public String baseUrl;
        public String locationUrl;
        public String controlUrl;
        public List<ServiceInfo> services = new ArrayList<>();
    }

    public interface DiscoveryCallback {
        void onDeviceFound(DeviceInfo device);
        void onDiscoveryFailed(String error);
    }

    public interface DownloadProgressListener {
        void onProgress(long bytesRead, long totalBytes, int percent);
    }

    private static Context appContext;
    private static Network activeWifiNetwork;
    public static final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private static DeviceInfo activeDevice;

    private static final Map<String, String> MIME_EXT = new HashMap<>();
    static {
        MIME_EXT.put("image/jpeg", ".jpg");
        MIME_EXT.put("image/jpg", ".jpg");
        MIME_EXT.put("image/png", ".png");
        MIME_EXT.put("image/gif", ".gif");
        MIME_EXT.put("image/bmp", ".bmp");
        MIME_EXT.put("video/mp4", ".mp4");
        MIME_EXT.put("video/mpeg", ".mpg");
        MIME_EXT.put("video/x-msvideo", ".avi");
        MIME_EXT.put("video/quicktime", ".mov");
        MIME_EXT.put("video/3gpp", ".3gp");
    }

    public static void setActiveWifiNetwork(Network network) {
        activeWifiNetwork = network;
        Log.i(TAG, "MobileLink setActiveWifiNetwork: " + network);
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

    private static String userAgent;

    /**
     * Reconstructs org.cybergarage.util.Utils.setUseragent(Context) from mobilelink-1-7-13.apk:
     * Prefix "SEC_DSC_" followed by 12-hex-character MAC address without colons.
     */
    public static synchronized String getUserAgent() {
        if (userAgent != null && userAgent.startsWith("SEC_DSC_") && userAgent.length() >= 20) {
            return userAgent;
        }
        String id = null;
        if (appContext != null) {
            try {
                android.content.SharedPreferences sp = appContext.getSharedPreferences("nx_link", Context.MODE_PRIVATE);
                id = sp.getString("dsc_id", null);
                if (id == null || id.length() != 12) {
                    id = generate12HexMac();
                    sp.edit().putString("dsc_id", id).apply();
                }
            } catch (Exception ignored) {}
        }
        if (id == null || id.length() != 12) {
            id = generate12HexMac();
        }
        userAgent = "SEC_DSC_" + id;
        Log.i(TAG, "MobileLink User-Agent: " + userAgent);
        return userAgent;
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

    public static HttpURLConnection openConnection(URL url) throws Exception {
        HttpURLConnection conn;
        Network net = NetworkManager.getActiveWifiNetwork();
        if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            conn = (HttpURLConnection) net.openConnection(url);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        return conn;
    }

    // =========================================================================
    // DISCOVERY ENGINE: CyberGarage SSDP (Ports 1901 & 1900, Local Port 8009)
    // =========================================================================

    public static void discoverCamera(final Context context, final String customIp, final int customPort, final DiscoveryCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                appContext = context.getApplicationContext();
                NetworkManager.bindProcessToWifi(appContext);
                NetworkManager.acquireLocks(appContext);

                final DeviceInfo[] foundDevice = new DeviceInfo[1];
                final AtomicBoolean resolved = new AtomicBoolean(false);
                final Set<String> attemptedLocations = Collections.synchronizedSet(new HashSet<String>());

                final List<String> candidateIps = new ArrayList<>();
                if (customIp != null && !customIp.trim().isEmpty()) {
                    candidateIps.add(customIp.trim());
                }
                List<String> discovered = NetworkManager.getCandidateCameraIps(appContext);
                for (String ip : discovered) {
                    if (!candidateIps.contains(ip)) candidateIps.add(ip);
                }

                final int[] candidatePorts = (customPort > 0) ? new int[]{customPort} :
                        new int[]{7676, 52235, 49152, 80};

                final long startTime = System.currentTimeMillis();
                final long deadline = startTime + 8500;

                // 1. Multicast NOTIFY listener on port 1901 (Samsung MobileLink SSDP port!)
                Thread notify1901Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listenSsdpMulticastNotify(SSDP_PORT_MOBILELINK, 8000, resolved, foundDevice, attemptedLocations);
                    }
                }, "MobileLink-SSDP-Notify-1901");
                notify1901Thread.start();

                // 2. Multicast NOTIFY listener on port 1900 (Standard SSDP port)
                Thread notify1900Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listenSsdpMulticastNotify(SSDP_PORT_STANDARD, 8000, resolved, foundDevice, attemptedLocations);
                    }
                }, "MobileLink-SSDP-Notify-1900");
                notify1900Thread.start();

                // 3. Active M-SEARCH sender & response listener bound to port 8009
                Thread searchThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        performSsdpSearchLoop(8000, candidateIps, resolved, foundDevice, attemptedLocations);
                    }
                }, "MobileLink-SSDP-Search-8009");
                searchThread.start();

                // 4. Give SSDP 1.8 seconds priority so we don't spam the camera's socket table
                //    unless the user explicitly provided a customIp
                if (customIp == null || customIp.trim().isEmpty()) {
                    long ssdpPriorityEnd = startTime + 1800;
                    while (!resolved.get() && System.currentTimeMillis() < ssdpPriorityEnd) {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) { break; }
                    }
                }

                // 5. Fallback direct HTTP description probing if SSDP packets are filtered
                while (!resolved.get() && System.currentTimeMillis() < deadline) {
                    for (String ip : candidateIps) {
                        if (resolved.get()) break;
                        for (int p : candidatePorts) {
                            if (resolved.get()) break;
                            if (isPortOpen(ip, p, 300)) {
                                Log.i(TAG, "Port " + p + " is open on " + ip + "! Probing UPnP description...");
                                DeviceInfo dev = probeHttpEndpoint(ip, p, attemptedLocations);
                                if (dev != null && resolved.compareAndSet(false, true)) {
                                    foundDevice[0] = dev;
                                    break;
                                }
                            }
                        }
                    }
                    if (resolved.get()) break;
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
                }

                notify1901Thread.interrupt();
                notify1900Thread.interrupt();
                searchThread.interrupt();

                if (foundDevice[0] != null) {
                    activeDevice = foundDevice[0];
                    callback.onDeviceFound(foundDevice[0]);
                } else {
                    callback.onDiscoveryFailed("Camera not found in MobileLink mode. Ensure camera is in MobileLink mode and phone Wi-Fi is connected to it.");
                }
            }
        }).start();
    }

    /**
     * Listens for multicast SSDP NOTIFY packets on 239.255.255.250:<port>
     * (port 1901 for MobileLink, port 1900 for standard UPnP).
     */
    private static void listenSsdpMulticastNotify(int port, int timeoutMs, AtomicBoolean resolved, DeviceInfo[] foundDevice, Set<String> attemptedLocations) {
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
                    String nts = extractHeader(msg, "NTS");
                    if (nts != null && nts.toLowerCase().contains("byebye")) {
                        continue;
                    }
                    String location = extractHeader(msg, "LOCATION");
                    if (location != null && !location.isEmpty() && attemptedLocations.add(location)) {
                        Log.i(TAG, "SSDP NOTIFY on port " + port + " -> LOCATION: " + location);
                        DeviceInfo dev = fetchAndParseDeviceDescription(location);
                        if (dev != null && dev.controlUrl != null && resolved.compareAndSet(false, true)) {
                            foundDevice[0] = dev;
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

    /**
     * Sends M-SEARCH * HTTP/1.1 every 1000ms from local port 8009 to
     * 239.255.255.250:1901 and 239.255.255.250:1900 (plus unicast to candidate IPs),
     * matching BrowseManager$SearchTimerTask & SSDPSearchResponseSocketList in
     * mobilelink-1-7-13.apk.
     */
    private static void performSsdpSearchLoop(int timeoutMs, List<String> candidateIps, AtomicBoolean resolved, DeviceInfo[] foundDevice, Set<String> attemptedLocations) {
        DatagramSocket socket = null;
        try {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(SSDP_SEARCH_RESPONSE_PORT));
                Log.i(TAG, "SSDP search socket bound to port " + SSDP_SEARCH_RESPONSE_PORT);
            } catch (Exception e) {
                socket = new DatagramSocket();
            }

            Network net = NetworkManager.getActiveWifiNetwork();
            if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try { net.bindSocket(socket); } catch (Exception ignored) {}
            }
            socket.setSoTimeout(250);

            // Exact CyberGarage SSDPSearchRequest payloads for ports 1901 and 1900
            byte[] search1901All = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1901\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: ssdp:all\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            byte[] search1901Root = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1901\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: upnp:rootdevice\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

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

            InetAddress multicastGroup = InetAddress.getByName(SSDP_ADDRESS);
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSendTime = 0;

            while (!resolved.get() && System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextSendTime) {
                    nextSendTime = now + 1000;
                    // 1. Multicast to 239.255.255.250:1901 (Primary MobileLink port!) and 1900
                    try { socket.send(new DatagramPacket(search1901All, search1901All.length, multicastGroup, SSDP_PORT_MOBILELINK)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1901Root, search1901Root.length, multicastGroup, SSDP_PORT_MOBILELINK)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1900All, search1900All.length, multicastGroup, SSDP_PORT_STANDARD)); } catch (Exception ignored) {}
                    try { socket.send(new DatagramPacket(search1900Root, search1900Root.length, multicastGroup, SSDP_PORT_STANDARD)); } catch (Exception ignored) {}

                    // 2. Unicast directly to candidate camera IPs on ports 1901 and 1900
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
                    String location = extractHeader(resp, "LOCATION");
                    if (location != null && !location.isEmpty() && attemptedLocations.add(location)) {
                        Log.i(TAG, "SSDP M-SEARCH response -> LOCATION: " + location);
                        DeviceInfo dev = fetchAndParseDeviceDescription(location);
                        if (dev != null && dev.controlUrl != null && resolved.compareAndSet(false, true)) {
                            foundDevice[0] = dev;
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

    private static DeviceInfo probeHttpEndpoint(String ip, int port, Set<String> attemptedLocations) {
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
            DeviceInfo dev = fetchAndParseDeviceDescription(testUrl);
            if (dev != null && dev.controlUrl != null) return dev;
        }
        return null;
    }

    /**
     * Fetches the UPnP device description XML at locationUrl with the mandatory
     * User-Agent: SEC_DSC_<12HexMac>, Content-Length: 0, HOST: <host>:<port> headers,
     * and then fetches each service's <SCPDURL> XML (matching BrowseManager ->
     * Device.getAction("Browse") -> Service.getSCPDNode() in mobilelink-1-7-13.apk).
     */
    private static DeviceInfo fetchAndParseDeviceDescription(String locationUrl) {
        try {
            String xml = HumbleHttpClient.httpGetXml(activeWifiNetwork, locationUrl, getUserAgent(), 3000);
            if (xml != null && !xml.isEmpty()) {
                DeviceInfo dev = parseDeviceDescriptionXml(locationUrl, xml);
                if (dev != null && dev.controlUrl != null) {
                    return dev;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getLocalName(Node node) {
        if (node == null) return "";
        String name = node.getLocalName();
        if (name == null || name.isEmpty()) name = node.getNodeName();
        if (name != null && name.contains(":")) {
            name = name.substring(name.indexOf(':') + 1);
        }
        return name != null ? name : "";
    }

    private static Element findElementByLocalName(Node root, String targetName) {
        if (root == null) return null;
        if (root.getNodeType() == Node.ELEMENT_NODE && getLocalName(root).equalsIgnoreCase(targetName)) {
            return (Element) root;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element found = findElementByLocalName(children.item(i), targetName);
            if (found != null) return found;
        }
        return null;
    }

    private static void findAllElementsByLocalName(Node root, String targetName, List<Element> out) {
        if (root == null) return;
        if (root.getNodeType() == Node.ELEMENT_NODE && getLocalName(root).equalsIgnoreCase(targetName)) {
            out.add((Element) root);
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            findAllElementsByLocalName(children.item(i), targetName, out);
        }
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

    public static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }

    private static boolean isSafeCameraUrl(String baseUrl, String candidateUrl) {
        if (candidateUrl == null || baseUrl == null) return false;
        try {
            URL base = new URL(baseUrl);
            URL cand = new URL(candidateUrl);
            String candHost = cand.getHost();
            if (candHost == null || candHost.equalsIgnoreCase("localhost") || candHost.startsWith("127.") || candHost.equals("0.0.0.0")) {
                return false;
            }
            if (candHost.equalsIgnoreCase(base.getHost())) {
                return true;
            }
            // Allow any RFC1918 private camera IPv4 subnet (e.g. 192.168.101.*, 192.168.107.*, 10.*, 172.*)
            if (candHost.startsWith("192.168.") || candHost.startsWith("10.") || candHost.startsWith("172.")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static DeviceInfo parseDeviceDescriptionXml(String locationUrl, String xml) {
        try {
            DocumentBuilderFactory dbf = createSecureDocumentBuilderFactory();
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            DeviceInfo device = new DeviceInfo();
            device.locationUrl = locationUrl;
            URL u = new URL(locationUrl);
            device.baseUrl = u.getProtocol() + "://" + u.getHost() + (u.getPort() != -1 ? ":" + u.getPort() : "");

            // Honor <URLBase> if specified in the UPnP root description XML
            Element urlBaseElem = findElementByLocalName(doc.getDocumentElement(), "URLBase");
            if (urlBaseElem != null && urlBaseElem.getTextContent() != null) {
                String ub = urlBaseElem.getTextContent().trim();
                if (ub.startsWith("http://") || ub.startsWith("https://")) {
                    if (ub.endsWith("/")) ub = ub.substring(0, ub.length() - 1);
                    device.baseUrl = ub;
                }
            }

            Element fnElem = findElementByLocalName(doc.getDocumentElement(), "friendlyName");
            if (fnElem != null) device.friendlyName = fnElem.getTextContent().trim();

            Element mnElem = findElementByLocalName(doc.getDocumentElement(), "modelName");
            if (mnElem != null) device.modelName = mnElem.getTextContent().trim();

            Element mfrElem = findElementByLocalName(doc.getDocumentElement(), "manufacturer");
            if (mfrElem != null) device.manufacturer = mfrElem.getTextContent().trim();

            List<Element> svcNodes = new ArrayList<>();
            findAllElementsByLocalName(doc.getDocumentElement(), "service", svcNodes);
            for (Element svcElem : svcNodes) {
                ServiceInfo s = new ServiceInfo();
                Element stElem = findElementByLocalName(svcElem, "serviceType");
                if (stElem != null) s.serviceType = stElem.getTextContent().trim();
                Element sidElem = findElementByLocalName(svcElem, "serviceId");
                if (sidElem != null) s.serviceId = sidElem.getTextContent().trim();
                Element scpdElem = findElementByLocalName(svcElem, "SCPDURL");
                if (scpdElem != null) {
                    s.scpdUrl = resolveUrl(device.baseUrl, scpdElem.getTextContent().trim());
                }
                Element scElem = findElementByLocalName(svcElem, "controlURL");
                if (scElem != null) {
                    s.controlUrl = resolveUrl(device.baseUrl, scElem.getTextContent().trim());
                }
                Element evElem = findElementByLocalName(svcElem, "eventSubURL");
                if (evElem != null) {
                    s.eventSubUrl = resolveUrl(device.baseUrl, evElem.getTextContent().trim());
                }

                // Fetch <SCPDURL> just like CyberGarage's dev.getAction("Browse") -> Service.getSCPDNode()!
                if (s.scpdUrl != null) {
                    String scpdXml = HumbleHttpClient.httpGetXml(activeWifiNetwork, s.scpdUrl, getUserAgent(), 2500);
                    if (scpdXml != null && scpdXml.contains("Browse")) {
                        s.hasBrowseAction = true;
                        Log.i(TAG, "Verified Browse action in SCPD: " + s.scpdUrl + " -> controlURL: " + s.controlUrl);
                    }
                }

                device.services.add(s);
                if (s.hasBrowseAction || (s.serviceType != null && s.serviceType.toLowerCase().contains("contentdirectory"))) {
                    device.controlUrl = s.controlUrl;
                }
            }

            if (device.controlUrl == null) {
                for (ServiceInfo s : device.services) {
                    if (s.controlUrl != null) {
                        device.controlUrl = s.controlUrl;
                        break;
                    }
                }
            }

            if (device.services.isEmpty() && !xml.toLowerCase().contains("root")) {
                return null;
            }
            if (device.controlUrl == null) {
                device.controlUrl = device.baseUrl + "/smp_4_";
            }

            Log.i(TAG, "Parsed MobileLink device: " + device.friendlyName + " (" + device.modelName + "), controlUrl=" + device.controlUrl);
            return device;
        } catch (Exception e) {
            Log.w(TAG, "Error parsing description XML from " + locationUrl + ": " + e.getMessage());
            return null;
        }
    }

    private static String resolveUrl(String base, String rel) {
        if (rel == null) return null;
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!rel.startsWith("/")) rel = "/" + rel;
        return base + rel;
    }

    // =========================================================================
    // SESSION MANAGEMENT (Zero socket polling - camera socket-silent between actions)
    // =========================================================================

    public static synchronized void startKeepAlive(Context context, DeviceInfo device) {
        appContext = context.getApplicationContext();
        activeDevice = device;
        Log.i(TAG, "MobileLink connected to " + device.baseUrl + " (Socket-silent mode active)");
    }

    public static synchronized void stopKeepAlive() {
        activeDevice = null;
    }

    // =========================================================================
    // PHOTO BROWSING: Official BrowseManager Pagination (100 for "0", 20 for folders)
    // =========================================================================

    public static List<PhotoItem> browsePhotos(DeviceInfo device) throws Exception {
        if (device == null || device.controlUrl == null) {
            throw new IllegalArgumentException("Invalid device or control URL");
        }
        List<PhotoItem> allPhotos = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        Log.i(TAG, "Starting recursive photo browse for: " + device.controlUrl);

        // Start at root container "0" (matching BrowseManager.doBrowse in mobilelink-1-7-13.apk)
        browseRecursive(device, "0", allPhotos, visited, 0);

        // Fallback if root container "0" returned 0 items
        if (allPhotos.isEmpty() && visited.size() <= 1) {
            String[] fallbacks = new String[]{"1", "DCIM", "ALL", "100", "0/0", "0/1"};
            for (String fbId : fallbacks) {
                if (visited.add(fbId)) {
                    browseRecursive(device, fbId, allPhotos, visited, 1);
                    if (!allPhotos.isEmpty()) break;
                }
            }
        }

        Log.i(TAG, "MobileLink total files found on camera: " + allPhotos.size());
        return allPhotos;
    }

    private static void browseRecursive(DeviceInfo device, String objectId, List<PhotoItem> outPhotos, Set<String> visited, int depth) {
        if (depth > 10) return;
        if (visited.contains(objectId) && depth > 0) return;
        visited.add(objectId);

        Log.i(TAG, "Browsing container: " + objectId + " (depth " + depth + ")");

        int startingIndex = 0;
        // Exact pagination from com.samsung.app.BrowseManager.doBrowse() in mobilelink-1-7-13.apk:
        // Root container "0" uses RequestedCount=100; child media folders use RequestedCount=20!
        int requestedCount = ("0".equals(objectId)) ? 100 : 20;
        boolean hasMore = true;
        int safetyLoops = 250;

        String serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
        if (device.services != null) {
            for (ServiceInfo s : device.services) {
                if (s.hasBrowseAction && s.serviceType != null) {
                    serviceType = s.serviceType;
                    break;
                }
                if (s.serviceType != null && s.serviceType.toLowerCase().contains("contentdirectory")) {
                    serviceType = s.serviceType;
                    break;
                }
            }
        }

        List<String> subContainers = new ArrayList<>();

        while (hasMore && safetyLoops-- > 0) {
            try {
                String safeObjectId = escapeXml(objectId);
                String safeServiceType = escapeXml(serviceType);
                // Exact CyberGarage SOAP envelope format from ActionRequest / SOAPRequest
                String soapRequest =
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                        "   <s:Body>\r\n" +
                        "      <u:Browse xmlns:u=\"" + safeServiceType + "\">\r\n" +
                        "         <ObjectID>" + safeObjectId + "</ObjectID>\r\n" +
                        "         <BrowseFlag>BrowseDirectChildren</BrowseFlag>\r\n" +
                        "         <Filter>*</Filter>\r\n" +
                        "         <StartingIndex>" + startingIndex + "</StartingIndex>\r\n" +
                        "         <RequestedCount>" + requestedCount + "</RequestedCount>\r\n" +
                        "         <SortCriteria></SortCriteria>\r\n" +
                        "      </u:Browse>\r\n" +
                        "   </s:Body>\r\n" +
                        "</s:Envelope>";

                String responseXml = HumbleHttpClient.httpPostSoap(
                        activeWifiNetwork,
                        device.controlUrl,
                        serviceType,
                        "Browse",
                        soapRequest,
                        getUserAgent(),
                        12000
                );

                if (responseXml != null && !responseXml.isEmpty()) {
                    String didlXml = extractResultFromSoap(responseXml);
                    if (didlXml != null && !didlXml.isEmpty()) {
                        int beforeCount = outPhotos.size();
                        int beforeContainers = subContainers.size();
                        parseDidlLiteDom(device.baseUrl, didlXml, outPhotos, subContainers);
                        int addedItems = (outPhotos.size() - beforeCount) + (subContainers.size() - beforeContainers);

                        int numberReturned = extractIntFromXml(responseXml, "NumberReturned");
                        int totalMatches = extractIntFromXml(responseXml, "TotalMatches");

                        int step = (numberReturned > 0) ? numberReturned : addedItems;
                        if (step <= 0 || (totalMatches > 0 && startingIndex + step >= totalMatches) || ("0".equals(objectId) && step < requestedCount)) {
                            hasMore = false;
                        } else {
                            startingIndex += step;
                        }
                    } else {
                        hasMore = false;
                    }
                } else {
                    hasMore = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error browsing objectId=" + objectId + ": " + e.getMessage());
                hasMore = false;
            }
        }

        // Recursively browse subcontainers
        for (String subId : subContainers) {
            if (!visited.contains(subId)) {
                browseRecursive(device, subId, outPhotos, visited, depth + 1);
            }
        }
    }

    private static String extractResultFromSoap(String soapResponse) {
        if (soapResponse == null || soapResponse.isEmpty()) return null;
        try {
            DocumentBuilderFactory dbf = createSecureDocumentBuilderFactory();
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(soapResponse)));
            Element resElem = findElementByLocalName(doc.getDocumentElement(), "Result");
            if (resElem != null) {
                String text = resElem.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    String clean = text.trim();
                    if (clean.startsWith("<![CDATA[") && clean.endsWith("]]>")) {
                        clean = clean.substring(9, clean.length() - 3).trim();
                    }
                    return clean;
                }
            }
        } catch (Exception ignored) {}

        try {
            Pattern p = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?Result\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?Result>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(soapResponse);
            if (m.find()) {
                String raw = m.group(1).trim();
                if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) {
                    raw = raw.substring(9, raw.length() - 3).trim();
                } else {
                    raw = unescapeXml(raw);
                }
                return raw;
            }
            if (soapResponse.contains("<DIDL-Lite") || soapResponse.contains("<didl-lite")) {
                int start = soapResponse.indexOf("<DIDL-Lite");
                if (start == -1) start = soapResponse.indexOf("<didl-lite");
                int end = soapResponse.indexOf("</DIDL-Lite>");
                if (end == -1) end = soapResponse.indexOf("</didl-lite>");
                if (start != -1 && end != -1) {
                    return soapResponse.substring(start, end + "</DIDL-Lite>".length());
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String unescapeXml(String raw) {
        if (raw == null) return "";
        return raw.replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&quot;", "\"")
                  .replace("&apos;", "'")
                  .replace("&amp;", "&");
    }

    private static int extractIntFromXml(String xml, String tagName) {
        if (xml == null) return -1;
        try {
            Pattern p = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?" + tagName + "\\b[^>]*>(\\d+)</(?:[a-zA-Z0-9_-]+:)?" + tagName + ">", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(xml);
            if (m.find()) {
                return Integer.parseInt(m.group(1).trim());
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static void parseDidlLiteDom(String baseUrl, String didlXml, List<PhotoItem> outPhotos, List<String> outSubContainers) {
        try {
            DocumentBuilderFactory dbf = createSecureDocumentBuilderFactory();
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(didlXml)));

            // 1. Containers
            List<Element> containers = new ArrayList<>();
            findAllElementsByLocalName(doc.getDocumentElement(), "container", containers);
            for (Element c : containers) {
                String cid = c.getAttribute("id");
                if (cid != null && !cid.isEmpty() && outSubContainers != null && !outSubContainers.contains(cid)) {
                    outSubContainers.add(cid);
                    Log.i(TAG, "Found container in DIDL: " + cid);
                }
            }

            // 2. Items (photos / videos)
            List<Element> items = new ArrayList<>();
            findAllElementsByLocalName(doc.getDocumentElement(), "item", items);
            for (Element it : items) {
                PhotoItem photo = new PhotoItem();
                photo.setId(it.getAttribute("id"));

                Element titleElem = findElementByLocalName(it, "title");
                String title = (titleElem != null) ? titleElem.getTextContent().trim() : "";

                Element dateElem = findElementByLocalName(it, "date");
                String date = (dateElem != null) ? dateElem.getTextContent().trim() : "";
                photo.setDate(date);

                Element classElem = findElementByLocalName(it, "class");
                String upnpClass = (classElem != null) ? classElem.getTextContent().trim().toLowerCase() : "";
                if (upnpClass.contains("video")) {
                    photo.setVideo(true);
                }

                List<Element> resList = new ArrayList<>();
                findAllElementsByLocalName(it, "res", resList);
                String bestFullUrl = null;
                long bestSize = 0;
                String bestMime = null;
                String bestThumbUrl = null;

                for (Element resElem : resList) {
                    String resUrl = resElem.getTextContent().trim();
                    if (resUrl.isEmpty()) continue;
                    String fullResUrl = resolveUrl(baseUrl, resUrl);
                    if (!isSafeCameraUrl(baseUrl, fullResUrl)) {
                        Log.w(TAG, "Skipping untrusted resource URL: " + fullResUrl);
                        continue;
                    }

                    String pi = resElem.getAttribute("protocolInfo");
                    if (pi == null) pi = "";
                    String piLower = pi.toLowerCase();

                    long size = 0;
                    String szStr = resElem.getAttribute("size");
                    if (szStr != null && !szStr.isEmpty()) {
                        try { size = Long.parseLong(szStr.trim()); } catch (Exception ignored) {}
                    }

                    String mime = "";
                    if (pi.contains(":")) {
                        String[] parts = pi.split(":");
                        if (parts.length >= 3) mime = parts[2].trim();
                    }

                    boolean isThumb = piLower.contains("jpeg_tn") || piLower.contains("jpeg_sm") ||
                                      piLower.contains("png_tn")  || piLower.contains("png_sm") ||
                                      piLower.contains("_tn")     || piLower.contains("_sm") ||
                                      piLower.contains("thumb");

                    if (isThumb) {
                        if (bestThumbUrl == null) bestThumbUrl = fullResUrl;
                    } else {
                        if (bestFullUrl == null || size > bestSize) {
                            bestFullUrl = fullResUrl;
                            bestSize = size;
                            bestMime = mime;
                        }
                    }
                }

                if (bestFullUrl == null && bestThumbUrl != null) {
                    bestFullUrl = bestThumbUrl;
                }

                if (bestFullUrl != null) {
                    String finalName = ensureExtension(title, bestMime, bestFullUrl);
                    photo.setTitle(finalName);
                    photo.setFullUrl(bestFullUrl);
                    photo.setSizeBytes(bestSize);

                    if (bestThumbUrl != null) {
                        photo.setThumbUrl(bestThumbUrl);
                    } else {
                        photo.setThumbUrl(bestFullUrl);
                    }

                    if (photo.isVideo() || (bestMime != null && bestMime.toLowerCase().contains("video"))) {
                        photo.setVideo(true);
                    }

                    outPhotos.add(photo);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parseDidlLiteDom error: " + e.getMessage());
        }
    }

    public static String sanitizeFileName(String title, boolean isVideo) {
        if (title == null || title.trim().isEmpty()) {
            return "NX_" + System.currentTimeMillis() + (isVideo ? ".mp4" : ".jpg");
        }
        String clean = new File(title).getName();
        clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        clean = clean.replace("..", "_");
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
            clean = "NX_" + System.currentTimeMillis() + (isVideo ? ".mp4" : ".jpg");
        }
        return clean;
    }

    public static String ensureExtension(String title, String mime, String url) {
        if (title == null || title.trim().isEmpty()) {
            title = "SAM_" + System.currentTimeMillis();
        }
        String lastPart = title;
        int slash = lastPart.lastIndexOf('/');
        if (slash >= 0) lastPart = lastPart.substring(slash + 1);

        if (lastPart.contains(".")) {
            return title;
        }

        String ext = null;
        if (mime != null) {
            ext = MIME_EXT.get(mime.toLowerCase().trim());
        }
        if (ext == null && url != null) {
            String cleanUrl = url;
            int q = cleanUrl.indexOf('?');
            if (q >= 0) cleanUrl = cleanUrl.substring(0, q);
            int dot = cleanUrl.lastIndexOf('.');
            if (dot >= 0 && dot > cleanUrl.lastIndexOf('/')) {
                ext = cleanUrl.substring(dot).toLowerCase();
            }
        }
        if (ext == null) {
            ext = ".jpg";
        }
        return title + ext;
    }

    // =========================================================================
    // DOWNLOAD ENGINE (Zero-Collision, Deduplication & MediaStore Integration)
    // =========================================================================

    public static void downloadPhoto(Context context, PhotoItem item, File targetDir, DownloadProgressListener listener) throws Exception {
        boolean wasDownloading = isDownloading.getAndSet(true);
        try {
            // Match SaveAllFileTask in mobilelink-1-7-13.apk: 200ms pause before file download
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            String fileName = sanitizeFileName(item.getTitle(), item.isVideo());

            if (targetDir == null) {
                targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NX1000");
            }

            File destFile = new File(targetDir, fileName);
            File canonicalTarget = targetDir.getCanonicalFile();
            File canonicalDest = destFile.getCanonicalFile();
            if (!canonicalDest.getPath().startsWith(canonicalTarget.getPath())) {
                throw new SecurityException("Potential path traversal detected in file name: " + fileName);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isMediaStoreFileExists(context, fileName)) {
                    Log.i(TAG, "File already exists in MediaStore: " + fileName + " (skipping)");
                    if (listener != null) listener.onProgress(item.getSizeBytes(), item.getSizeBytes(), 100);
                    return;
                }
            } else {
                if (destFile.exists() && destFile.length() > 0) {
                    Log.i(TAG, "File already exists on disk: " + fileName + " (skipping)");
                    if (listener != null) listener.onProgress(destFile.length(), destFile.length(), 100);
                    return;
                }
            }

            OutputStream out = null;
            Uri insertedUri = null;
            File fallbackFile = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, item.isVideo() ? "video/mp4" : "image/jpeg");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "NX1000");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                    Uri collection = item.isVideo() ?
                            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) :
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

                    insertedUri = resolver.insert(collection, values);
                    if (insertedUri != null) {
                        out = resolver.openOutputStream(insertedUri);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "MediaStore insert fallback to direct file write", e);
                }
            }

            if (out == null) {
                if (!targetDir.exists()) targetDir.mkdirs();
                fallbackFile = destFile;
                out = new FileOutputStream(fallbackFile);
            }

            final long itemSize = item.getSizeBytes();
            try {
                HumbleHttpClient.download(activeWifiNetwork, item.getFullUrl(), getUserAgent(), out, new HumbleHttpClient.ProgressCallback() {
                    @Override
                    public void onProgress(long bytesRead, long totalBytes, int percent) {
                        if (listener != null) {
                            long total = (totalBytes > 0) ? totalBytes : itemSize;
                            int pct = (total > 0) ? (int) ((bytesRead * 100) / total) : -1;
                            listener.onProgress(bytesRead, total, pct);
                        }
                    }
                });
            } finally {
                try { out.close(); } catch (Exception ignored) {}
            }

            if (insertedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues completeValues = new ContentValues();
                    completeValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    context.getContentResolver().update(insertedUri, completeValues, null, null);
                } catch (Exception ignored) {}
            } else if (fallbackFile != null) {
                try {
                    MediaScannerConnection.scanFile(
                            context.getApplicationContext(),
                            new String[]{fallbackFile.getAbsolutePath()},
                            new String[]{item.isVideo() ? "video/mp4" : "image/jpeg"},
                            null
                    );
                } catch (Exception ignored) {}
            }
        } finally {
            if (!wasDownloading) {
                isDownloading.set(false);
            }
        }
    }

    private static boolean isMediaStoreFileExists(Context context, String fileName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            String[] selectionArgs = new String[]{fileName};
            Cursor cursor = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID}, selection, selectionArgs, null);
            if (cursor != null) {
                boolean exists = cursor.moveToFirst();
                cursor.close();
                if (exists) return true;
            }
            Uri vidCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            cursor = resolver.query(vidCollection, new String[]{MediaStore.MediaColumns._ID}, selection, selectionArgs, null);
            if (cursor != null) {
                boolean exists = cursor.moveToFirst();
                cursor.close();
                if (exists) return true;
            }
        } catch (Exception ignored) {}
        return false;
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
