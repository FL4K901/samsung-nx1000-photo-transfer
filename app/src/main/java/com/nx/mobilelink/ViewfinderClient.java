package com.nx.mobilelink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
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
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Handles communication with Samsung NX1000 / Smart Camera in "Remote Viewfinder" mode.
 *
 * Reconstructed from official Samsung Remote Viewfinder v2.2.11 APK (com.samsung.app):
 * - SSDP Multicast (239.255.255.250:1900) listener + M-SEARCH with User-Agent: SEC_RVF_<MAC>
 * - Mandatory HTTP GET on <LOCATION> and <SCPDURL> with User-Agent: SEC_RVF_<MAC>
 *   and UPnP Event SUBSCRIBE with CALLBACK: <http://localIp:8059/>
 * - UPnP Service: urn:schemas-upnp-org:service:ContentDirectory:1
 * - SOAP Actions:
 *   1. GetInfomation (note Samsung typo: "GetInfomation", NOT "GetInformation")
 *      -> Returns <GETINFORMATIONRESULT> containing <Resolutions>, <Flash>, <ZoomInfo>,
 *         <AVAILSHOTS>, <ROTATION>, and <GETIPRESULT> (the live stream URL, e.g. http://192.168.107.1:7679/...)
 *   2. Shot (with <Flash>, <Zoom>, <ResolutionWidth>, <ResolutionHeight>, <Timer>, <Rotation>, <TouchAF>, <SaveStorage>)
 *      -> Returns <AFATGPSRESULT> (captured file URL) and <AVAILSHOTSRESULT>
 *   3. SetResolution (with <Width>, <Height>) -> Returns <CHANGERESOLUTIONRESULT> (new stream URL)
 *   4. SetZoom (with <Zoom>)
 *   5. SetFlash (with <Flash>)
 *   6. SetAutoFocus (with <FocusX>, <FocusY>, <PrevWidth>, <PrevHeight>, <Rotation>)
 * - Live Stream Protocol (reconstructed from libffplay-jni.so + LiveShutter.initcodec):
 *   - HTTP GET to StreamURL with User-Agent: Lavf52.103.0, Range: bytes=0-, Connection: close
 *   - Demuxes AVI container ("RIFF....AVI ", -f avi) or MP4 (-f mp4) or raw Annex-B / MJPEG
 *   - Decodes H.264 Annex-B (00 00 00 01 27 / 67 SPS, 28 / 68 PPS, 25 / 65 IDR, 21 / 41 / 61 P-slice),
 *     MPEG-4 Part 2 (FMP4 / MP4V), or H.263 via Android MediaCodec to Bitmap frames.
 */
public class ViewfinderClient {

    private static final String TAG = "ViewfinderClient";

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    // Exact UPnP service type used by Remote Viewfinder (MyControlPoint.java)
    private static final String UPNP_SERVICE_CONTENT_DIR = "urn:schemas-upnp-org:service:ContentDirectory:1";

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
        void onSuccess();
        void onError(String error);
    }

    public interface AutoFocusCallback {
        void onAutoFocusSuccess(String afStatus);
        void onAutoFocusFailed(String error);
    }

    private static class UpnpEndpoint {
        final String host;
        final int port;
        final String controlPath;
        final String serviceType;

        UpnpEndpoint(String host, int port, String controlPath, String serviceType) {
            this.host = host;
            this.port = port;
            this.controlPath = controlPath.startsWith("/") ? controlPath : "/" + controlPath;
            this.serviceType = serviceType;
        }
    }

    private static final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static Thread streamThread;
    private static volatile Socket activeStreamSocket;
    private static volatile FrameCallback lastFrameCallback;

    private static volatile Context appContext;
    private static volatile Network activeWifiNetwork;
    private static volatile String activeCameraIp;
    private static volatile String activeStreamUrl;
    private static volatile List<UpnpEndpoint> activeEndpoints = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Camera state parsed from GetInfomation
    private static volatile String currentFlash = "off";
    private static volatile int currentZoom = 1;
    private static volatile int maxZoom = 1;
    private static volatile int currentWidth = 0;
    private static volatile int currentHeight = 0;
    private static volatile int currentRotation = 1; // 1 = normal (0 deg)
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

    /**
     * Exact User-Agent required by Samsung Remote Viewfinder firmware:
     * "SEC_RVF_" + MAC address (uppercase)
     */
    private static String getUserAgent() {
        String mac = getLocalMacAddress(appContext);
        return "SEC_RVF_" + mac;
    }

    private static String getLocalMacAddress(Context context) {
        if (context != null) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo info = wm.getConnectionInfo();
                    if (info != null) {
                        String mac = info.getMacAddress();
                        if (mac != null && !mac.isEmpty() && !"02:00:00:00:00:00".equals(mac)) {
                            return mac.toUpperCase(Locale.US);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (!nif.getName().equalsIgnoreCase("wlan0")) continue;
                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : macBytes) {
                    sb.append(String.format(Locale.US, "%02X:", b));
                }
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                String mac = sb.toString();
                if (!"02:00:00:00:00:00".equals(mac) && !mac.isEmpty()) {
                    return mac;
                }
            }
        } catch (Exception ignored) {}
        return "00:12:47:11:22:33";
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
        connect(cameraIp, callback);
    }

    public static void connect(final String cameraIp, final ConnectionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                WifiManager.MulticastLock multicastLock = null;
                DatagramSocket searchSocket = null;
                MulticastSocket notifySocket = null;
                try {
                    if (appContext != null) {
                        Network wifiNet = NetworkManager.bindProcessToWifi(appContext);
                        if (wifiNet != null) {
                            activeWifiNetwork = wifiNet;
                        }
                        try {
                            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                            if (wm != null) {
                                multicastLock = wm.createMulticastLock("NX_RVF_SSDP");
                                multicastLock.setReferenceCounted(true);
                                multicastLock.acquire();
                            }
                        } catch (Exception ignored) {}
                    }

                    activeEndpoints.clear();
                    activeStreamUrl = null;
                    activeCameraIp = (cameraIp != null && !cameraIp.trim().isEmpty()) ? cameraIp.trim() : "192.168.107.1";

                    final String localIp = getLocalWifiIp(appContext);
                    final String ua = getUserAgent();
                    final AtomicBoolean deviceDiscovered = new AtomicBoolean(false);

                    // 1. Start SSDP Multicast Listener on port 1900 for NOTIFY ssdp:alive
                    try {
                        notifySocket = new MulticastSocket(null);
                        notifySocket.setReuseAddress(true);
                        notifySocket.bind(new InetSocketAddress(SSDP_PORT));
                        NetworkInterface wlanIf = findWifiNetworkInterface(localIp);
                        InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
                        if (wlanIf != null) {
                            notifySocket.joinGroup(new InetSocketAddress(group, SSDP_PORT), wlanIf);
                        } else {
                            notifySocket.joinGroup(group);
                        }
                        notifySocket.setSoTimeout(1000);
                    } catch (Exception e) {
                        Log.w(TAG, "Multicast listener setup warning: " + e.getMessage());
                    }

                    // 2. Open Unicast SSDP socket for M-SEARCH
                    searchSocket = new DatagramSocket(null);
                    searchSocket.setReuseAddress(true);
                    searchSocket.bind(new InetSocketAddress(localIp, 0));
                    if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= 22) {
                        try {
                            activeWifiNetwork.bindSocket(searchSocket);
                        } catch (Exception ignored) {}
                    }
                    searchSocket.setSoTimeout(800);

                    String[] searchTargets = {
                        "ssdp:all",
                        "upnp:rootdevice",
                        "urn:schemas-upnp-org:device:MediaServer:1",
                        UPNP_SERVICE_CONTENT_DIR
                    };

                    long startMs = System.currentTimeMillis();
                    byte[] rxBuf = new byte[8192];

                    // Discover via SSDP for up to 5.5 seconds (or until device XML + SCPDURL is fetched)
                    while (System.currentTimeMillis() - startMs < 5500 && !deviceDiscovered.get()) {
                        for (String st : searchTargets) {
                            String mSearch = "M-SEARCH * HTTP/1.1\r\n" +
                                    "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n" +
                                    "MAN: \"ssdp:discover\"\r\n" +
                                    "MX: 3\r\n" +
                                    "ST: " + st + "\r\n" +
                                    "USER-AGENT: " + ua + "\r\n\r\n";
                            byte[] tx = mSearch.getBytes(StandardCharsets.UTF_8);
                            try {
                                searchSocket.send(new DatagramPacket(tx, tx.length, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT));
                            } catch (Exception ignored) {}
                            if (activeCameraIp != null && !activeCameraIp.isEmpty()) {
                                try {
                                    searchSocket.send(new DatagramPacket(tx, tx.length, InetAddress.getByName(activeCameraIp), SSDP_PORT));
                                } catch (Exception ignored) {}
                            }
                        }

                        // Read M-SEARCH replies
                        long readEnd = System.currentTimeMillis() + 900;
                        while (System.currentTimeMillis() < readEnd && !deviceDiscovered.get()) {
                            try {
                                DatagramPacket pkt = new DatagramPacket(rxBuf, rxBuf.length);
                                searchSocket.receive(pkt);
                                String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                                String senderIp = pkt.getAddress().getHostAddress();
                                if (senderIp != null && !senderIp.equals(localIp)) {
                                    if (handleSsdpPacket(msg, senderIp, localIp)) {
                                        deviceDiscovered.set(true);
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                                break;
                            }
                        }

                        // Also check Multicast NOTIFY socket
                        if (notifySocket != null && !deviceDiscovered.get()) {
                            try {
                                DatagramPacket pkt = new DatagramPacket(rxBuf, rxBuf.length);
                                notifySocket.receive(pkt);
                                String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                                String senderIp = pkt.getAddress().getHostAddress();
                                if (senderIp != null && !senderIp.equals(localIp)) {
                                    if (handleSsdpPacket(msg, senderIp, localIp)) {
                                        deviceDiscovered.set(true);
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    // 3. Fallback: If SSDP was blocked by Android OEM firewall, directly probe known Samsung UPnP description XML paths
                    if (!deviceDiscovered.get()) {
                        List<String> candidateIps = new ArrayList<>();
                        if (activeCameraIp != null && !activeCameraIp.isEmpty()) {
                            candidateIps.add(activeCameraIp);
                        }
                        if (appContext != null) {
                            for (String ip : NetworkManager.getCandidateCameraIps(appContext)) {
                                if (!candidateIps.contains(ip)) candidateIps.add(ip);
                            }
                        }
                        String[] xmlPaths = {"/smp_2_", "/smp_1_", "/smp_5_", "/description.xml", "/ rootDesc.xml"};
                        int[] xmlPorts = {7676, 7678, 52235, 49152, 49153, 8080};

                        outer:
                        for (String ip : candidateIps) {
                            for (int port : xmlPorts) {
                                for (String path : xmlPaths) {
                                    String loc = "http://" + ip + ":" + port + path.trim();
                                    try {
                                        String xml = HumbleHttpClient.httpGetXml(activeWifiNetwork, loc, ua, 1200);
                                        if (xml != null && (xml.contains("ContentDirectory") || xml.contains("serviceList"))) {
                                            Log.i(TAG, "Direct XML probe succeeded at " + loc);
                                            activeCameraIp = ip;
                                            parseDeviceDescriptionXml(xml, loc, ip, port, localIp);
                                            deviceDiscovered.set(true);
                                            break outer;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    // 4. Execute GetInfomation SOAP Action in a retry loop (matching LiveShutter$32: up to 25 attempts, 250ms interval)
                    // The camera transitions out of its "Waiting" screen when <LOCATION> and <SCPDURL> are fetched,
                    // and starts its live stream server when GetInfomation succeeds!
                    boolean infoSuccess = false;
                    for (int attempt = 1; attempt <= 25; attempt++) {
                        if (executeGetInformation(activeCameraIp)) {
                            infoSuccess = true;
                            Log.i(TAG, "GetInfomation succeeded on attempt " + attempt + " (streamUrl=" + activeStreamUrl + ")");
                            break;
                        }
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (infoSuccess && activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                        callback.onConnected(activeCameraIp);
                    } else if (infoSuccess) {
                        activeStreamUrl = "http://" + activeCameraIp + ":7679/livestream.avi";
                        callback.onConnected(activeCameraIp);
                    } else {
                        callback.onConnectionFailed("Could not initialize Remote Viewfinder session (GetInfomation failed). Check that the camera is in Remote Viewfinder mode and connected to its Wi-Fi.");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Viewfinder connect error", e);
                    callback.onConnectionFailed("Connection error: " + e.getMessage());
                } finally {
                    if (searchSocket != null) {
                        try { searchSocket.close(); } catch (Exception ignored) {}
                    }
                    if (notifySocket != null) {
                        try { notifySocket.close(); } catch (Exception ignored) {}
                    }
                    if (multicastLock != null && multicastLock.isHeld()) {
                        try { multicastLock.release(); } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
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

            // Fetch Device Description XML using exact User-Agent: SEC_RVF_<MAC>
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
     * Parses UPnP Device Description XML using DOM (matching MobileLinkClient.parseDeviceDescriptionXml),
     * fetches every <SCPDURL> with User-Agent: SEC_RVF_<MAC>, sends UPnP SUBSCRIBE to <eventSubURL>,
     * and prioritizes the service containing GetInfomation / Shot (ContentDirectory:1).
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
                            if (scpdXml != null && (scpdXml.contains("GetInfomation") || scpdXml.contains("Shot"))) {
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

                    if (hasGetInfoOrShot || (serviceType != null && serviceType.contains("ContentDirectory"))) {
                        prioritized.add(ep);
                    } else if (serviceType == null || !serviceType.contains("ConnectionManager")) {
                        fallbacks.add(ep);
                    }
                    Log.i(TAG, "Discovered UPnP service: " + st + " control=" + normControl + " port=" + epPort + " primary=" + hasGetInfoOrShot);
                }
            }

            activeEndpoints.clear();
            activeEndpoints.addAll(prioritized);
            activeEndpoints.addAll(fallbacks);
        } catch (Exception e) {
            Log.w(TAG, "DOM parseDeviceDescriptionXml error: " + e.getMessage());
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
     * Executes the exact SOAP action "GetInfomation" (Samsung's spelling without 'r')
     * on urn:schemas-upnp-org:service:ContentDirectory:1.
     */
    private static boolean executeGetInformation(String cameraIp) {
        String soapBody =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:GetInfomation xmlns:u=\"" + UPNP_SERVICE_CONTENT_DIR + "\"></u:GetInfomation>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>\r\n";

        String soapAction = "\"" + UPNP_SERVICE_CONTENT_DIR + "#GetInfomation\"";

        // 1. Try discovered endpoints first
        List<UpnpEndpoint> snapshot = new ArrayList<>(activeEndpoints);
        for (UpnpEndpoint ep : snapshot) {
            String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
            String response = sendSoapRequest(url, soapAction, soapBody, 3500);
            if (response != null && parseGetInformationResponse(response, ep.host)) {
                return true;
            }
        }

        // 2. If SSDP didn't populate endpoints yet, probe common Samsung UPnP ports/paths
        if (snapshot.isEmpty() && cameraIp != null && !cameraIp.isEmpty()) {
            for (int port : CANDIDATE_UPNP_PORTS) {
                for (String path : CANDIDATE_CONTROL_PATHS) {
                    String url = "http://" + cameraIp + ":" + port + path;
                    String response = sendSoapRequest(url, soapAction, soapBody, 1500);
                    if (response != null && parseGetInformationResponse(response, cameraIp)) {
                        activeEndpoints.add(0, new UpnpEndpoint(cameraIp, port, path, UPNP_SERVICE_CONTENT_DIR));
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean parseGetInformationResponse(String xml, String cameraIp) {
        if (xml == null || xml.isEmpty()) return false;
        String unescaped = unescapeXml(xml);

        boolean matched = false;
        if (unescaped.contains("GetInfomationResponse") ||
            unescaped.contains("GETINFORMATIONRESULT") ||
            unescaped.contains("GETIPRESULT") ||
            unescaped.contains("Resolutions")) {
            matched = true;
        }

        // Extract <GETIPRESULT> (the live stream URL, e.g. http://192.168.107.1:7679/...)
        String streamUrl = extractXmlTag(unescaped, "GETIPRESULT");
        if (streamUrl != null && !streamUrl.isEmpty()) {
            String candidateStreamUrl = streamUrl.trim();
            if (isSafeCameraUrl(candidateStreamUrl)) {
                activeStreamUrl = candidateStreamUrl;
                Log.i(TAG, "Parsed StreamURL from <GETIPRESULT>: " + activeStreamUrl);
            }
            matched = true;
        }

        // Extract <AVAILSHOTS>
        String shots = extractXmlTag(unescaped, "AVAILSHOTS");
        if (shots != null && !shots.isEmpty()) {
            availableShots = shots.trim();
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

        // Extract <ROTATION>
        String rot = extractXmlTag(unescaped, "ROTATION");
        if (rot != null) {
            try { currentRotation = Integer.parseInt(rot.trim()); } catch (Exception ignored) {}
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
            activeStreamUrl = "http://" + cameraIp + ":7679/livestream.avi";
        }

        return matched;
    }

    /**
     * Starts streaming live preview frames from the camera.
     *
     * Reconstructed from libffplay-jni.so + LiveShutter.java:
     * 1. Sends HTTP GET to StreamURL with User-Agent: Lavf52.103.0, Range: bytes=0-, Connection: close
     * 2. Demuxes the camera's AVI container ("RIFF....AVI ") or MP4 or raw Annex-B / MJPEG stream
     * 3. Decodes H.264 / MPEG-4 / H.263 video chunks via Android MediaCodec into Bitmap frames
     */
    public static void startLiveStream(final String cameraIp, final FrameCallback frameCallback) {
        stopLiveStream();
        lastFrameCallback = frameCallback;
        isStreaming.set(true);

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Build candidate stream URLs prioritizing the exact <GETIPRESULT> from GetInfomation
                List<String> streamUrls = new ArrayList<>();
                if (activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                    streamUrls.add(activeStreamUrl);
                }
                String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                            (activeCameraIp != null ? activeCameraIp : "192.168.107.1");
                String[] fallbacks = {
                    "http://" + ip + ":7679/livestream.avi",
                    "http://" + ip + ":7679/live",
                    "http://" + ip + ":7679/"
                };
                for (String fb : fallbacks) {
                    if (!streamUrls.contains(fb)) {
                        streamUrls.add(fb);
                    }
                }

                int consecutiveErrors = 0;

                while (isStreaming.get() && !Thread.currentThread().isInterrupted()) {
                    boolean gotAnyFrames = false;

                    // If we don't have a valid activeStreamUrl yet, re-query GetInfomation
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
                            int port = url.getPort() != -1 ? url.getPort() : 7679;
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
                                // Some raw camera sockets immediately stream RIFF/AVI without HTTP status line
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
                                Log.w(TAG, "Stream connection notice on " + urlStr + ": " + e.getMessage());
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
                        // Re-run GetInfomation to refresh StreamURL if stream connection dropped or wasn't ready
                        if (consecutiveErrors % 2 == 0) {
                            if (executeGetInformation(ip) && activeStreamUrl != null && !activeStreamUrl.isEmpty()) {
                                if (!streamUrls.contains(activeStreamUrl)) {
                                    streamUrls.add(0, activeStreamUrl);
                                }
                            }
                        }
                        if (consecutiveErrors == 4) {
                            frameCallback.onStreamError("Waiting for camera live video stream...");
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
        }, "NX-LiveStreamThread");
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

    /**
     * Reads HTTP response headers byte-by-byte up to \r\n\r\n so no binary video payload bytes are lost.
     */
    private static class HttpStreamHeader {
        int statusCode = 0;
        boolean isChunked = false;
        byte[] rawHeaderBytes = new byte[0];
    }

    private static HttpStreamHeader readHttpStreamHeader(InputStream in) throws IOException {
        HttpStreamHeader result = new HttpStreamHeader();
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(512);
        int b;
        int matchState = 0; // tracks \r\n\r\n

        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if (headerBuf.size() == 4) {
                byte[] first4 = headerBuf.toByteArray();
                String prefix = new String(first4, StandardCharsets.US_ASCII);
                if (!prefix.startsWith("HTTP")) {
                    // Not an HTTP header! Stream started directly with binary data (e.g. "RIFF")
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
                // Consume trailing \r\n after chunk body
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

    /**
     * Auto-detects the live stream container format (AVI "RIFF....AVI ", MP4 "ftyp"/"moov"/"mdat",
     * raw H.264 Annex-B 00 00 00 01, or MJPEG FF D8) and decodes frames into Bitmaps.
     */
    private static int decodeCameraStream(BufferedInputStream in, String streamUrl, FrameCallback frameCallback) throws IOException {
        in.mark(32);
        byte[] probe = new byte[16];
        int probeLen = readUpTo(in, probe, 0, probe.length);
        in.reset();

        if (probeLen < 4) return 0;

        // 1. Check for RIFF / AVI container ("RIFF....AVI " or "RIFF....AVIX")
        if (probe[0] == 'R' && probe[1] == 'I' && probe[2] == 'F' && probe[3] == 'F') {
            Log.i(TAG, "Detected RIFF/AVI live stream container from camera");
            return decodeAviStream(in, frameCallback);
        }

        // 2. Check for MP4 / MOV container ("ftyp", "moov", "mdat", "wide", "free")
        if (probeLen >= 8) {
            String boxType = new String(probe, 4, 4, StandardCharsets.US_ASCII);
            if ("ftyp".equals(boxType) || "moov".equals(boxType) || "mdat".equals(boxType) ||
                "free".equals(boxType) || "wide".equals(boxType) ||
                (streamUrl != null && streamUrl.toLowerCase(Locale.US).contains(".mp4"))) {
                Log.i(TAG, "Detected MP4/MOV live stream container (" + boxType + ") from camera");
                return decodeAnnexBOrMp4Stream(in, frameCallback);
            }
        }

        // 3. Check for raw H.264 Annex-B start code (00 00 00 01 or 00 00 01) or MPEG-4 start code (00 00 01 B0/B6)
        if ((probe[0] == 0 && probe[1] == 0 && probe[2] == 0 && probe[3] == 1) ||
            (probe[0] == 0 && probe[1] == 0 && probe[2] == 1)) {
            Log.i(TAG, "Detected raw Annex-B elementary video stream from camera");
            return decodeAnnexBOrMp4Stream(in, frameCallback);
        }

        // 4. Fallback: Scan stream for RIFF header, Annex-B start codes, or MJPEG SOI/EOI markers
        return decodeHybridOrMjpegStream(in, frameCallback);
    }

    /**
     * Demuxes a live AVI stream ("RIFF....AVI ") and decodes video packets (00dc, 00db, etc.)
     * using Android's hardware/software MediaCodec (or BitmapFactory if MJPG).
     *
     * Matches libffplay-jni.so's ff_avi_demuxer + decode_thread with -livestreaming:
     * - Parses strh ("vids", fccHandler) and strf (BITMAPINFOHEADER: biWidth, biHeight, biCompression, extradata)
     * - Steps inside LIST ("hdrl", "strl", "movi") without skipping the list body
     * - Extracts video frame packets ("00dc", "00db", "01dc", "01db")
     * - Handles both even-padded and unaligned AVI chunk boundaries gracefully
     */
    private static int decodeAviStream(BufferedInputStream in, FrameCallback frameCallback) throws IOException {
        LiveVideoDecoder videoDecoder = new LiveVideoDecoder(frameCallback);
        int decodedFrames = 0;

        try {
            // Read 12-byte RIFF header: "RIFF" + fileSize (4B) + "AVI " (4B)
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

                // Handle LIST or RIFF containers by stepping inside their 4-byte listType
                if ("LIST".equals(fourCC) || "RIFF".equals(fourCC)) {
                    byte[] listTypeBytes = new byte[4];
                    if (!readFully(in, listTypeBytes, 0, 4)) break;
                    String listType = new String(listTypeBytes, 0, 4, StandardCharsets.US_ASCII);
                    Log.d(TAG, "AVI LIST type=" + listType + " size=" + chunkSize);
                    continue;
                }

                // Guard against corrupt/negative chunk sizes (note: streaming AVI may have 0 or large sizes on outer containers)
                if (chunkSize < 0 || chunkSize > 4 * 1024 * 1024) {
                    // Resync to next valid video chunk or NAL unit
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
                        Log.i(TAG, "AVI strf video " + videoWidth + "x" + videoHeight + " compression=" + videoFourCC +
                                   " extradata=" + (extraData != null ? extraData.length : 0));
                        videoDecoder.configureFromHeader(videoFourCC, videoWidth, videoHeight, extraData);
                    }
                } else if (isVideoChunkFourCC(fourCC)) {
                    if (chunkSize == 0) {
                        continue;
                    }
                    byte[] packet = new byte[chunkSize];
                    if (!readFully(in, packet, 0, chunkSize)) break;
                    consumeAviPadIfPresent(in, chunkSize);

                    if (videoDecoder.decodePacket(packet, 0, chunkSize)) {
                        decodedFrames++;
                    }
                } else {
                    // Audio chunk (01wb/00wb), avih, JUNK, idx1, etc. -> skip chunk payload
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
        // Check 00dc, 00db, 01wb, 00wb, 01dc, etc.
        if (b0 >= '0' && b0 <= '9' && b1 >= '0' && b1 <= '9') {
            if ((b2 == 'd' || b2 == 'D') && (b3 == 'c' || b3 == 'C' || b3 == 'b' || b3 == 'B')) return true;
            if ((b2 == 'w' || b2 == 'W') && (b3 == 'b' || b3 == 'B')) return true;
        }
        // Check LIST, JUNK, avih, strh, strf, strn, strd, idx1, indx, odml, dmlh, INFO, RIFF
        if (b0 == 'L' && b1 == 'I' && b2 == 'S' && b3 == 'T') return true;
        if (b0 == 'J' && b1 == 'U' && b2 == 'N' && b3 == 'K') return true;
        if (b0 == 'a' && b1 == 'v' && b2 == 'i' && b3 == 'h') return true;
        if (b0 == 's' && b1 == 't' && b2 == 'r' && (b3 == 'h' || b3 == 'f' || b3 == 'n' || b3 == 'd')) return true;
        if (b0 == 'i' && b1 == 'd' && b2 == 'x' && b3 == '1') return true;
        if (b0 == 'i' && b1 == 'n' && b2 == 'd' && b3 == 'x') return true;
        if (b0 == 'd' && b1 == 'm' && b2 == 'l' && b3 == 'h') return true;
        if (b0 == 'I' && b1 == 'N' && b2 == 'F' && b3 == 'O') return true;
        if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F') return true;
        if (b0 == 'v' && b1 == 'p' && b2 == 'r' && b3 == 'p') return true;
        return false;
    }

    private static boolean isPrintableFourCC(byte b0, byte b1, byte b2, byte b3) {
        return (b0 >= 0x20 && b0 <= 0x7E) &&
               (b1 >= 0x20 && b1 <= 0x7E) &&
               (b2 >= 0x20 && b2 <= 0x7E) &&
               (b3 >= 0x20 && b3 <= 0x7E);
    }

    /**
     * In standard RIFF/AVI, odd-sized chunks are padded with 1 byte to a 2-byte boundary,
     * EXCEPT some embedded camera muxers omit the pad byte (or add a pad byte).
     * We peek ahead 5 bytes to see whether the valid FourCC starts at +0 or +1!
     */
    private static void consumeAviPadIfPresent(BufferedInputStream in, int chunkSize) throws IOException {
        if ((chunkSize & 1) == 0) return;
        in.mark(8);
        byte[] peek = new byte[5];
        int n = readUpTo(in, peek, 0, 5);
        in.reset();
        if (n >= 5) {
            if (isKnownAviFourCC(peek[0], peek[1], peek[2], peek[3]) && !isKnownAviFourCC(peek[1], peek[2], peek[3], peek[4])) {
                // Camera omitted the odd pad byte; next FourCC is right at offset 0
                return;
            }
        }
        // Standard RIFF 1-byte pad
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
        // Slide window byte-by-byte until a known AVI FourCC with plausible size is found
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

    /**
     * Decodes a raw Annex-B H.264 / MPEG-4 elementary stream or MP4 stream by splitting NAL access units.
     */
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

                // Split completed access units on Slice/SPS start codes (00 00 00 01)
                int searchFrom = 0;
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
                        // Keep SPS (7), PPS (8), SEI (6), AUD (9) grouped with their following IDR (5) / Slice (1)
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

    /**
     * Fallback scanner for streams that either wrap AVI/Annex-B after custom headers or send MJPEG (FF D8 ... FF D9).
     */
    private static int decodeHybridOrMjpegStream(BufferedInputStream in, FrameCallback frameCallback) throws IOException {
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(65536);
        byte[] readBuf = new byte[16384];
        boolean inJpeg = false;
        int prevByte = -1;
        int framesDecoded = 0;

        int bytesRead;
        while (isStreaming.get() && (bytesRead = in.read(readBuf)) != -1) {
            // Check if RIFFAVI appeared in readBuf
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

    private static Bitmap applyCameraRotation(Bitmap bmp) {
        if (bmp == null) return null;
        int deg = 0;
        // LiveShutter rotation values: 3 = 180 (U), 6 = 90 CW (R), 8 = 270 CW (L)
        if (currentRotation == 3) deg = 180;
        else if (currentRotation == 6) deg = 90;
        else if (currentRotation == 8) deg = 270;
        if (deg == 0) return bmp;
        try {
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        } catch (Exception e) {
            return bmp;
        }
    }

    /**
     * Hardware/Software MediaCodec Video Decoder for NX1000 Live Viewfinder Stream.
     *
     * Supports:
     * - H.264 / AVC ("video/avc") — including Samsung NX1000 Annex-B packets starting with
     *   00 00 00 01 27 or 00 00 00 01 67 (SPS), 00 00 00 01 28 / 68 (PPS), and IDR/P slices.
     * - MPEG-4 Part 2 ("video/mp4v-es") — FMP4 / MP4V / DIVX / XVID
     * - H.263 ("video/3gpp")
     * - MJPEG (via BitmapFactory fallback if a packet starts with FF D8)
     */
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

        // Reusable ARGB pixel buffer and double-buffered Bitmaps to prevent UI thread tearing & GC churn
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

            // 1. Check if packet is actually a JPEG frame (FF D8)
            if ((data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xFF) == 0xD8) {
                Bitmap bmp = BitmapFactory.decodeByteArray(data, offset, length);
                if (bmp != null) {
                    frameCallback.onFrame(applyCameraRotation(bmp));
                    return true;
                }
            }

            // 2. Auto-detect H.264 vs MPEG-4 from packet start codes if needed
            byte[] normalized = normalizePacketStartCodes(data, offset, length);
            extractCsdFromBytes(normalized, 0, normalized.length);

            if (!configured) {
                if ("video/avc".equals(mimeType) && csd0 == null) {
                    // Wait until the first SPS keyframe arrives (e.g. 00 00 00 01 27 or 00 00 00 01 67)
                    // just like libffplay-jni.so's first_nal_unit_header check!
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
                Log.w(TAG, "MediaCodec decode step warning: " + e.getMessage());
                // Reset codec so next keyframe re-initializes cleanly
                release();
                return false;
            }
        }

        private byte[] normalizePacketStartCodes(byte[] data, int offset, int length) {
            // Check if already Annex-B (00 00 00 01 or 00 00 01)
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

            // Check if length-prefixed AVCC (4-byte big-endian NAL length)
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
                    if (nalType == 7) { // SPS (e.g. 00 00 00 01 27 or 00 00 00 01 67)
                        csd0 = buildAnnexBNal(buf, nalStart, nalEnd - nalStart);
                    } else if (nalType == 8) { // PPS (e.g. 00 00 00 01 28 or 00 00 00 01 68)
                        csd1 = buildAnnexBNal(buf, nalStart, nalEnd - nalStart);
                    }
                    p = nalEnd;
                }
            } else if ("video/mp4v-es".equals(mimeType) && csd0 == null) {
                // Look for MPEG-4 VOL start code 00 00 01 B0 .. before 00 00 01 B6 (VOP)
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
                timeoutUs = 0; // subsequent drains in same packet loop are non-blocking
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
                    timeoutUs = 8000; // give decoder a moment right after format change
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
                        Log.w(TAG, "YUV frame conversion warning: " + e.getMessage());
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

        /**
         * Fast integer YUV_420_888 Image to ARGB_8888 Bitmap conversion using double-buffered Bitmaps.
         */
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

    /**
     * Reconstructed from Samsung LiveShutter$AFActTask:
     * Triggers autofocus by sending UPnP SOAP action "MULTIAF" on urn:schemas-upnp-org:service:ContentDirectory:1.
     * The camera drives its autofocus motor and responds with:
     *   <AFSTATUS>0</AFSTATUS> (0 = focus locked; non-zero = hunting/failed)
     *   <AF_MF>...</AF_MF>
     */
    public static void triggerAutoFocus(final String cameraIp, final AutoFocusCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                                  (activeCameraIp != null ? activeCameraIp : "192.168.107.1");

                String soapBody =
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                        "<s:Body>\r\n" +
                        "<u:MULTIAF xmlns:u=\"" + UPNP_SERVICE_CONTENT_DIR + "\" />\r\n" +
                        "</s:Body>\r\n" +
                        "</s:Envelope>\r\n";

                String soapAction = "\"" + UPNP_SERVICE_CONTENT_DIR + "#MULTIAF\"";
                String afResponse = null;

                for (UpnpEndpoint ep : activeEndpoints) {
                    String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                    String resp = sendSoapRequest(url, soapAction, soapBody, 4500);
                    if (resp != null) {
                        afResponse = resp;
                        break;
                    }
                }

                if (afResponse == null) {
                    outer:
                    for (int port : CANDIDATE_UPNP_PORTS) {
                        for (String path : CANDIDATE_CONTROL_PATHS) {
                            String url = "http://" + ip + ":" + port + path;
                            String resp = sendSoapRequest(url, soapAction, soapBody, 2500);
                            if (resp != null) {
                                afResponse = resp;
                                activeEndpoints.add(0, new UpnpEndpoint(ip, port, path, UPNP_SERVICE_CONTENT_DIR));
                                break outer;
                            }
                        }
                    }
                }

                if (afResponse != null) {
                    String unescaped = unescapeXml(afResponse);
                    String afStatus = extractXmlTag(unescaped, "AFSTATUS");
                    Log.i(TAG, "MULTIAF completed. AFSTATUS=" + afStatus);
                    if (callback != null) {
                        if ("0".equals(afStatus) || afStatus != null) {
                            callback.onAutoFocusSuccess(afStatus != null ? afStatus : "0");
                        } else {
                            callback.onAutoFocusFailed("AFSTATUS=" + afStatus);
                        }
                    }
                } else {
                    Log.w(TAG, "Camera did not respond to MULTIAF");
                    if (callback != null) {
                        callback.onAutoFocusFailed("Camera did not respond to autofocus command");
                    }
                }
            }
        }).start();
    }

    public static void triggerShutter(final String cameraIp, final ShutterCallback callback) {
        triggerShutter(cameraIp, true, callback);
    }

    /**
     * Reconstructed from Samsung LiveShutter.Shot_DSC() + LiveShutter$37 + LiveShutter$XxxThread:
     * 1. If autoFocusFirst is true, executes MULTIAF to lock focus before capture.
     * 2. Stops the live stream socket (stop_codec) and waits 250ms so the camera DSP unbinds streaming.
     * 3. Sends UPnP SOAP action "Shot" (parameterless <u:Shot/>) on urn:schemas-upnp-org:service:ContentDirectory:1.
     * 4. Parses <AFSHOTRESULT> / <AFATGPSRESULT> (FileURL) and <AVAILSHOTS> / <AVAILSHOTSRESULT>.
     * 5. Restarts the live stream automatically so the viewfinder resumes seamlessly.
     */
    public static void triggerShutter(final String cameraIp, final boolean autoFocusFirst, final ShutterCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean wasStreaming = isStreaming.get();
                final FrameCallback savedFrameCallback = lastFrameCallback;
                final String ip = (cameraIp != null && !cameraIp.isEmpty()) ? cameraIp :
                                  (activeCameraIp != null ? activeCameraIp : "192.168.107.1");

                // 1. Autofocus first if requested
                if (autoFocusFirst) {
                    Log.i(TAG, "triggerShutter: Executing pre-capture MULTIAF...");
                    String afBody =
                            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            "<s:Body>\r\n" +
                            "<u:MULTIAF xmlns:u=\"" + UPNP_SERVICE_CONTENT_DIR + "\" />\r\n" +
                            "</s:Body>\r\n" +
                            "</s:Envelope>\r\n";
                    String afAction = "\"" + UPNP_SERVICE_CONTENT_DIR + "#MULTIAF\"";
                    for (UpnpEndpoint ep : activeEndpoints) {
                        String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                        String resp = sendSoapRequest(url, afAction, afBody, 3500);
                        if (resp != null) {
                            String unescaped = unescapeXml(resp);
                            String afStatus = extractXmlTag(unescaped, "AFSTATUS");
                            Log.i(TAG, "Pre-capture MULTIAF completed. AFSTATUS=" + afStatus);
                            break;
                        }
                    }
                }

                // 2. Stop live stream before Shot (matching LiveShutter.stop_codec() before Shot_DSC())
                // Crucial: Camera RTOS cannot stream H.264/AVI and capture full sensor resolution concurrently.
                if (wasStreaming) {
                    stopLiveStream();
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                try {
                    // Exact Samsung Shot action: parameterless <u:Shot xmlns:u="..."/>
                    String soapBody =
                            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            "<s:Body>\r\n" +
                            "<u:Shot xmlns:u=\"" + UPNP_SERVICE_CONTENT_DIR + "\" />\r\n" +
                            "</s:Body>\r\n" +
                            "</s:Envelope>\r\n";

                    String soapAction = "\"" + UPNP_SERVICE_CONTENT_DIR + "#Shot\"";
                    boolean shotTriggered = false;
                    String shotResponse = null;

                    // Timeout 20s for physical sensor exposure, raw/jpeg encoding, and SD card writing
                    for (UpnpEndpoint ep : activeEndpoints) {
                        String url = "http://" + ep.host + ":" + ep.port + ep.controlPath;
                        String resp = sendSoapRequest(url, soapAction, soapBody, 20000);
                        if (resp != null) {
                            shotTriggered = true;
                            shotResponse = resp;
                            break;
                        }
                    }

                    // Fallback to candidate ports/paths if needed
                    if (!shotTriggered) {
                        outer:
                        for (int port : CANDIDATE_UPNP_PORTS) {
                            for (String path : CANDIDATE_CONTROL_PATHS) {
                                String url = "http://" + ip + ":" + port + path;
                                String resp = sendSoapRequest(url, soapAction, soapBody, 10000);
                                if (resp != null) {
                                    shotTriggered = true;
                                    shotResponse = resp;
                                    activeEndpoints.add(0, new UpnpEndpoint(ip, port, path, UPNP_SERVICE_CONTENT_DIR));
                                    break outer;
                                }
                            }
                        }
                    }

                    if (shotTriggered) {
                        if (shotResponse != null) {
                            String unescaped = unescapeXml(shotResponse);
                            String fileUrl = extractXmlTag(unescaped, "AFSHOTRESULT");
                            if (fileUrl == null || fileUrl.trim().isEmpty()) {
                                fileUrl = extractXmlTag(unescaped, "AFATGPSRESULT");
                            }
                            if (fileUrl != null && !fileUrl.trim().isEmpty()) {
                                lastCapturedFileUrl = fileUrl.trim();
                                Log.i(TAG, "Shot returned FileURL=" + lastCapturedFileUrl);
                                downloadCapturedThumbnailOrPhoto(lastCapturedFileUrl);
                            }
                            String avail = extractXmlTag(unescaped, "AVAILSHOTS");
                            if (avail == null || avail.trim().isEmpty()) {
                                avail = extractXmlTag(unescaped, "AVAILSHOTSRESULT");
                            }
                            if (avail != null && !avail.trim().isEmpty()) {
                                availableShots = avail.trim();
                            }
                        }
                        if (callback != null) callback.onSuccess();
                    } else {
                        if (callback != null) callback.onError("Camera did not respond to Shot command");
                    }
                } finally {
                    // Restart live stream automatically after Shot completes (matching LiveShutter.GetLiveStream())
                    if (wasStreaming && savedFrameCallback != null) {
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        startLiveStream(ip, savedFrameCallback);
                    }
                }
            }
        }).start();
    }

    private static final int MAX_THUMBNAIL_BYTES = 15 * 1024 * 1024;

    /**
     * Downloads the captured thumbnail (FileURL + "_SM") or photo returned in <AFATGPSRESULT>,
     * matching LiveShutter.Thumbnaildown().
     */
    private static void downloadCapturedThumbnailOrPhoto(String fileUrl) {
        Socket sock = null;
        try {
            String thumbUrl = fileUrl.endsWith("_SM") ? fileUrl : (fileUrl + "_SM");
            if (!isSafeCameraUrl(thumbUrl)) {
                return;
            }
            URL u = new URL(thumbUrl);
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : 80;
            String path = u.getFile();

            sock = new Socket();
            if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= 21) {
                try { activeWifiNetwork.bindSocket(sock); } catch (Exception ignored) {}
            }
            sock.setSoTimeout(5000);
            sock.connect(new InetSocketAddress(host, port), 3000);

            String req = "GET " + path + " HTTP/1.1\r\n" +
                         "Host: " + host + ":" + port + "\r\n" +
                         "User-Agent: " + getUserAgent() + "\r\n" +
                         "Connection: close\r\n\r\n";
            OutputStream out = sock.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = sock.getInputStream();
            HttpStreamHeader hdr = readHttpStreamHeader(in);
            if (hdr.statusCode >= 200 && hdr.statusCode < 300) {
                ByteArrayOutputStream imgBytes = new ByteArrayOutputStream(65536);
                byte[] buf = new byte[8192];
                int n;
                int totalRead = 0;
                while ((n = in.read(buf)) != -1) {
                    totalRead += n;
                    if (totalRead > MAX_THUMBNAIL_BYTES) {
                        throw new IOException("Thumbnail response exceeded maximum size limit");
                    }
                    imgBytes.write(buf, 0, n);
                }
                byte[] data = imgBytes.toByteArray();
                if (data.length > 0 && appContext != null) {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NX_MobileLink");
                    if (!dir.exists()) dir.mkdirs();
                    File outFile = new File(dir, "NX_RVF_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(data);
                    }
                    Log.i(TAG, "Saved RVF preview image to " + outFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Thumbnail download notice: " + e.getMessage());
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
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

    /**
     * Sends a UPnP SOAP HTTP/1.0 POST request using HumbleHttpClient (raw Wi-Fi bound socket)
     * with the exact User-Agent: SEC_RVF_<MAC>.
     */
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

    public static void disconnect() {
        stopLiveStream();
        lastFrameCallback = null;
        activeEndpoints.clear();
        activeStreamUrl = null;
    }

    public static void disconnect(Object owner) {
        disconnect();
    }
}
