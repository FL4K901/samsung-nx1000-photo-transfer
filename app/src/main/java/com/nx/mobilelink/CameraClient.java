package com.nx.mobilelink;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraClient {
    private static final String TAG = "CameraClient";

    public static class ServiceInfo {
        public String serviceType;
        public String serviceId;
        public String scpdUrl;
        public String controlUrl;
        public String eventSubUrl;
        public String sid; // Assigned by camera upon SUBSCRIBE
    }

    public static class DeviceInfo {
        public String friendlyName = "NX Camera";
        public String modelName = "NX1000";
        public String locationUrl;
        public String baseUrl;
        public String controlUrl;
        public List<ServiceInfo> services = new ArrayList<>();

        @Override
        public String toString() {
            return friendlyName + " (" + (baseUrl != null ? baseUrl : locationUrl) + ")";
        }
    }

    public interface DiscoveryCallback {
        void onDeviceFound(DeviceInfo device);
        void onDiscoveryFailed(String error);
    }

    public interface DownloadProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes, int percent);
    }

    // Keepalive and Event Server State
    private static LocalEventServer localEventServer;
    private static ScheduledExecutorService keepAliveExecutor;
    private static DeviceInfo activeDevice;
    private static Context appContext;
    private static Network activeWifiNetwork;
    public static final AtomicBoolean isDownloading = new AtomicBoolean(false);

    public static void setActiveWifiNetwork(Network network) {
        activeWifiNetwork = network;
        Log.i(TAG, "setActiveWifiNetwork: " + network);
    }

    public static String getWifiGatewayIp(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int g = dhcp.gateway;
                    String ip = (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
                    if (!"0.0.0.0".equals(ip)) {
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting gateway IP", e);
        }
        return "192.168.107.1";
    }

    public static String getLocalIpAddress(Context context) {
        // Step 1: Check activeWifiNetwork LinkProperties if available
        if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    LinkProperties lp = cm.getLinkProperties(activeWifiNetwork);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                String host = addr.getHostAddress();
                                if (host != null && !host.startsWith("127.")) {
                                    Log.i(TAG, "Found local IP from activeWifiNetwork LinkProperties: " + host);
                                    return host;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error checking activeWifiNetwork LinkProperties", e);
            }
        }

        // Step 2: Scan NetworkInterfaces, prioritizing 192.168.107.* and wlan
        String candidate192 = null;
        String candidateWlan = null;
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                String ifName = intf.getName().toLowerCase();

                // Skip virtual, loopback, and cellular interfaces
                if (ifName.startsWith("dummy") || ifName.startsWith("rmnet") || ifName.startsWith("ccmni") ||
                    ifName.startsWith("tun") || ifName.startsWith("p2p")) {
                    continue;
                }

                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        if (ip == null || ip.startsWith("127.")) continue;

                        // Exact Samsung camera SoftAP subnet
                        if (ip.startsWith("192.168.107.")) {
                            Log.i(TAG, "Found exact Samsung camera subnet IP: " + ip + " on " + ifName);
                            return ip;
                        }

                        if (ip.startsWith("192.168.")) {
                            candidate192 = ip;
                        } else if (ifName.contains("wlan") || ifName.contains("wifi") || ifName.contains("eth")) {
                            candidateWlan = ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error scanning NetworkInterfaces", e);
        }

        if (candidate192 != null) {
            Log.i(TAG, "Using 192.168.* candidate IP: " + candidate192);
            return candidate192;
        }
        if (candidateWlan != null) {
            Log.i(TAG, "Using wlan candidate IP: " + candidateWlan);
            return candidateWlan;
        }

        // Step 3: Legacy WifiManager fallback
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
                    if (!"0.0.0.0".equals(ip)) {
                        Log.i(TAG, "Using WifiManager IP: " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {}

        Log.w(TAG, "Falling back to default camera client IP 192.168.107.2");
        return "192.168.107.2";
    }

    public static void discoverCamera(final Context context, final String customIp, final int customPort, final DiscoveryCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                WifiManager.MulticastLock multicastLock = null;
                try {
                    WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        multicastLock = wm.createMulticastLock("NX_DISCOVERY");
                        multicastLock.setReferenceCounted(true);
                        multicastLock.acquire();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not acquire MulticastLock", e);
                }

                final DeviceInfo[] foundDevice = new DeviceInfo[1];
                final AtomicBoolean resolved = new AtomicBoolean(false);

                // Candidate ports for NX series cameras (52235=standard NX, 7676=Samsung smart, 4004=Cybergarage UPnP)
                final int[] candidatePorts = (customPort > 0) ? new int[]{customPort} :
                        new int[]{52235, 7676, 4004, 8080, 50000, 80, 8000, 49152, 49153, 49154, 38400, 55555};

                final String gatewayIp = getWifiGatewayIp(context);
                final String localIp = getLocalIpAddress(context);
                final List<String> candidateIps = new ArrayList<>();
                if (customIp != null && !customIp.trim().isEmpty()) {
                    candidateIps.add(customIp.trim());
                }
                if (gatewayIp != null && !candidateIps.contains(gatewayIp)) candidateIps.add(gatewayIp);
                if (!candidateIps.contains("192.168.107.1")) candidateIps.add("192.168.107.1");
                if (!candidateIps.contains("192.168.49.1")) candidateIps.add("192.168.49.1");

                if (localIp != null && localIp.contains(".")) {
                    String subnetBase = localIp.substring(0, localIp.lastIndexOf('.') + 1) + "1";
                    if (!candidateIps.contains(subnetBase)) candidateIps.add(subnetBase);
                }

                final ExecutorService probePool = Executors.newFixedThreadPool(6);
                final long deadline = System.currentTimeMillis() + 20000; // 20-second discovery window to give user time to approve on camera

                // Background SSDP search / listener thread
                Thread ssdpSearchThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (!resolved.get() && System.currentTimeMillis() < deadline) {
                            try {
                                DeviceInfo dev = performSsdpSearch(1500, candidateIps);
                                if (dev != null && resolved.compareAndSet(false, true)) {
                                    foundDevice[0] = dev;
                                    break;
                                }
                            } catch (Exception ignored) {}
                            try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                        }
                    }
                }, "NX-Discovery-SSDP");
                ssdpSearchThread.start();

                // Direct HTTP Probing loop (runs concurrently across candidate ports)
                while (!resolved.get() && System.currentTimeMillis() < deadline) {
                    final CountDownLatch roundLatch = new CountDownLatch(candidateIps.size() * candidatePorts.length);
                    for (final String ip : candidateIps) {
                        if (resolved.get()) break;
                        for (final int p : candidatePorts) {
                            if (resolved.get()) break;
                            probePool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (!resolved.get()) {
                                            DeviceInfo dev = probeHttpEndpoint(ip, p);
                                            if (dev != null && resolved.compareAndSet(false, true)) {
                                                foundDevice[0] = dev;
                                            }
                                        }
                                    } finally {
                                        roundLatch.countDown();
                                    }
                                }
                            });
                        }
                    }

                    try {
                        roundLatch.await(1500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {}

                    if (resolved.get()) break;
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { break; }
                }

                probePool.shutdownNow();
                ssdpSearchThread.interrupt();

                if (multicastLock != null && multicastLock.isHeld()) {
                    try {
                        multicastLock.release();
                    } catch (Exception ignored) {}
                }

                if (foundDevice[0] != null) {
                    callback.onDeviceFound(foundDevice[0]);
                } else {
                    callback.onDiscoveryFailed("לא נמצאה מצלמה. ודא שהטלפון מחובר לרשת ה-Wi-Fi של המצלמה, ובמידה ואתה במצב MobileLink ודא שלחצת 'כן' במצלמה.");
                }
            }
        }).start();
    }

    private static DeviceInfo performSsdpSearch(int timeoutMs, List<String> candidateIps) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    activeWifiNetwork.bindSocket(socket);
                } catch (Exception ignored) {}
            }
            socket.setSoTimeout(timeoutMs);

            String[] searchTargets = new String[]{
                    "ssdp:all",
                    "urn:schemas-upnp-org:device:MediaServer:1",
                    "urn:schemas-upnp-org:service:ContentDirectory:1",
                    "upnp:rootdevice"
            };

            InetAddress multicastGroup = InetAddress.getByName("239.255.255.250");

            for (String st : searchTargets) {
                String searchMsg = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: " + st + "\r\n\r\n";
                byte[] bytes = searchMsg.getBytes(StandardCharsets.UTF_8);

                // Send to multicast
                try {
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, multicastGroup, 1900);
                    socket.send(packet);
                } catch (Exception ignored) {}

                // Also send unicast to candidate IPs
                if (candidateIps != null) {
                    for (String cip : candidateIps) {
                        try {
                            InetAddress directAddr = InetAddress.getByName(cip);
                            DatagramPacket directPacket = new DatagramPacket(bytes, bytes.length, directAddr, 1900);
                            socket.send(directPacket);
                        } catch (Exception ignored) {}
                    }
                }
            }

            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(recv);
                    String resp = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8);
                    Log.d(TAG, "SSDP response: " + resp);
                    String location = extractHeader(resp, "LOCATION");
                    if (location != null && !location.isEmpty()) {
                        DeviceInfo dev = fetchAndParseDeviceDescription(location);
                        if (dev != null) {
                            return dev;
                        }
                    }
                } catch (SocketTimeoutException te) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "performSsdpSearch error", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        return null;
    }

    private static DeviceInfo probeHttpEndpoint(String ip, int port) {
        String[] paths = new String[]{
                "/description.xml",
                "/smp_10_",
                "/dmr/SamsungMRDesc.xml",
                "/upnp/ContentDirectory.xml",
                "/upnp/common/desc.xml",
                "/rootDesc.xml"
        };
        for (String p : paths) {
            String testUrl = "http://" + ip + ":" + port + p;
            DeviceInfo dev = fetchAndParseDeviceDescription(testUrl);
            if (dev != null) {
                return dev;
            }
        }
        return null;
    }

    private static DeviceInfo fetchAndParseDeviceDescription(String locationUrl) {
        try {
            URL url = new URL(locationUrl);
            HttpURLConnection conn = openConnection(url);
            conn.setConnectTimeout(600);
            conn.setReadTimeout(1200);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", getUserAgent());

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = new BufferedInputStream(conn.getInputStream());
                String xml = readStreamToString(in);
                conn.disconnect();

                DeviceInfo dev = parseDeviceDescriptionXml(locationUrl, xml);
                if (dev != null) {
                    // Fetch SCPD XMLs for all services to complete UPnP device registration
                    for (ServiceInfo s : dev.services) {
                        if (s.scpdUrl != null) {
                            fetchServiceScpd(s.scpdUrl);
                        }
                    }
                    return dev;
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
            // endpoint not reachable
        }
        return null;
    }

    private static DeviceInfo parseDeviceDescriptionXml(String locationUrl, String xml) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            DeviceInfo dev = new DeviceInfo();
            dev.locationUrl = locationUrl;

            URL locUrl = new URL(locationUrl);
            int locPort = locUrl.getPort() != -1 ? locUrl.getPort() : (locUrl.getDefaultPort() != -1 ? locUrl.getDefaultPort() : 80);
            dev.baseUrl = locUrl.getProtocol() + "://" + locUrl.getHost() + (locPort == 80 && locUrl.getPort() == -1 ? "" : ":" + locPort);

            boolean inService = false;
            String currentTag = "";
            ServiceInfo currentService = null;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                    if ("service".equalsIgnoreCase(currentTag)) {
                        inService = true;
                        currentService = new ServiceInfo();
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    String text = parser.getText();
                    if (text != null) text = text.trim();
                    if (text != null && !text.isEmpty()) {
                        if (!inService) {
                            if ("friendlyName".equalsIgnoreCase(currentTag) && dev.friendlyName.equals("NX Camera")) {
                                dev.friendlyName = text;
                            } else if ("modelName".equalsIgnoreCase(currentTag)) {
                                dev.modelName = text;
                            }
                        } else if (currentService != null) {
                            if ("serviceType".equalsIgnoreCase(currentTag)) {
                                currentService.serviceType = text;
                            } else if ("serviceId".equalsIgnoreCase(currentTag)) {
                                currentService.serviceId = text;
                            } else if ("SCPDURL".equalsIgnoreCase(currentTag)) {
                                currentService.scpdUrl = text;
                            } else if ("controlURL".equalsIgnoreCase(currentTag)) {
                                currentService.controlUrl = text;
                            } else if ("eventSubURL".equalsIgnoreCase(currentTag)) {
                                currentService.eventSubUrl = text;
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String endTag = parser.getName();
                    if ("service".equalsIgnoreCase(endTag) && currentService != null) {
                        // Resolve URLs
                        if (currentService.scpdUrl != null) {
                            if (!currentService.scpdUrl.startsWith("http://") && !currentService.scpdUrl.startsWith("https://")) {
                                if (!currentService.scpdUrl.startsWith("/")) currentService.scpdUrl = "/" + currentService.scpdUrl;
                                currentService.scpdUrl = dev.baseUrl + currentService.scpdUrl;
                            }
                        }
                        if (currentService.controlUrl != null) {
                            if (!currentService.controlUrl.startsWith("http://") && !currentService.controlUrl.startsWith("https://")) {
                                if (!currentService.controlUrl.startsWith("/")) currentService.controlUrl = "/" + currentService.controlUrl;
                                currentService.controlUrl = dev.baseUrl + currentService.controlUrl;
                            }
                        }
                        if (currentService.eventSubUrl != null) {
                            if (!currentService.eventSubUrl.startsWith("http://") && !currentService.eventSubUrl.startsWith("https://")) {
                                if (!currentService.eventSubUrl.startsWith("/")) currentService.eventSubUrl = "/" + currentService.eventSubUrl;
                                currentService.eventSubUrl = dev.baseUrl + currentService.eventSubUrl;
                            }
                        }

                        if (currentService.serviceType != null) {
                            String st = currentService.serviceType.toLowerCase();
                            if (st.contains("contentdirectory") || st.contains("cds") || st.contains("mediaserver")) {
                                dev.controlUrl = currentService.controlUrl;
                            }
                        }
                        dev.services.add(currentService);

                        inService = false;
                        currentService = null;
                    }
                }
                eventType = parser.next();
            }

            // Ensure controlUrl is resolved
            if (dev.controlUrl == null && !dev.services.isEmpty()) {
                for (ServiceInfo s : dev.services) {
                    if (s.controlUrl != null) {
                        dev.controlUrl = s.controlUrl;
                        break;
                    }
                }
            }
            if (dev.controlUrl == null && dev.baseUrl != null) {
                dev.controlUrl = dev.baseUrl + "/upnp/control/ContentDirectory";
            }

            // If no services were found in XML, provide default NX services
            if (dev.services.isEmpty() && dev.baseUrl != null) {
                ServiceInfo cd = new ServiceInfo();
                cd.serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
                cd.serviceId = "urn:upnp-org:serviceId:ContentDirectory";
                cd.controlUrl = dev.baseUrl + "/upnp/control/ContentDirectory";
                cd.eventSubUrl = dev.baseUrl + "/upnp/event/ContentDirectory";
                cd.scpdUrl = dev.baseUrl + "/upnp/ContentDirectory.xml";
                dev.services.add(cd);

                ServiceInfo cm = new ServiceInfo();
                cm.serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
                cm.serviceId = "urn:upnp-org:serviceId:ConnectionManager";
                cm.controlUrl = dev.baseUrl + "/upnp/control/ConnectionManager";
                cm.eventSubUrl = dev.baseUrl + "/upnp/event/ConnectionManager";
                cm.scpdUrl = dev.baseUrl + "/upnp/ConnectionManager.xml";
                dev.services.add(cm);
                dev.controlUrl = cd.controlUrl;
            }

            // Ensure eventSubUrl is set on all services
            for (ServiceInfo s : dev.services) {
                if (s.eventSubUrl == null && dev.baseUrl != null) {
                    if (s.serviceType != null && s.serviceType.toLowerCase().contains("content")) {
                        s.eventSubUrl = dev.baseUrl + "/upnp/event/ContentDirectory";
                    } else {
                        s.eventSubUrl = dev.baseUrl + "/upnp/event/ConnectionManager";
                    }
                }
            }

            Log.i(TAG, "Parsed Device: " + dev.friendlyName + " with " + dev.services.size() + " services, controlUrl=" + dev.controlUrl);
            return dev;
        } catch (Exception e) {
            Log.w(TAG, "Error parsing device XML", e);
        }
        return null;
    }

    public static void fetchServiceScpd(String scpdUrlStr) {
        if (scpdUrlStr == null) return;
        try {
            URL url = new URL(scpdUrlStr);
            HttpURLConnection conn = openConnection(url);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", getUserAgent());
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) {}
                in.close();
            }
            conn.disconnect();
            Log.i(TAG, "Fetched SCPD XML from " + scpdUrlStr + " (status " + code + ")");
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch SCPD: " + e.getMessage());
        }
    }

    public static HttpURLConnection openConnection(URL url) throws Exception {
        if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return (HttpURLConnection) activeWifiNetwork.openConnection(url);
        }
        return (HttpURLConnection) url.openConnection();
    }

    // =========================================================================
    // MULTI-LAYER KEEP-ALIVE SYSTEM (Prevents Camera Wi-Fi Timeout / Auto-Shutoff)
    // =========================================================================

    // -------------------------------------------------------------------------
    // Smartphone identity, exactly as the original MobileLink app builds it
    // (Utils.setUseragent): "SEC_DSC_" + phone number / Wi-Fi MAC (no colons).
    // The camera uses this User-Agent on the device-description request to
    // recognise that a *smartphone* (not a generic DLNA client) has connected.
    // -------------------------------------------------------------------------
    private static String userAgent;

    public static synchronized String getUserAgent() {
        if (userAgent != null && !userAgent.contains("UNKNOWN") && userAgent.length() == 20) {
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
        Log.i(TAG, "Smartphone User-Agent: " + userAgent);
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
        // Realistic Samsung smartphone MAC format (OUI 48:5D:60)
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder("485D60");
        for (int i = 0; i < 3; i++) {
            sb.append(String.format("%02X", r.nextInt(256)));
        }
        return sb.toString();
    }

    public static synchronized String getRvfUserAgent() {
        String base = getUserAgent(); // e.g. "SEC_DSC_485D60112233"
        String macPart = base.replace("SEC_DSC_", "");
        return "SEC_RVF_" + macPart;
    }

    private static String formatMacWithColons(String mac) {
        if (mac == null || mac.length() != 12) return "48:5D:60:11:22:33";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(mac.substring(i, i + 2));
        }
        return sb.toString();
    }

    /** Sends Remote Viewfinder handshake to /smp_2, /smp_3, /smp_4 so camera physical screen acknowledges connection */
    private static void sendRvfHandshake(DeviceInfo device) {
        if (device == null || device.baseUrl == null) return;
        try {
            URL u = new URL(device.baseUrl);
            String host = u.getHost();
            int cameraPort = u.getPort() != -1 ? u.getPort() : 80;

            String rvfUa1 = getRvfUserAgent();
            String rawMac = rvfUa1.replace("SEC_RVF_", "");
            String rvfUa2 = "SEC_RVF_" + formatMacWithColons(rawMac);
            String rvfUa3 = "SEC_RVF_" + rawMac + "_";

            String[] uas = new String[]{rvfUa1, rvfUa2, rvfUa3};
            String[] rvfPaths = new String[]{"/smp_2", "/smp_3", "/smp_4", "/smp_2_", "/smp_3_", "/smp_4_"};
            int[] testPorts = (cameraPort != 80 && cameraPort != 7679) ?
                    new int[]{cameraPort, 7679, 80} : new int[]{cameraPort, 80};

            for (int p : testPorts) {
                for (String path : rvfPaths) {
                    for (String ua : uas) {
                        try {
                            URL reqUrl = new URL("http://" + host + ":" + p + path);
                            HttpURLConnection conn = openConnection(reqUrl);
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(600);
                            conn.setReadTimeout(1000);
                            conn.setRequestProperty("User-Agent", ua);
                            conn.setRequestProperty("Connection", "close");
                            int code = conn.getResponseCode();
                            conn.disconnect();
                            Log.d(TAG, "RVF Handshake (" + ua + ") -> " + reqUrl + " code: " + code);
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Also send RTSP ping on port 554
            sendRtspPing(host, 554, rvfUa1);

            // Also send TCP ping to ports 50000, 50001, 50002
            sendTcpPings(host, new int[]{50000, 50001, 50002});
        } catch (Exception e) {
            Log.w(TAG, "sendRvfHandshake error: " + e.getMessage());
        }
    }

    private static Socket createSocket() {
        Socket socket = new Socket();
        if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                activeWifiNetwork.bindSocket(socket);
            } catch (Exception ignored) {}
        }
        return socket;
    }

    private static void sendRtspPing(String host, int port, String ua) {
        Socket s = null;
        try {
            s = createSocket();
            s.connect(new InetSocketAddress(host, port), 600);
            s.setSoTimeout(1000);
            OutputStream os = s.getOutputStream();
            String msg = "OPTIONS rtsp://" + host + ":" + port + "/profile1/media.smp RTSP/1.0\r\n" +
                         "CSeq: 1\r\n" +
                         "User-Agent: " + ua + "\r\n\r\n";
            os.write(msg.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignored) {
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void sendTcpPings(String host, int[] ports) {
        for (int p : ports) {
            Socket s = null;
            try {
                s = createSocket();
                s.connect(new InetSocketAddress(host, p), 400);
                s.setSoTimeout(500);
                OutputStream os = s.getOutputStream();
                os.write(new byte[]{0x00, 0x01, 0x00, 0x00});
                os.flush();
            } catch (Exception ignored) {
            } finally {
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        getUserAgent();
    }

    private static volatile DatagramSocket ssdpSocket;
    private static volatile Thread ssdpThread;

    public static synchronized void startKeepAlive(Context context, DeviceInfo device) {
        stopKeepAlive();
        appContext = context.getApplicationContext();
        activeDevice = device;
        final DeviceInfo dev = device;

        Log.i(TAG, "Starting MobileLink session. Camera: " + device.baseUrl + " UA: " + getUserAgent());

        // 0. Start LocalEventServer for UPnP NOTIFY callbacks
        localEventServer = new LocalEventServer();
        final int localPort = localEventServer.start();
        final String localIp = getLocalIpAddress(context);
        Log.i(TAG, "LocalEventServer active on " + localIp + ":" + localPort);

        // 1. Handshake & initial UPnP Event Subscriptions:
        //    Request device description with SEC_DSC_* User-Agent, RVF handshake, UDP pings, and UPnP subscription
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                sendDescriptionHandshake(dev);
                sendRvfHandshake(dev);
                sendUdpPings(dev);
                subscribeAllServices(dev, localIp, localPort);
            }
        });

        // 2. Heartbeat: the original app sends "M-SEARCH ssdp:all" every second for as long
        //    as it is open (MyControlPoint$1.run). The camera treats these as "phone alive".
        //    It keeps running during downloads too (it is UDP and does not disturb HTTP).
        ssdpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ssdpHeartbeatLoop(dev);
            }
        }, "NX-SSDP-Heartbeat");
        ssdpThread.setDaemon(true);
        ssdpThread.start();

        // 3. Periodic refresh of UPnP subscriptions, RVF/MobileLink handshakes and browse ping every 10 seconds
        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor();
        keepAliveExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    if (activeDevice != null) {
                        String currentIp = getLocalIpAddress(appContext);
                        String ipToUse = (currentIp != null && !currentIp.isEmpty()) ? currentIp : localIp;
                        subscribeAllServices(activeDevice, ipToUse, localPort);
                        sendRvfHandshake(activeDevice);
                        sendUdpPings(activeDevice);

                        if (!isDownloading.get()) {
                            sendDescriptionHandshake(activeDevice);
                            sendBrowseHeartbeatPing(activeDevice);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Keepalive tick warning: " + e.getMessage());
                }
            }
        }, 1500, 3000, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stopKeepAlive() {
        if (keepAliveExecutor != null) {
            try {
                keepAliveExecutor.shutdownNow();
            } catch (Exception ignored) {}
            keepAliveExecutor = null;
        }
        Thread t = ssdpThread;
        ssdpThread = null;
        if (t != null) t.interrupt();
        DatagramSocket s = ssdpSocket;
        ssdpSocket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        if (activeDevice != null) {
            final DeviceInfo devToUnsub = activeDevice;
            Executors.newSingleThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    unsubscribeAllServices(devToUnsub);
                }
            });
        }
        activeDevice = null;

        if (localEventServer != null) {
            localEventServer.stop();
            localEventServer = null;
        }
    }

    /** GET description.xml over a raw socket with the same headers CyberGarage sends, and fetch service SCPD XMLs. */
    private static void sendDescriptionHandshake(DeviceInfo device) {
        if (device == null || device.locationUrl == null) return;
        try {
            URL url = new URL(device.locationUrl);
            String host = url.getHost();
            int port = url.getPort() != -1 ? url.getPort() : 80;
            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";
            String req = "GET " + path + " HTTP/1.0\r\n" +
                    "User-Agent: " + getUserAgent() + "\r\n" +
                    "HOST: " + host + ":" + port + "\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n";
            String resp = sendRawHttpRequest(host, port, req);
            Log.i(TAG, "Description handshake -> " + (resp != null ? resp.split("\r\n")[0] : "no response"));

            // Fetch SCPD XML for each service just like CyberGarage Service.getSCPDNode()
            if (device.services != null) {
                for (ServiceInfo s : device.services) {
                    if (s.scpdUrl != null) {
                        fetchServiceScpd(s.scpdUrl);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Description handshake failed: " + e.getMessage());
        }
    }

    private static void ssdpHeartbeatLoop(DeviceInfo device) {
        String msg = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: ssdp:all\r\n" +
                "\r\n";
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        InetAddress cameraHost = null;
        try {
            cameraHost = InetAddress.getByName(new URL(device.baseUrl).getHost());
        } catch (Exception ignored) {}

        byte[] rx = new byte[2048];
        while (Thread.currentThread() == ssdpThread) {
            try {
                DatagramSocket s = ssdpSocket;
                if (s == null || s.isClosed()) {
                    s = new DatagramSocket();
                    if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try { activeWifiNetwork.bindSocket(s); } catch (Exception ignored) {}
                    }
                    s.setSoTimeout(300);
                    ssdpSocket = s;
                }
                s.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("239.255.255.250"), 1900));
                if (cameraHost != null) {
                    s.send(new DatagramPacket(bytes, bytes.length, cameraHost, 1900));
                }
                // Drain replies for ~1 second (the camera answers each search)
                long until = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < until && Thread.currentThread() == ssdpThread) {
                    try {
                        s.receive(new DatagramPacket(rx, rx.length));
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread() != ssdpThread) break;
                Log.d(TAG, "SSDP heartbeat error: " + e.getMessage());
                DatagramSocket s = ssdpSocket;
                ssdpSocket = null;
                if (s != null) try { s.close(); } catch (Exception ignored) {}
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    // Ping 1: HTTP GET request to camera description endpoint
    private static void sendHttpWebserverPing(DeviceInfo device) {
        if (device == null || device.locationUrl == null) return;
        try {
            URL url = new URL(device.locationUrl);
            HttpURLConnection conn = openConnection(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("User-Agent", getUserAgent());
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "sendHttpWebserverPing: " + code);
        } catch (Exception e) {
            Log.d(TAG, "sendHttpWebserverPing error: " + e.getMessage());
        }
    }

    // Ping 2: UPnP BrowseMetadata SOAP Ping
    private static void sendBrowseHeartbeatPing(DeviceInfo device) {
        if (device == null || device.controlUrl == null) return;
        try {
            String soapRequest =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                    "  <s:Body>\r\n" +
                    "    <u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\r\n" +
                    "      <ObjectID>0</ObjectID>\r\n" +
                    "      <BrowseFlag>BrowseMetadata</BrowseFlag>\r\n" +
                    "      <Filter>@id</Filter>\r\n" +
                    "      <StartingIndex>0</StartingIndex>\r\n" +
                    "      <RequestedCount>1</RequestedCount>\r\n" +
                    "      <SortCriteria></SortCriteria>\r\n" +
                    "    </u:Browse>\r\n" +
                    "  </s:Body>\r\n" +
                    "</s:Envelope>";

            URL url = new URL(device.controlUrl);
            HttpURLConnection conn = openConnection(url);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"");
            conn.setRequestProperty("User-Agent", getUserAgent());
            conn.setRequestProperty("Connection", "close");

            byte[] b = soapRequest.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(b.length));
            OutputStream os = conn.getOutputStream();
            os.write(b);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "sendBrowseHeartbeatPing result code: " + code);
        } catch (Exception e) {
            Log.d(TAG, "sendBrowseHeartbeatPing exception: " + e.getMessage());
        }
    }

    // Ping 3: UDP Multicast & Unicast Pings
    private static void sendUdpPings(DeviceInfo device) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    activeWifiNetwork.bindSocket(socket);
                } catch (Exception ignored) {}
            }

            // SSDP alive probe
            String searchMsg = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n";
            byte[] bytes = searchMsg.getBytes(StandardCharsets.UTF_8);

            // Multicast
            DatagramPacket mcPacket = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("239.255.255.250"), 1900);
            socket.send(mcPacket);

            // Direct UDP packet to camera gateway (port 1900 & port 50000)
            if (device != null && device.baseUrl != null) {
                try {
                    URL u = new URL(device.baseUrl);
                    InetAddress cameraHost = InetAddress.getByName(u.getHost());
                    DatagramPacket directSsdp = new DatagramPacket(bytes, bytes.length, cameraHost, 1900);
                    socket.send(directSsdp);

                    // Camera command heartbeat on port 50000
                    byte[] cmdPing = new byte[]{(byte) 0x53, (byte) 0x45, (byte) 0x54, (byte) 0x43, (byte) 0x4d, (byte) 0x44, 0, 0, 0, 0};
                    DatagramPacket cmdPacket = new DatagramPacket(cmdPing, cmdPing.length, cameraHost, 50000);
                    socket.send(cmdPacket);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void subscribeAllServices(DeviceInfo device, String localIp, int localPort) {
        if (device == null || device.services == null) return;
        for (ServiceInfo s : device.services) {
            if (s.eventSubUrl != null) {
                subscribeService(s, localIp, localPort);
            }
        }
    }

    private static void unsubscribeAllServices(DeviceInfo device) {
        if (device == null || device.services == null) return;
        for (ServiceInfo s : device.services) {
            if (s.eventSubUrl != null && s.sid != null) {
                unsubscribeService(s);
            }
        }
    }

    private static void subscribeService(ServiceInfo service, String localIp, int localPort) {
        if (service == null || service.eventSubUrl == null) return;
        try {
            URL url = new URL(service.eventSubUrl);
            String host = url.getHost();
            int port = (url.getPort() != -1) ? url.getPort() : 80;
            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";

            String req;
            if (service.sid == null) {
                req = "SUBSCRIBE " + path + " HTTP/1.1\r\n" +
                      "HOST: " + host + ":" + port + "\r\n" +
                      "USER-AGENT: " + getUserAgent() + "\r\n" +
                      "CALLBACK: <http://" + localIp + ":" + localPort + "/>\r\n" +
                      "NT: upnp:event\r\n" +
                      "TIMEOUT: Second-1800\r\n\r\n";
            } else {
                req = "SUBSCRIBE " + path + " HTTP/1.1\r\n" +
                      "HOST: " + host + ":" + port + "\r\n" +
                      "USER-AGENT: " + getUserAgent() + "\r\n" +
                      "SID: " + service.sid + "\r\n" +
                      "TIMEOUT: Second-1800\r\n\r\n";
            }

            String resp = sendRawHttpRequest(host, port, req);
            if (resp != null) {
                if (resp.contains("412") || resp.contains("404") || resp.contains("400")) {
                    Log.w(TAG, "Subscription for " + service.serviceType + " returned error (" + resp.split("\r\n")[0] + "), resetting SID for fresh subscribe");
                    service.sid = null;
                    // Resubscribe fresh immediately with CALLBACK
                    String freshReq = "SUBSCRIBE " + path + " HTTP/1.1\r\n" +
                          "HOST: " + host + ":" + port + "\r\n" +
                          "USER-AGENT: " + getUserAgent() + "\r\n" +
                          "CALLBACK: <http://" + localIp + ":" + localPort + "/>\r\n" +
                          "NT: upnp:event\r\n" +
                          "TIMEOUT: Second-1800\r\n\r\n";
                    String freshResp = sendRawHttpRequest(host, port, freshReq);
                    if (freshResp != null) {
                        String freshSid = extractHeader(freshResp, "SID");
                        if (freshSid != null && !freshSid.isEmpty()) {
                            service.sid = freshSid;
                            Log.i(TAG, "Freshly resubscribed " + service.serviceType + ". SID=" + freshSid);
                        }
                    }
                } else {
                    String sid = extractHeader(resp, "SID");
                    if (sid != null && !sid.isEmpty()) {
                        service.sid = sid;
                        Log.i(TAG, "Subscribed " + service.serviceType + " successfully. SID=" + sid);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error subscribing to " + service.serviceType + ": " + e.getMessage());
        }
    }

    private static void unsubscribeService(ServiceInfo service) {
        if (service == null || service.eventSubUrl == null || service.sid == null) return;
        try {
            URL url = new URL(service.eventSubUrl);
            String host = url.getHost();
            int port = (url.getPort() != -1) ? url.getPort() : 80;
            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";

            String req = "UNSUBSCRIBE " + path + " HTTP/1.1\r\n" +
                         "HOST: " + host + ":" + port + "\r\n" +
                         "SID: " + service.sid + "\r\n\r\n";
            sendRawHttpRequest(host, port, req);
            service.sid = null;
        } catch (Exception ignored) {}
    }

    private static String sendRawHttpRequest(String host, int port, String requestData) {
        Socket socket = null;
        try {
            socket = new Socket();
            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    activeWifiNetwork.bindSocket(socket);
                } catch (Exception ignored) {}
            }
            socket.setSoTimeout(4000);
            socket.connect(new InetSocketAddress(host, port), 3000);

            OutputStream out = socket.getOutputStream();
            out.write(requestData.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                baos.write(buf, 0, len);
                String current = baos.toString("UTF-8");
                if (current.contains("\r\n\r\n") || current.contains("\n\n")) {
                    break;
                }
            }
            return baos.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "sendRawHttpRequest error: " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // Lightweight Local Event Server for UPnP NOTIFY callbacks
    private static class LocalEventServer {
        private ServerSocket serverSocket;
        private Thread serverThread;
        private volatile boolean running = false;
        private int port = 0;

        public synchronized int start() {
            if (running && port > 0) return port;
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(0));
                port = serverSocket.getLocalPort();
                running = true;
                serverThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (running && serverSocket != null && !serverSocket.isClosed()) {
                            try {
                                Socket client = serverSocket.accept();
                                handleClient(client);
                            } catch (Exception e) {
                                if (!running) break;
                            }
                        }
                    }
                }, "LocalEventServer");
                serverThread.setDaemon(true);
                serverThread.start();
                Log.i(TAG, "LocalEventServer listening on port " + port);
                return port;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start LocalEventServer", e);
                return 8058;
            }
        }

        public synchronized int getPort() {
            return (port > 0) ? port : 8058;
        }

        private void handleClient(final Socket client) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.setSoTimeout(3000);
                        InputStream in = client.getInputStream();
                        byte[] buf = new byte[2048];
                        int len = in.read(buf);
                        if (len > 0) {
                            String req = new String(buf, 0, len, StandardCharsets.UTF_8);
                            Log.d(TAG, "Camera NOTIFY received by LocalEventServer:\n" + req);
                        }
                        OutputStream out = client.getOutputStream();
                        String resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                        out.write(resp.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Log.i(TAG, "Acknowledged camera NOTIFY with 200 OK");
                    } catch (Exception ignored) {
                    } finally {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                }
            }).start();
        }

        public synchronized void stop() {
            running = false;
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Exception ignored) {}
                serverSocket = null;
            }
            if (serverThread != null) {
                serverThread.interrupt();
                serverThread = null;
            }
        }
    }

    // =========================================================================
    // UPnP BROWSE & DOWNLOAD
    // =========================================================================

    public static List<PhotoItem> browsePhotos(DeviceInfo device) throws Exception {
        List<PhotoItem> allPhotos = new ArrayList<>();
        if (device == null || device.controlUrl == null) {
            throw new IllegalArgumentException("Invalid device or control URL");
        }

        Queue<String> containerQueue = new ArrayDeque<>();
        Set<String> visitedContainers = new HashSet<>();

        // Start BFS at root container "0"
        containerQueue.add("0");
        visitedContainers.add("0");

        Log.i(TAG, "Starting photo browse for device: " + device.baseUrl);

        while (!containerQueue.isEmpty()) {
            String containerId = containerQueue.poll();
            List<String> subContainers = new ArrayList<>();
            browseContainer(device, containerId, allPhotos, subContainers);
            for (String sub : subContainers) {
                if (sub != null && !sub.isEmpty() && visitedContainers.add(sub)) {
                    Log.i(TAG, "Queued discovered subcontainer: " + sub);
                    containerQueue.add(sub);
                }
            }
        }

        // Fallback: If container "0" yielded 0 photos and no subcontainers, try common alternative container IDs
        if (allPhotos.isEmpty() && visitedContainers.size() <= 1) {
            String[] fallbackContainers = new String[]{"1", "DCIM", "ALL", "100", "0/0", "0/1"};
            for (String fbId : fallbackContainers) {
                if (visitedContainers.add(fbId)) {
                    List<String> sub = new ArrayList<>();
                    browseContainer(device, fbId, allPhotos, sub);
                    for (String s : sub) {
                        if (visitedContainers.add(s)) {
                            browseContainer(device, s, allPhotos, null);
                        }
                    }
                    if (!allPhotos.isEmpty()) {
                        Log.i(TAG, "Photos successfully found using fallback container ID: " + fbId);
                        break;
                    }
                }
            }
        }

        Log.i(TAG, "Total photos found across all containers: " + allPhotos.size());
        return allPhotos;
    }

    private static void browseContainer(DeviceInfo device, String objectId, List<PhotoItem> outPhotos, List<String> outSubContainers) {
        int startingIndex = 0;
        int requestedCount = 25; // 25 items per chunk (Samsung official app uses 20 to prevent camera buffer overflows)
        boolean hasMore = true;
        int maxLoops = 200; // safety ceiling (up to 5000 items)

        String serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
        if (device.services != null) {
            for (ServiceInfo s : device.services) {
                if (s.serviceType != null && s.serviceType.toLowerCase().contains("contentdirectory")) {
                    serviceType = s.serviceType;
                    break;
                }
            }
        }

        Log.i(TAG, "browseContainer: starting browse for objectId=" + objectId + " controlUrl=" + device.controlUrl + " serviceType=" + serviceType);

        while (hasMore && maxLoops-- > 0) {
            try {
                String soapRequest =
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                        "  <s:Body>\r\n" +
                        "    <u:Browse xmlns:u=\"" + serviceType + "\">\r\n" +
                        "      <ObjectID>" + objectId + "</ObjectID>\r\n" +
                        "      <BrowseFlag>BrowseDirectChildren</BrowseFlag>\r\n" +
                        "      <Filter>*</Filter>\r\n" +
                        "      <StartingIndex>" + startingIndex + "</StartingIndex>\r\n" +
                        "      <RequestedCount>" + requestedCount + "</RequestedCount>\r\n" +
                        "      <SortCriteria></SortCriteria>\r\n" +
                        "    </u:Browse>\r\n" +
                        "  </s:Body>\r\n" +
                        "</s:Envelope>";

                URL url = new URL(device.controlUrl);
                HttpURLConnection conn = openConnection(url);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                conn.setRequestProperty("SOAPACTION", "\"" + serviceType + "#Browse\"");
                conn.setRequestProperty("User-Agent", getUserAgent());

                byte[] bodyBytes = soapRequest.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

                OutputStream out = conn.getOutputStream();
                out.write(bodyBytes);
                out.flush();
                out.close();

                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream in = new BufferedInputStream(conn.getInputStream());
                    String responseXml = readStreamToString(in);
                    conn.disconnect();

                    int numberReturned = extractIntFromXml(responseXml, "NumberReturned");
                    int totalMatches = extractIntFromXml(responseXml, "TotalMatches");
                    Log.d(TAG, "browseContainer: objectId=" + objectId + " idx=" + startingIndex + " returned=" + numberReturned + " total=" + totalMatches);

                    String didlXml = extractResultFromSoap(responseXml);
                    if (didlXml != null && !didlXml.isEmpty()) {
                        int beforeItemsCount = outPhotos.size();
                        int beforeSubsCount = (outSubContainers != null) ? outSubContainers.size() : 0;
                        parseDidlLite(device.baseUrl, didlXml, outPhotos, outSubContainers);
                        int addedItems = outPhotos.size() - beforeItemsCount;
                        int addedSubs = (outSubContainers != null) ? (outSubContainers.size() - beforeSubsCount) : 0;
                        int totalAdded = addedItems + addedSubs;

                        int progressStep = numberReturned > 0 ? numberReturned : totalAdded;
                        if (progressStep <= 0) {
                            hasMore = false;
                        } else {
                            startingIndex += progressStep;
                            if (totalMatches > 0 && startingIndex >= totalMatches) {
                                hasMore = false;
                            } else if (numberReturned > 0 && numberReturned < requestedCount) {
                                hasMore = false;
                            } else if (numberReturned <= 0 && totalAdded < requestedCount) {
                                hasMore = false;
                            }
                        }
                    } else {
                        Log.w(TAG, "browseContainer: DIDL XML extraction empty for objectId=" + objectId);
                        hasMore = false;
                    }
                } else {
                    InputStream err = conn.getErrorStream();
                    String errStr = err != null ? readStreamToString(err) : "";
                    Log.e(TAG, "browseContainer failed for objectId=" + objectId + " HTTP " + code + ": " + errStr);
                    conn.disconnect();
                    hasMore = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error browsing container " + objectId + " at index " + startingIndex, e);
                hasMore = false;
            }
        }
    }

    private static String extractResultFromSoap(String soapResponse) {
        if (soapResponse == null || soapResponse.isEmpty()) return null;
        try {
            // Pattern 1: <Result...>...</Result> or <u:Result...>...</u:Result>
            Pattern p = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?Result\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?Result>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(soapResponse);
            if (m.find()) {
                return unescapeXml(m.group(1));
            }

            // Pattern 2: Escaped &lt;Result&gt;...&lt;/Result&gt;
            Pattern pEsc = Pattern.compile("&lt;(?:[a-zA-Z0-9_-]+:)?Result\\b[^&]*&gt;([\\s\\S]*?)&lt;/(?:[a-zA-Z0-9_-]+:)?Result&gt;", Pattern.CASE_INSENSITIVE);
            Matcher mEsc = pEsc.matcher(soapResponse);
            if (mEsc.find()) {
                return unescapeXml(mEsc.group(1));
            }

            // Pattern 3: Directly contains <DIDL-Lite...>
            if (soapResponse.contains("<DIDL-Lite") || soapResponse.contains("<didl-lite")) {
                int start = soapResponse.indexOf("<DIDL-Lite");
                if (start == -1) start = soapResponse.indexOf("<didl-lite");
                int end = soapResponse.indexOf("</DIDL-Lite>");
                if (end == -1) end = soapResponse.indexOf("</didl-lite>");
                if (start != -1 && end != -1) {
                    return soapResponse.substring(start, end + "</DIDL-Lite>".length());
                }
            }

            // Pattern 4: Escaped &lt;DIDL-Lite...&gt;
            if (soapResponse.contains("&lt;DIDL-Lite") || soapResponse.contains("&lt;didl-lite")) {
                int start = soapResponse.indexOf("&lt;DIDL-Lite");
                if (start == -1) start = soapResponse.indexOf("&lt;didl-lite");
                int end = soapResponse.indexOf("&lt;/DIDL-Lite&gt;");
                if (end == -1) end = soapResponse.indexOf("&lt;/didl-lite&gt;");
                if (start != -1 && end != -1) {
                    String sub = soapResponse.substring(start, end + "&lt;/DIDL-Lite&gt;".length());
                    return unescapeXml(sub);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error extracting Result from SOAP: " + e.getMessage());
        }
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

    private static void parseDidlLite(String baseUrl, String didlXml, List<PhotoItem> outPhotos, List<String> outSubContainers) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(didlXml));

            int eventType = parser.getEventType();
            PhotoItem currentItem = null;
            String currentTag = "";
            String tempResUrl = null;
            String tempProtocolInfo = null;
            long tempResSize = 0;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                    if ("container".equalsIgnoreCase(currentTag)) {
                        String cId = parser.getAttributeValue(null, "id");
                        if (cId == null) {
                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                if ("id".equalsIgnoreCase(parser.getAttributeName(i))) {
                                    cId = parser.getAttributeValue(i);
                                    break;
                                }
                            }
                        }
                        if (cId != null && outSubContainers != null) {
                            outSubContainers.add(cId);
                        }
                    } else if ("item".equalsIgnoreCase(currentTag)) {
                        currentItem = new PhotoItem();
                        String itemId = parser.getAttributeValue(null, "id");
                        if (itemId == null) {
                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                if ("id".equalsIgnoreCase(parser.getAttributeName(i))) {
                                    itemId = parser.getAttributeValue(i);
                                    break;
                                }
                            }
                        }
                        currentItem.setId(itemId);
                    } else if ("res".equalsIgnoreCase(currentTag) && currentItem != null) {
                        tempProtocolInfo = parser.getAttributeValue(null, "protocolInfo");
                        String sizeStr = parser.getAttributeValue(null, "size");
                        if (sizeStr == null) {
                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                if ("size".equalsIgnoreCase(parser.getAttributeName(i))) {
                                    sizeStr = parser.getAttributeValue(i);
                                    break;
                                }
                            }
                        }
                        tempResSize = 0;
                        if (sizeStr != null) {
                            try {
                                tempResSize = Long.parseLong(sizeStr);
                            } catch (Exception ignored) {}
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    String text = parser.getText();
                    if (text != null) text = text.trim();
                    if (text != null && !text.isEmpty() && currentItem != null) {
                        if ("title".equalsIgnoreCase(currentTag) || "dc:title".equalsIgnoreCase(currentTag)) {
                            currentItem.setTitle(text);
                        } else if ("date".equalsIgnoreCase(currentTag) || "dc:date".equalsIgnoreCase(currentTag)) {
                            currentItem.setDate(text);
                        } else if ("res".equalsIgnoreCase(currentTag)) {
                            tempResUrl = text;
                            if (!tempResUrl.startsWith("http://") && !tempResUrl.startsWith("https://")) {
                                if (!tempResUrl.startsWith("/")) tempResUrl = "/" + tempResUrl;
                                tempResUrl = baseUrl + tempResUrl;
                            }

                            boolean isThumb = false;
                            if (tempProtocolInfo != null && (tempProtocolInfo.contains("JPEG_TN") || tempProtocolInfo.contains("JPEG_SM") || tempProtocolInfo.contains("thumb"))) {
                                isThumb = true;
                            } else if (tempResSize > 0 && tempResSize < 120 * 1024 && currentItem.getFullUrl() != null) {
                                isThumb = true;
                            }

                            if (isThumb) {
                                currentItem.setThumbUrl(tempResUrl);
                            } else {
                                currentItem.setFullUrl(tempResUrl);
                                if (tempResSize > 0) currentItem.setSizeBytes(tempResSize);
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String endTag = parser.getName();
                    if ("item".equalsIgnoreCase(endTag) && currentItem != null) {
                        // Title fallback if missing: derive from URL or ID
                        if (currentItem.getTitle() == null || currentItem.getTitle().trim().isEmpty()) {
                            if (currentItem.getFullUrl() != null) {
                                String u = currentItem.getFullUrl();
                                int slash = u.lastIndexOf('/');
                                if (slash != -1 && slash + 1 < u.length()) {
                                    currentItem.setTitle(u.substring(slash + 1));
                                }
                            }
                            if (currentItem.getTitle() == null || currentItem.getTitle().trim().isEmpty()) {
                                String safeId = currentItem.getId() != null ? currentItem.getId().replace('/', '_') : String.valueOf(System.currentTimeMillis());
                                currentItem.setTitle("SAM_" + safeId + ".JPG");
                            }
                        }

                        String lower = currentItem.getTitle().toLowerCase();
                        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi")) {
                            currentItem.setVideo(true);
                        }
                        if (currentItem.getThumbUrl() == null && currentItem.getFullUrl() != null) {
                            currentItem.setThumbUrl(currentItem.getFullUrl());
                        }
                        if (currentItem.getFullUrl() == null && currentItem.getThumbUrl() != null) {
                            currentItem.setFullUrl(currentItem.getThumbUrl());
                        }
                        if (currentItem.getFullUrl() != null) {
                            outPhotos.add(currentItem);
                        }
                        currentItem = null;
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing DIDL-Lite XML", e);
        }
    }

    public static void downloadPhoto(Context context, PhotoItem item, File targetDir, DownloadProgressListener listener) throws Exception {
        isDownloading.set(true);
        try {
            String fileName = item.getTitle();
            if (fileName == null || fileName.isEmpty()) {
                fileName = "NX_" + System.currentTimeMillis() + (item.isVideo() ? ".mp4" : ".jpg");
            }

            URL url = new URL(item.getFullUrl());
            HttpURLConnection conn = openConnection(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", getUserAgent());
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                throw new Exception("HTTP server returned error: " + code);
            }

            long contentLength = conn.getContentLength();
            if (contentLength <= 0 && item.getSizeBytes() > 0) {
                contentLength = item.getSizeBytes();
            }

            InputStream in = new BufferedInputStream(conn.getInputStream(), 32768);
            OutputStream out = null;
            Uri insertedUri = null;
            File fallbackFile = null;

            // On Android 10+ (API 29+), use MediaStore to save directly into Pictures/NX1000
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
                    Log.w(TAG, "MediaStore insert failed, falling back to file write", e);
                }
            }

            if (out == null) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                fallbackFile = new File(targetDir, fileName);
                out = new FileOutputStream(fallbackFile);
            }

            byte[] buffer = new byte[32 * 1024];
            long totalRead = 0;
            int bytesRead;

            try {
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    if (listener != null) {
                        int percent = (contentLength > 0) ? (int) ((totalRead * 100) / contentLength) : -1;
                        listener.onProgress(totalRead, contentLength, percent);
                    }
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
                conn.disconnect();
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
                } catch (Exception e) {
                    Log.w(TAG, "MediaScanner error", e);
                }
            }
        } finally {
            isDownloading.set(false);
        }
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

    private static String readStreamToString(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString("UTF-8");
    }
}
