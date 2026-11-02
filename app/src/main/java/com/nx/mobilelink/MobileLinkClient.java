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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
import java.util.ArrayList;
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
 * Robust, native DLNA Digital Media Server (DMS-1.50) client for Samsung smart cameras
 * (NX1000, WB350F, WB, EX, ST series).
 *
 * Implements pure UPnP/DLNA ContentDirectory service browsing and download logic
 * reverse-engineered and verified against working hardware.
 *
 * CRITICAL STABILITY DESIGN:
 * 1. Zero socket heartbeats / Zero background polling between user actions.
 * 2. Recursive DOM-based tree browsing (ObjectID="0" DFS).
 * 3. Port 7676 /smp_6_ priority direct probing.
 * 4. Strict HTTP/1.0 sequential downloads with immediate socket closure.
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

    // =========================================================================
    // DISCOVERY ENGINE: SSDP (upnp:rootdevice) + Port 7676 /smp_6_ Direct Probing
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

                final List<String> candidateIps = new ArrayList<>();
                if (customIp != null && !customIp.trim().isEmpty()) {
                    candidateIps.add(customIp.trim());
                }
                List<String> discovered = NetworkManager.getCandidateCameraIps(appContext);
                for (String ip : discovered) {
                    if (!candidateIps.contains(ip)) candidateIps.add(ip);
                }
                // Known Samsung default AP gateways
                if (!candidateIps.contains("192.168.107.1")) candidateIps.add("192.168.107.1");
                if (!candidateIps.contains("192.168.49.1")) candidateIps.add("192.168.49.1");
                if (!candidateIps.contains("192.168.1.1")) candidateIps.add("192.168.1.1");

                final int[] candidatePorts = (customPort > 0) ? new int[]{customPort} :
                        new int[]{7676, 52235, 80, 49152, 49153, 8080};

                final long deadline = System.currentTimeMillis() + 8000;

                // 1. Background SSDP discovery thread (matching SamsungCameraDownloader)
                Thread ssdpThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            DeviceInfo dev = performSsdpSearch(3500, candidateIps);
                            if (dev != null && resolved.compareAndSet(false, true)) {
                                foundDevice[0] = dev;
                            }
                        } catch (Exception ignored) {}
                    }
                }, "MobileLink-SSDP-Search");
                ssdpThread.start();

                // 2. Direct HTTP Probing on primary Samsung MediaServer endpoints
                // Checks port 7676 /smp_6_ FIRST as priority (primary Samsung camera endpoint)
                while (!resolved.get() && System.currentTimeMillis() < deadline) {
                    for (String ip : candidateIps) {
                        if (resolved.get()) break;
                        for (int p : candidatePorts) {
                            if (resolved.get()) break;
                            if (isPortOpen(ip, p, 300)) {
                                Log.i(TAG, "Port " + p + " is open on " + ip + "! Probing UPnP description...");
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
                    callback.onDiscoveryFailed("Camera not found in MobileLink mode. Ensure phone Wi-Fi is connected to camera, and press 'Yes' on camera screen if prompted.");
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

            // Exactly matching SamsungCameraDownloader: ST: upnp:rootdevice, MX: 3
            String searchMsg = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: upnp:rootdevice\r\n\r\n";
            byte[] bytes = searchMsg.getBytes(StandardCharsets.UTF_8);

            // Multicast
            InetAddress multicastGroup = InetAddress.getByName("239.255.255.250");
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, multicastGroup, 1900));
            } catch (Exception ignored) {}

            // Direct unicast to candidate IPs on port 1900
            if (candidateIps != null) {
                for (String cip : candidateIps) {
                    try {
                        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(cip), 1900));
                    } catch (Exception ignored) {}
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
                        if (dev != null && dev.controlUrl != null) return dev;
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
        // Priority endpoints matching SamsungCameraDownloader & Samsung DSC UPnP firmware:
        String[] paths = new String[]{
                "/smp_6_",                // Samsung primary description endpoint!
                "/description.xml",
                "/rootDesc.xml",
                "/smp_4_",
                "/smp_2_",
                "/smp_10_",
                "/dmr/SamsungMRDesc.xml",
                "/upnp/ContentDirectory.xml"
        };
        for (String p : paths) {
            String testUrl = "http://" + ip + ":" + port + p;
            DeviceInfo dev = fetchAndParseDeviceDescription(testUrl);
            if (dev != null && dev.controlUrl != null) return dev;
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

    private static DeviceInfo parseDeviceDescriptionXml(String locationUrl, String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            DeviceInfo device = new DeviceInfo();
            device.locationUrl = locationUrl;
            URL u = new URL(locationUrl);
            device.baseUrl = u.getProtocol() + "://" + u.getHost() + (u.getPort() != -1 ? ":" + u.getPort() : "");

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
                Element scElem = findElementByLocalName(svcElem, "controlURL");
                if (scElem != null) {
                    s.controlUrl = resolveUrl(device.baseUrl, scElem.getTextContent().trim());
                }
                device.services.add(s);
                if (s.serviceType != null && s.serviceType.toLowerCase().contains("contentdirectory")) {
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
            if (device.controlUrl == null) {
                device.controlUrl = device.baseUrl + "/smp_7_";
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
    // SESSION MANAGEMENT (Zero socket polling - camera socket-silent between actions)
    // =========================================================================

    public static synchronized void startKeepAlive(Context context, DeviceInfo device) {
        // Zero keepalive / Zero background polling (matching SamsungCameraDownloader).
        // Sending continuous heartbeats crashes Samsung camera's tiny VxWorks socket table!
        appContext = context.getApplicationContext();
        activeDevice = device;
        Log.i(TAG, "MobileLink connected to " + device.baseUrl + " (Socket-silent mode active)");
    }

    public static synchronized void stopKeepAlive() {
        activeDevice = null;
    }

    // =========================================================================
    // PHOTO BROWSING: Pure Recursive DFS Tree Traversal (ObjectID="0")
    // =========================================================================

    public static List<PhotoItem> browsePhotos(DeviceInfo device) throws Exception {
        if (device == null || device.controlUrl == null) {
            throw new IllegalArgumentException("Invalid device or control URL");
        }
        List<PhotoItem> allPhotos = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        Log.i(TAG, "Starting recursive photo browse for: " + device.controlUrl);

        // Start DFS at root container "0" (matching SamsungCameraDownloader)
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
        int requestedCount = 200; // Efficient chunk size matching SamsungCameraDownloader
        boolean hasMore = true;
        int safetyLoops = 50;

        String serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
        if (device.services != null) {
            for (ServiceInfo s : device.services) {
                if (s.serviceType != null && s.serviceType.toLowerCase().contains("contentdirectory")) {
                    serviceType = s.serviceType;
                    break;
                }
            }
        }

        List<String> subContainers = new ArrayList<>();

        while (hasMore && safetyLoops-- > 0) {
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
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                conn.setRequestProperty("SOAPACTION", "\"" + serviceType + "#Browse\"");
                conn.setRequestProperty("User-Agent", getUserAgent());
                conn.setRequestProperty("Connection", "close");

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

                    String didlXml = extractResultFromSoap(responseXml);
                    if (didlXml != null && !didlXml.isEmpty()) {
                        int beforeCount = outPhotos.size();
                        parseDidlLiteDom(device.baseUrl, didlXml, outPhotos, subContainers);
                        int added = outPhotos.size() - beforeCount;

                        int numberReturned = extractIntFromXml(responseXml, "NumberReturned");
                        int totalMatches = extractIntFromXml(responseXml, "TotalMatches");

                        int step = (numberReturned > 0) ? numberReturned : added;
                        if (step <= 0 || step < requestedCount || (totalMatches > 0 && startingIndex + step >= totalMatches)) {
                            hasMore = false;
                        } else {
                            startingIndex += step;
                        }
                    } else {
                        hasMore = false;
                    }
                } else {
                    conn.disconnect();
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
            // First check DOM Result tag using localName traversal
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
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

        // Fallback regex if DOM parsing of SOAP envelope failed
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
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
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

                // Title (matches <title>, <dc:title>)
                Element titleElem = findElementByLocalName(it, "title");
                String title = (titleElem != null) ? titleElem.getTextContent().trim() : "";

                // Date (matches <date>, <dc:date>)
                Element dateElem = findElementByLocalName(it, "date");
                String date = (dateElem != null) ? dateElem.getTextContent().trim() : "";
                photo.setDate(date);

                // Class (matches <class>, <upnp:class>)
                Element classElem = findElementByLocalName(it, "class");
                String upnpClass = (classElem != null) ? classElem.getTextContent().trim().toLowerCase() : "";
                if (upnpClass.contains("video")) {
                    photo.setVideo(true);
                }

                // Resources (<res>)
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

                    // DLNA thumbnail identification matching SamsungCameraDownloader markers
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
                    // Ensure valid extension (mapping MIME types matching SamsungCameraDownloader)
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
            String fileName = item.getTitle();
            if (fileName == null || fileName.isEmpty()) {
                fileName = "NX_" + System.currentTimeMillis() + (item.isVideo() ? ".mp4" : ".jpg");
            }

            // Deduplication: Skip existing files (matching SamsungCameraDownloader)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isMediaStoreFileExists(context, fileName)) {
                    Log.i(TAG, "File already exists in MediaStore: " + fileName + " (skipping)");
                    if (listener != null) listener.onProgress(item.getSizeBytes(), item.getSizeBytes(), 100);
                    return;
                }
            } else {
                if (targetDir == null) {
                    targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NX1000");
                }
                File existing = new File(targetDir, fileName);
                if (existing.exists() && existing.length() > 0) {
                    Log.i(TAG, "File already exists on disk: " + fileName + " (skipping)");
                    if (listener != null) listener.onProgress(existing.length(), existing.length(), 100);
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
                if (targetDir == null) {
                    targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NX1000");
                }
                if (!targetDir.exists()) targetDir.mkdirs();
                fallbackFile = new File(targetDir, fileName);
                out = new FileOutputStream(fallbackFile);
            }

            // Download using HumbleHttpClient (Strict HTTP/1.0, immediate socket close)
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
