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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated, isolated client for Samsung NX Remote Viewfinder mode.
 * Reverse-engineered directly from Samsung's official Remote Viewfinder APK:
 * 
 * 1. UPnP Control Protocol:
 *    - Connects using UPnP SOAP action 'GetInfomation' on MediaServer/ConnectionManager.
 *    - The camera responds with <GETIPRESULT> containing the live stream URL (e.g. port 7679/livestream.avi).
 *    - Receiving 'GetInfomation' updates the camera LCD to "Connected" and PREVENTS auto-shutdown!
 * 2. Shutter Control:
 *    - Triggers hardware autofocus & capture using UPnP SOAP action 'Shot' (or 'ShotWithGPS').
 * 3. Video Pipeline:
 *    - Continuous MJPEG / AVI JPEG frame stream parser on port 7679 with auto-reconnect.
 * 4. Mode Control:
 *    - Sends SEC_RVF handshake on port 7788 with local callback server on port 7788/7792.
 */
public class ViewfinderClient {
    private static final String TAG = "ViewfinderClient";

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
    }

    private static Context appContext;
    private static Network activeWifiNetwork;
    private static String userAgentRvf;
    private static LocalRvfServer localServer;
    private static ScheduledExecutorService keepAliveExecutor;
    private static final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static Thread streamThread;

    // Active session parameters
    private static String activeCameraIp;
    private static String activeStreamUrl;
    private static String activeControlUrl;
    private static String activeServiceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final List<ServiceEndpoint> activeEndpoints = new ArrayList<>();

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

    public static synchronized String getUserAgent(Context context) {
        if (userAgentRvf != null && userAgentRvf.length() == 20) {
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

                final List<String> candidateIps = new ArrayList<>();
                if (customIp != null && !customIp.trim().isEmpty()) {
                    candidateIps.add(customIp.trim());
                }
                List<String> discovered = NetworkManager.getCandidateCameraIps(appContext);
                for (String ip : discovered) {
                    if (!candidateIps.contains(ip)) candidateIps.add(ip);
                }

                // Detect active camera IP
                String cameraIp = null;
                for (String ip : candidateIps) {
                    if (isPortOpen(ip, 7679, 400) || isPortOpen(ip, 7788, 400) || isPortOpen(ip, 52235, 400)) {
                        cameraIp = ip;
                        Log.i(TAG, "Detected active RVF camera IP: " + cameraIp);
                        break;
                    }
                }
                if (cameraIp == null && !candidateIps.isEmpty()) {
                    cameraIp = candidateIps.get(0);
                }
                activeCameraIp = cameraIp;

                final String ua = getUserAgent(appContext);
                final String rawMac = ua.replace("SEC_RVF_", "");
                final String localIp = NetworkManager.getLocalIpAddress(appContext);

                startLocalServer();
                int localPort = (localServer != null) ? localServer.getPort() : 7788;

                Log.i(TAG, "Connecting to RVF camera at " + cameraIp + ", phone=" + localIp + ":" + localPort);

                // 1. Send Samsung Mode Control handshake to port 7788 (TCP + UDP)
                try {
                    String req7788 = "GET /mode/control HTTP/1.0\r\n" +
                            "HOST: " + cameraIp + ":7788\r\n" +
                            "User-Agent: " + ua + "\r\n" +
                            "HOST-Mac: " + rawMac + "\r\n" +
                            "HOST-Address: " + localIp + "\r\n" +
                            "HOST-port: " + localPort + "\r\n" +
                            "Access-Method: manual\r\n" +
                            "Connection: close\r\n\r\n";
                    sendRawHttpRequest(cameraIp, 7788, req7788, 800);
                    Log.i(TAG, "Handshake to 7788 succeeded");
                } catch (Exception e) {
                    Log.w(TAG, "Handshake to 7788 warning: " + e.getMessage());
                }

                try {
                    DatagramSocket udpSock = new DatagramSocket();
                    Network net = NetworkManager.getActiveWifiNetwork();
                    if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try { net.bindSocket(udpSock); } catch (Exception ignored) {}
                    }
                    String udpMsg = "GET /mode/control HTTP/1.0\r\n" +
                            "HOST: " + cameraIp + ":7788\r\n" +
                            "User-Agent: " + ua + "\r\n" +
                            "HOST-Mac: " + rawMac + "\r\n" +
                            "HOST-Address: " + localIp + "\r\n" +
                            "HOST-port: " + localPort + "\r\n\r\n";
                    byte[] b = udpMsg.getBytes(StandardCharsets.UTF_8);
                    udpSock.send(new DatagramPacket(b, b.length, InetAddress.getByName(cameraIp), 7788));
                    udpSock.close();
                } catch (Exception ignored) {}

                // 2. Discover UPnP Description and Control Endpoints
                discoverEndpoints(cameraIp);

                // 3. Send UPnP SOAP action 'GetInfomation' (This confirms connection on camera LCD!)
                boolean upnpConfirmed = executeGetInformation(cameraIp);

                // 4. Default stream URL fallback
                if (activeStreamUrl == null || activeStreamUrl.isEmpty()) {
                    activeStreamUrl = "http://" + cameraIp + ":7679/livestream.avi";
                }

                // 5. Start continuous keep-alive ping loop
                startKeepAlive(cameraIp, ua, rawMac, localIp, localPort);

                boolean port7679Open = isPortOpen(cameraIp, 7679, 1000);
                boolean port7788Open = isPortOpen(cameraIp, 7788, 1000);

                if (upnpConfirmed || port7679Open || port7788Open) {
                    Log.i(TAG, "Remote Viewfinder connected! StreamURL=" + activeStreamUrl + ", ControlURL=" + activeControlUrl);
                    callback.onConnected(cameraIp);
                } else {
                    callback.onConnectionFailed("Could not connect to camera at " + cameraIp + ". Please verify camera is in Remote Viewfinder mode.");
                }
            }
        }).start();
    }

    /**
     * Discovers UPnP services by probing standard description XML locations.
     */
    private static void discoverEndpoints(String cameraIp) {
        activeEndpoints.clear();
        int[] ports = new int[]{52235, 7676};
        String[] paths = new String[]{
                "/description.xml",
                "/smp_10_",
                "/dmr/SamsungMRDesc.xml"
        };

        for (int p : ports) {
            if (!isPortOpen(cameraIp, p, 300)) continue;
            for (String path : paths) {
                String testUrl = "http://" + cameraIp + ":" + p + path;
                try {
                    URL u = new URL(testUrl);
                    HttpURLConnection conn = openConnection(u);
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(2000);
                    conn.setRequestProperty("User-Agent", getUserAgent(appContext));
                    conn.setRequestProperty("Connection", "close");
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int len;
                        while ((len = in.read(buf)) != -1) baos.write(buf, 0, len);
                        in.close();
                        conn.disconnect();

                        String xml = baos.toString("UTF-8");
                        parseEndpointsFromXml(cameraIp, p, xml);
                        if (!activeEndpoints.isEmpty()) {
                            Log.i(TAG, "Discovered " + activeEndpoints.size() + " UPnP endpoints from " + testUrl);
                            return;
                        }
                    } else {
                        conn.disconnect();
                    }
                } catch (Exception ignored) {}
            }
        }

        // Default fallbacks if XML probing did not return
        ServiceEndpoint ep1 = new ServiceEndpoint();
        ep1.serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
        ep1.controlUrl = "http://" + cameraIp + ":52235/upnp/control/ConnectionManager";
        activeEndpoints.add(ep1);

        ServiceEndpoint ep2 = new ServiceEndpoint();
        ep2.serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1";
        ep2.controlUrl = "http://" + cameraIp + ":52235/upnp/control/ContentDirectory";
        activeEndpoints.add(ep2);

        ServiceEndpoint ep3 = new ServiceEndpoint();
        ep3.serviceType = "urn:schemas-samsung-com:service:RemoteControl:1";
        ep3.controlUrl = "http://" + cameraIp + ":52235/upnp/control/RemoteControl";
        activeEndpoints.add(ep3);

        ServiceEndpoint ep4 = new ServiceEndpoint();
        ep4.serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1";
        ep4.controlUrl = "http://" + cameraIp + ":7676/upnp/control/ConnectionManager";
        activeEndpoints.add(ep4);
    }

    private static void parseEndpointsFromXml(String cameraIp, int port, String xml) {
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
                    if (cur != null && !text.isEmpty()) {
                        if ("serviceType".equalsIgnoreCase(curTag)) {
                            cur.serviceType = text;
                        } else if ("controlURL".equalsIgnoreCase(curTag)) {
                            cur.controlUrl = text.startsWith("http") ? text : (baseUrl + (text.startsWith("/") ? text : "/" + text));
                        } else if ("SCPDURL".equalsIgnoreCase(curTag)) {
                            cur.scpdUrl = text.startsWith("http") ? text : (baseUrl + (text.startsWith("/") ? text : "/" + text));
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("service".equalsIgnoreCase(parser.getName()) && cur != null) {
                        if (cur.controlUrl != null) {
                            activeEndpoints.add(cur);
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
    }

    /**
     * Executes UPnP SOAP action 'GetInfomation' (spelled GetInfomation or GetInformation).
     * Extracts StreamURL from <GETIPRESULT> and confirms session on camera LCD.
     */
    private static boolean executeGetInformation(String cameraIp) {
        String[] actionNames = new String[]{"GetInfomation", "GetInformation"};

        for (ServiceEndpoint ep : activeEndpoints) {
            for (String action : actionNames) {
                try {
                    String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            "  <s:Body>\r\n" +
                            "    <u:" + action + " xmlns:u=\"" + ep.serviceType + "\">\r\n" +
                            "    </u:" + action + ">\r\n" +
                            "  </s:Body>\r\n" +
                            "</s:Envelope>";

                    String resp = postSoap(ep.controlUrl, ep.serviceType, action, soap, 2500);
                    if (resp != null && (resp.contains("GETINFORMATIONRESULT") || resp.contains("GETIPRESULT") || resp.contains("200 OK") || resp.contains("livestream"))) {
                        activeControlUrl = ep.controlUrl;
                        activeServiceType = ep.serviceType;
                        Log.i(TAG, "GetInfomation SUCCESS on " + ep.controlUrl);

                        // Extract <GETIPRESULT>
                        Pattern p = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?GETIPRESULT\\b[^>]*>(.*?)</(?:[a-zA-Z0-9_-]+:)?GETIPRESULT>", Pattern.CASE_INSENSITIVE);
                        Matcher m = p.matcher(resp);
                        if (m.find()) {
                            String urlFound = m.group(1).trim();
                            if (urlFound.startsWith("http")) {
                                activeStreamUrl = urlFound;
                                Log.i(TAG, "Parsed StreamURL from camera: " + activeStreamUrl);
                            }
                        }
                        return true;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "GetInfomation on " + ep.controlUrl + " returned: " + e.getMessage());
                }
            }
        }

        // If activeControlUrl not set yet, pick first valid endpoint
        if (!activeEndpoints.isEmpty()) {
            activeControlUrl = activeEndpoints.get(0).controlUrl;
            activeServiceType = activeEndpoints.get(0).serviceType;
        } else {
            activeControlUrl = "http://" + cameraIp + ":52235/upnp/control/ConnectionManager";
        }
        return false;
    }

    private static String postSoap(String urlStr, String serviceType, String action, String soapBody, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = openConnection(url);
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
            conn.setRequestProperty("User-Agent", getUserAgent(appContext));
            conn.setRequestProperty("Connection", "close");

            byte[] b = soapBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(b.length));

            OutputStream out = conn.getOutputStream();
            out.write(b);
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int len;
                while ((len = in.read(buf)) != -1) baos.write(buf, 0, len);
                in.close();
                return baos.toString("UTF-8");
            }
            return null;
        } finally {
            conn.disconnect();
        }
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

    private static void sendRawHttpRequest(String host, int port, String req, int timeoutMs) throws Exception {
        Socket s = null;
        try {
            s = createSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream os = s.getOutputStream();
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();

            InputStream in = s.getInputStream();
            byte[] buf = new byte[256];
            in.read(buf);
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static synchronized void startKeepAlive(final String cameraIp, final String ua, final String rawMac, final String localIp, final int localPort) {
        stopKeepAlive();
        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor();
        keepAliveExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                // 1. UPnP GetInfomation ping (Keeps camera from LCD disconnect timeout)
                if (activeControlUrl != null && activeServiceType != null) {
                    try {
                        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                                "  <s:Body>\r\n" +
                                "    <u:GetInfomation xmlns:u=\"" + activeServiceType + "\"/>\r\n" +
                                "  </s:Body>\r\n" +
                                "</s:Envelope>";
                        postSoap(activeControlUrl, activeServiceType, "GetInfomation", soap, 1500);
                    } catch (Exception ignored) {}
                }

                // 2. Port 7788 Mode Control keep-alive ping
                try {
                    String ping = "GET /mode/control HTTP/1.0\r\n" +
                            "HOST: " + cameraIp + ":7788\r\n" +
                            "User-Agent: " + ua + "\r\n" +
                            "HOST-Mac: " + rawMac + "\r\n" +
                            "HOST-Address: " + localIp + "\r\n" +
                            "HOST-port: " + localPort + "\r\n" +
                            "Connection: close\r\n\r\n";
                    sendRawHttpRequest(cameraIp, 7788, ping, 1000);
                } catch (Exception ignored) {}
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    public static synchronized void stopKeepAlive() {
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            keepAliveExecutor = null;
        }
    }

    /**
     * Starts live video stream with continuous auto-reconnection loop.
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
                    HttpURLConnection conn = null;
                    InputStream in = null;
                    try {
                        URL url = new URL(targetStreamUrl);
                        conn = openConnection(url);
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(12000); // 12s read timeout so slow frames don't drop stream
                        conn.setRequestProperty("User-Agent", getUserAgent(appContext));
                        conn.setRequestProperty("Connection", "keep-alive");
                        conn.connect();

                        int code = conn.getResponseCode();
                        Log.i(TAG, "Stream response code: " + code);
                        if (code == 200) {
                            in = new BufferedInputStream(conn.getInputStream(), 32768);
                            readMjpegStream(in, callback);
                        }
                    } catch (Exception e) {
                        if (isStreaming.get()) {
                            Log.w(TAG, "Stream connection error: " + e.getMessage());
                        }
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Exception ignored) {}
                        }
                        if (conn != null) {
                            try { conn.disconnect(); } catch (Exception ignored) {}
                        }
                    }

                    // If still streaming, brief pause before auto-reconnect
                    if (isStreaming.get()) {
                        try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                    }
                }

                Log.i(TAG, "Live stream pipeline stopped");
            }
        }, "NX-RVF-StreamThread");
        streamThread.start();
    }

    private static void readMjpegStream(InputStream in, FrameCallback callback) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
        byte[] readBuf = new byte[8192];
        int prev = -1;
        boolean inFrame = false;

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
                        if (prev == 0xFF && b == 0xD9) {
                            inFrame = false;
                            byte[] frameData = buffer.toByteArray();
                            if (frameData.length > 512) {
                                Bitmap bmp = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
                                if (bmp != null) {
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
    }

    public static void stopLiveStream() {
        isStreaming.set(false);
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
    }

    /**
     * Triggers autofocus & shutter capture using Samsung's official UPnP 'Shot' action!
     */
    public static void triggerShutter(final String cameraIp, final ShutterCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean success = false;
                    String ua = getUserAgent(appContext);

                    // 1. Official UPnP 'Shot' and 'ShotWithGPS' action
                    String[] shutterActions = new String[]{"Shot", "ShotWithGPS"};
                    for (ServiceEndpoint ep : activeEndpoints) {
                        for (String act : shutterActions) {
                            try {
                                String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                                        "  <s:Body>\r\n" +
                                        "    <u:" + act + " xmlns:u=\"" + ep.serviceType + "\">\r\n" +
                                        "    </u:" + act + ">\r\n" +
                                        "  </s:Body>\r\n" +
                                        "</s:Envelope>";

                                String resp = postSoap(ep.controlUrl, ep.serviceType, act, soap, 3000);
                                if (resp != null && (resp.contains("200 OK") || resp.contains("ShotResponse") || resp.contains("ShotWithGPSResponse"))) {
                                    Log.i(TAG, "UPnP Shot action SUCCESS on " + ep.controlUrl);
                                    success = true;
                                    break;
                                }
                            } catch (Exception e) {
                                Log.d(TAG, "UPnP Shot on " + ep.controlUrl + " returned: " + e.getMessage());
                            }
                        }
                        if (success) break;
                    }

                    // 2. Mode Control capture requests on port 7788 and 7679 as backup
                    String[] captureRequests = new String[]{
                            "GET /mode/control?action=capture HTTP/1.0\r\nHOST: " + cameraIp + ":7788\r\nUser-Agent: " + ua + "\r\nConnection: close\r\n\r\n",
                            "POST /capture HTTP/1.0\r\nHOST: " + cameraIp + ":7679\r\nUser-Agent: " + ua + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                            "GET /shutter HTTP/1.0\r\nHOST: " + cameraIp + ":7679\r\nUser-Agent: " + ua + "\r\nConnection: close\r\n\r\n"
                    };

                    for (String req : captureRequests) {
                        try {
                            int port = req.contains(":7788") ? 7788 : 7679;
                            sendRawHttpRequest(cameraIp, port, req, 1200);
                            success = true;
                        } catch (Exception ignored) {}
                    }

                    if (success) {
                        callback.onSuccess();
                    } else {
                        callback.onError("Camera did not acknowledge capture command");
                    }
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    private static synchronized void startLocalServer() {
        if (localServer == null) {
            try {
                localServer = new LocalRvfServer();
                localServer.start();
            } catch (Exception e) {
                Log.w(TAG, "Could not start LocalRvfServer: " + e.getMessage());
            }
        }
    }

    public static void disconnect() {
        stopLiveStream();
        stopKeepAlive();
        if (localServer != null) {
            localServer.shutdown();
            localServer = null;
        }
    }

    private static class LocalRvfServer extends Thread {
        private ServerSocket serverSocket;
        private int boundPort = 7788;
        private volatile boolean running = true;

        public LocalRvfServer() {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(7788));
                boundPort = 7788;
                Log.i(TAG, "LocalRvfServer bound to port 7788");
            } catch (Exception e1) {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(7792));
                    boundPort = 7792;
                    Log.i(TAG, "LocalRvfServer bound to fallback port 7792");
                } catch (Exception e2) {
                    try {
                        serverSocket = new ServerSocket(0);
                        boundPort = serverSocket.getLocalPort();
                        Log.i(TAG, "LocalRvfServer bound to dynamic port " + boundPort);
                    } catch (Exception ignored) {}
                }
            }
        }

        public int getPort() {
            return boundPort;
        }

        @Override
        public void run() {
            while (running && serverSocket != null && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (Exception ignored) {}
            }
        }

        private void handleClient(final Socket client) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.setSoTimeout(2000);
                        InputStream in = client.getInputStream();
                        byte[] buf = new byte[1024];
                        in.read(buf);

                        OutputStream os = client.getOutputStream();
                        String resp = "HTTP/1.1 200 OK\r\n" +
                                "Server: Linux/2.6 UPnP/1.0 SEC_RVF/1.0\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n";
                        os.write(resp.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        client.close();
                    } catch (Exception ignored) {
                        try { client.close(); } catch (Exception ignored2) {}
                    }
                }
            }).start();
        }

        public void shutdown() {
            running = false;
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
            interrupt();
        }
    }
}
