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

/**
 * Dedicated, isolated client for Samsung NX MobileLink mode.
 * Speaks pure UPnP/DLNA (ContentDirectory:1) with SEC_DSC_<MAC> identity.
 * Contains ZERO video streaming or RVF mode packets to prevent camera freezes.
 */
public class MobileLinkClient {
    private static final String TAG = "MobileLinkClient";

    public static class ServiceInfo {
        public String serviceType;
        public String serviceId;
        public String scpdUrl;
        public String controlUrl;
        public String eventSubUrl;
    }

    public static class DeviceInfo {
        public String friendlyName = "NX Camera (MobileLink)";
        public String modelName = "NX Series";
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

    public static synchronized String getUserAgent() {
        if (userAgent != null && userAgent.length() == 20) {
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

    public static void discoverCamera(final Context context, final String customIp, final int customPort, final DiscoveryCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                appContext = context.getApplicationContext();
                NetworkManager.bindProcessToWifi(appContext);
                NetworkManager.acquireLocks(appContext);

                final DeviceInfo[] foundDevice = new DeviceInfo[1];
                final AtomicBoolean resolved = new AtomicBoolean(false);

                final List<String> candidateIps = new ArrayList<>();
                if (customIp != null && !customIp.trim().isEmpty()) {
                    candidateIps.add(customIp.trim());
                }
                List<String> discovered = NetworkManager.getCandidateCameraIps(appContext);
                for (String ip : discovered) {
                    if (!candidateIps.contains(ip)) candidateIps.add(ip);
                }

                // Known Samsung MediaServer ports
                final int[] candidatePorts = (customPort > 0) ? new int[]{customPort} :
                        new int[]{52235, 7676, 80};

                final long deadline = System.currentTimeMillis() + 15000;

                // 1. Background SSDP discovery thread
                Thread ssdpThread = new Thread(new Runnable() {
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
                            try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                        }
                    }
                }, "MobileLink-SSDP-Search");
                ssdpThread.start();

                // 2. Direct HTTP Probing on primary Samsung MediaServer ports
                while (!resolved.get() && System.currentTimeMillis() < deadline) {
                    for (String ip : candidateIps) {
                        if (resolved.get()) break;
                        for (int p : candidatePorts) {
                            if (resolved.get()) break;
                            if (isPortOpen(ip, p, 400)) {
                                Log.i(TAG, "Port " + p + " is open on " + ip + "! Probing description XML...");
                                DeviceInfo dev = probeHttpEndpoint(ip, p);
                                if (dev != null && resolved.compareAndSet(false, true)) {
                                    foundDevice[0] = dev;
                                    break;
                                }
                            }
                        }
                    }
                    if (resolved.get()) break;
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { break; }
                }

                ssdpThread.interrupt();

                if (foundDevice[0] != null) {
                    activeDevice = foundDevice[0];
                    callback.onDeviceFound(foundDevice[0]);
                } else {
                    callback.onDiscoveryFailed("Camera not found in MobileLink mode. Ensure phone Wi-Fi is connected to camera, and if the camera prompts 'Allow connection from smartphone?', press 'Yes' on the camera.");
                }
            }
        }).start();
    }

    private static DeviceInfo performSsdpSearch(int timeoutMs, List<String> candidateIps) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            Network net = NetworkManager.getActiveWifiNetwork();
            if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try { net.bindSocket(socket); } catch (Exception ignored) {}
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

                try {
                    socket.send(new DatagramPacket(bytes, bytes.length, multicastGroup, 1900));
                } catch (Exception ignored) {}

                if (candidateIps != null) {
                    for (String cip : candidateIps) {
                        try {
                            socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(cip), 1900));
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
                    String location = extractHeader(resp, "LOCATION");
                    if (location != null && !location.isEmpty()) {
                        DeviceInfo dev = fetchAndParseDeviceDescription(location);
                        if (dev != null) return dev;
                    }
                } catch (SocketTimeoutException te) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
        return null;
    }

    private static DeviceInfo probeHttpEndpoint(String ip, int port) {
        String[] paths = new String[]{
                "/description.xml",
                "/smp_10_",
                "/dmr/SamsungMRDesc.xml",
                "/upnp/ContentDirectory.xml",
                "/rootDesc.xml"
        };
        for (String p : paths) {
            String testUrl = "http://" + ip + ":" + port + p;
            DeviceInfo dev = fetchAndParseDeviceDescription(testUrl);
            if (dev != null) return dev;
        }
        return null;
    }

    private static DeviceInfo fetchAndParseDeviceDescription(String locationUrl) {
        try {
            URL url = new URL(locationUrl);
            HttpURLConnection conn = openConnection(url);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", getUserAgent());
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = new BufferedInputStream(conn.getInputStream());
                String xml = readStreamToString(in);
                conn.disconnect();
                return parseDeviceDescriptionXml(locationUrl, xml);
            }
            conn.disconnect();
        } catch (Exception ignored) {}
        return null;
    }

    private static DeviceInfo parseDeviceDescriptionXml(String locationUrl, String xml) {
        try {
            DeviceInfo device = new DeviceInfo();
            device.locationUrl = locationUrl;

            URL u = new URL(locationUrl);
            device.baseUrl = u.getProtocol() + "://" + u.getHost() + (u.getPort() != -1 ? ":" + u.getPort() : "");

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            ServiceInfo currentService = null;
            String currentTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                    if ("service".equalsIgnoreCase(currentTag)) {
                        currentService = new ServiceInfo();
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        if ("friendlyName".equalsIgnoreCase(currentTag) && device.friendlyName.contains("MobileLink")) {
                            device.friendlyName = text;
                        } else if ("modelName".equalsIgnoreCase(currentTag)) {
                            device.modelName = text;
                        } else if (currentService != null) {
                            if ("serviceType".equalsIgnoreCase(currentTag)) {
                                currentService.serviceType = text;
                            } else if ("serviceId".equalsIgnoreCase(currentTag)) {
                                currentService.serviceId = text;
                            } else if ("SCPDURL".equalsIgnoreCase(currentTag)) {
                                currentService.scpdUrl = resolveUrl(device.baseUrl, text);
                            } else if ("controlURL".equalsIgnoreCase(currentTag)) {
                                currentService.controlUrl = resolveUrl(device.baseUrl, text);
                            } else if ("eventSubURL".equalsIgnoreCase(currentTag)) {
                                currentService.eventSubUrl = resolveUrl(device.baseUrl, text);
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("service".equalsIgnoreCase(parser.getName()) && currentService != null) {
                        device.services.add(currentService);
                        if (currentService.serviceType != null &&
                                currentService.serviceType.toLowerCase().contains("contentdirectory")) {
                            device.controlUrl = currentService.controlUrl;
                        }
                        currentService = null;
                    }
                    currentTag = "";
                }
                eventType = parser.next();
            }

            if (device.controlUrl == null) {
                for (ServiceInfo s : device.services) {
                    if (s.controlUrl != null) {
                        device.controlUrl = s.controlUrl;
                        break;
                    }
                }
            }

            if (device.controlUrl == null) {
                device.controlUrl = device.baseUrl + "/upnp/control/ContentDirectory";
            }

            return device;
        } catch (Exception e) {
            Log.w(TAG, "Error parsing description XML: " + e.getMessage());
            return null;
        }
    }

    private static String resolveUrl(String base, String rel) {
        if (rel == null) return null;
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        if (!rel.startsWith("/")) rel = "/" + rel;
        return base + rel;
    }

    private static String readStreamToString(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int len;
        while ((len = in.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString("UTF-8");
    }

    // =========================================================================
    // KEEPALIVE ENGINE (Pure MobileLink: UDP M-SEARCH + BrowseMetadata Ping)
    // =========================================================================

    private static volatile DatagramSocket ssdpSocket;
    private static volatile Thread ssdpThread;
    private static ScheduledExecutorService keepAliveExecutor;

    public static synchronized void startKeepAlive(Context context, DeviceInfo device) {
        stopKeepAlive();
        appContext = context.getApplicationContext();
        activeDevice = device;
        final DeviceInfo dev = device;

        Log.i(TAG, "Starting pure MobileLink session for " + device.baseUrl);

        // 1. Initial description handshake
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                sendDescriptionHandshake(dev);
            }
        });

        // 2. Heartbeat: Sends UDP M-SEARCH to 239.255.255.250:1900 every 1.5 seconds.
        // Purely UDP - does NOT tie up the camera's HTTP socket pool.
        ssdpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ssdpHeartbeatLoop(dev);
            }
        }, "MobileLink-SSDP-Heartbeat");
        ssdpThread.setDaemon(true);
        ssdpThread.start();

        // 3. Periodic lightweight browse ping every 10 seconds ONLY when NOT downloading
        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor();
        keepAliveExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    // NEVER send HTTP pings to camera while a photo download is active!
                    if (isDownloading.get()) return;

                    if (activeDevice != null) {
                        sendDescriptionHandshake(activeDevice);
                        sendBrowseHeartbeatPing(activeDevice);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "MobileLink keepalive tick warning: " + e.getMessage());
                }
            }
        }, 4000, 10000, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stopKeepAlive() {
        if (keepAliveExecutor != null) {
            try { keepAliveExecutor.shutdownNow(); } catch (Exception ignored) {}
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
        activeDevice = null;
    }

    private static void ssdpHeartbeatLoop(DeviceInfo device) {
        String msg = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n\r\n";
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
                long until = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < until && Thread.currentThread() == ssdpThread) {
                    try {
                        s.receive(new DatagramPacket(rx, rx.length));
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                if (Thread.currentThread() != ssdpThread) break;
                DatagramSocket s = ssdpSocket;
                ssdpSocket = null;
                if (s != null) try { s.close(); } catch (Exception ignored) {}
                try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private static void sendDescriptionHandshake(DeviceInfo device) {
        if (device == null || device.locationUrl == null) return;
        try {
            URL url = new URL(device.locationUrl);
            HttpURLConnection conn = openConnection(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestProperty("User-Agent", getUserAgent());
            conn.setRequestProperty("Connection", "close");
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

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
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
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

            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // PHOTO BROWSING & BFS DIRECTORY TRAVERSAL
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

        Log.i(TAG, "Starting MobileLink photo browse for: " + device.baseUrl);

        while (!containerQueue.isEmpty()) {
            String containerId = containerQueue.poll();
            List<String> subContainers = new ArrayList<>();
            browseContainer(device, containerId, allPhotos, subContainers);
            for (String sub : subContainers) {
                if (sub != null && !sub.isEmpty() && visitedContainers.add(sub)) {
                    containerQueue.add(sub);
                }
            }
        }

        // Fallback containers if root container "0" returned 0 items
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
                    if (!allPhotos.isEmpty()) break;
                }
            }
        }

        Log.i(TAG, "MobileLink total photos found: " + allPhotos.size());
        return allPhotos;
    }

    private static void browseContainer(DeviceInfo device, String objectId, List<PhotoItem> outPhotos, List<String> outSubContainers) {
        int startingIndex = 0;
        int requestedCount = 25; // 25 items per chunk (prevents camera buffer overflow)
        boolean hasMore = true;
        int maxLoops = 200;

        String serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
        if (device.services != null) {
            for (ServiceInfo s : device.services) {
                if (s.serviceType != null && s.serviceType.toLowerCase().contains("contentdirectory")) {
                    serviceType = s.serviceType;
                    break;
                }
            }
        }

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

                    String didlXml = extractResultFromSoap(responseXml);
                    if (didlXml != null && !didlXml.isEmpty()) {
                        int beforeItemsCount = outPhotos.size();
                        parseDidlLite(device.baseUrl, didlXml, outPhotos, outSubContainers);
                        int addedItems = outPhotos.size() - beforeItemsCount;

                        int progressStep = numberReturned > 0 ? numberReturned : addedItems;
                        if (progressStep <= 0) {
                            hasMore = false;
                        } else {
                            startingIndex += progressStep;
                            if (totalMatches > 0 && startingIndex >= totalMatches) {
                                hasMore = false;
                            } else if (numberReturned > 0 && numberReturned < requestedCount) {
                                hasMore = false;
                            }
                        }
                    } else {
                        hasMore = false;
                    }
                } else {
                    conn.disconnect();
                    hasMore = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error browsing container " + objectId + ": " + e.getMessage());
                hasMore = false;
            }
        }
    }

    private static String extractResultFromSoap(String soapResponse) {
        if (soapResponse == null || soapResponse.isEmpty()) return null;
        try {
            Pattern p = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?Result\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?Result>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(soapResponse);
            if (m.find()) {
                return unescapeXml(m.group(1));
            }
            Pattern pEsc = Pattern.compile("&lt;(?:[a-zA-Z0-9_-]+:)?Result\\b[^&]*&gt;([\\s\\S]*?)&lt;/(?:[a-zA-Z0-9_-]+:)?Result&gt;", Pattern.CASE_INSENSITIVE);
            Matcher mEsc = pEsc.matcher(soapResponse);
            if (mEsc.find()) {
                return unescapeXml(mEsc.group(1));
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
                        if (cId != null && outSubContainers != null) {
                            outSubContainers.add(cId);
                        }
                    } else if ("item".equalsIgnoreCase(currentTag)) {
                        currentItem = new PhotoItem();
                        String itemId = parser.getAttributeValue(null, "id");
                        currentItem.setId(itemId);
                    } else if ("res".equalsIgnoreCase(currentTag) && currentItem != null) {
                        tempProtocolInfo = parser.getAttributeValue(null, "protocolInfo");
                        String sizeStr = parser.getAttributeValue(null, "size");
                        try {
                            if (sizeStr != null && !sizeStr.isEmpty()) {
                                tempResSize = Long.parseLong(sizeStr.trim());
                            }
                        } catch (Exception ignored) {}
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    String text = parser.getText().trim();
                    if (!text.isEmpty() && currentItem != null) {
                        if ("dc:title".equalsIgnoreCase(currentTag) || "title".equalsIgnoreCase(currentTag)) {
                            currentItem.setTitle(text);
                        } else if ("dc:date".equalsIgnoreCase(currentTag) || "date".equalsIgnoreCase(currentTag)) {
                            currentItem.setDate(text);
                        } else if ("upnp:class".equalsIgnoreCase(currentTag)) {
                            if (text.toLowerCase().contains("video")) {
                                currentItem.setVideo(true);
                            }
                        } else if ("res".equalsIgnoreCase(currentTag)) {
                            tempResUrl = text;
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String endTag = parser.getName();
                    if ("res".equalsIgnoreCase(endTag) && currentItem != null && tempResUrl != null) {
                        String resolved = resolveUrl(baseUrl, tempResUrl);
                        boolean isThumb = false;
                        if (tempProtocolInfo != null) {
                            String lowerProto = tempProtocolInfo.toLowerCase();
                            if (lowerProto.contains("jpeg_sm") || lowerProto.contains("jpeg_tn") || lowerProto.contains("thumb")) {
                                isThumb = true;
                            }
                        }
                        if (isThumb) {
                            if (currentItem.getThumbUrl() == null) currentItem.setThumbUrl(resolved);
                        } else {
                            if (currentItem.getFullUrl() == null) {
                                currentItem.setFullUrl(resolved);
                                if (tempResSize > 0) currentItem.setSizeBytes(tempResSize);
                            }
                        }
                        tempResUrl = null;
                        tempProtocolInfo = null;
                        tempResSize = 0;
                    } else if ("item".equalsIgnoreCase(endTag) && currentItem != null) {
                        if (currentItem.getFullUrl() != null) {
                            if (currentItem.getThumbUrl() == null) {
                                currentItem.setThumbUrl(currentItem.getFullUrl());
                            }
                            outPhotos.add(currentItem);
                        }
                        currentItem = null;
                    }
                    currentTag = "";
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "parseDidlLite XML parse warning: " + e.getMessage());
        }
    }

    // =========================================================================
    // DOWNLOAD ENGINE (Zero-Traffic Collision, Clean MediaStore Export)
    // =========================================================================

    public static void downloadPhoto(Context context, PhotoItem item, File targetDir, DownloadProgressListener listener) throws Exception {
        boolean wasDownloading = isDownloading.getAndSet(true);
        try {
            String fileName = item.getTitle();
            if (fileName == null || fileName.isEmpty()) {
                fileName = "NX_" + System.currentTimeMillis() + (item.isVideo() ? ".mp4" : ".jpg");
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
                fallbackFile = new File(targetDir, fileName);
                out = new FileOutputStream(fallbackFile);
            }

            // Download using HumbleHttpClient (Strict HTTP/1.0 raw socket, immediate close)
            // This prevents VxWorks RTOS socket exhaustion and eliminates the 3-photo disconnect!
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
