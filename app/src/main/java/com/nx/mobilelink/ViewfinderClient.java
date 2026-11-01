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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated, isolated client for Samsung NX Remote Viewfinder mode.
 * Operates on Mode Control (port 7788), callback server (port 7788/7792),
 * and live stream pipeline (port 7679).
 * Completely decoupled from MobileLink.
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

    private static Context appContext;
    private static Network activeWifiNetwork;
    private static String userAgentRvf;
    private static LocalRvfServer localServer;
    private static ScheduledExecutorService keepAliveExecutor;
    private static final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static Thread streamThread;

    public static void setActiveWifiNetwork(Network network) {
        activeWifiNetwork = network;
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
        if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Socket s = new Socket();
            activeWifiNetwork.bindSocket(s);
            return s;
        }
        return new Socket();
    }

    private static HttpURLConnection openConnection(URL url) throws Exception {
        HttpURLConnection conn;
        if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            conn = (HttpURLConnection) activeWifiNetwork.openConnection(url);
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
                final String cameraIp = (customIp != null && !customIp.isEmpty()) ? customIp : getWifiGatewayIp(context);
                final String ua = getUserAgent(context);
                final String rawMac = ua.replace("SEC_RVF_", "");
                final String localIp = getLocalIpAddress(context);

                startLocalServer();
                int localPort = (localServer != null) ? localServer.getPort() : 7788;

                Log.i(TAG, "Connecting to RVF camera at " + cameraIp + ", phone=" + localIp + ":" + localPort);

                boolean handshook = false;

                try {
                    String req7788 = "GET /mode/control HTTP/1.0\r\n" +
                            "HOST: " + cameraIp + ":7788\r\n" +
                            "User-Agent: " + ua + "\r\n" +
                            "HOST-Mac: " + rawMac + "\r\n" +
                            "HOST-Address: " + localIp + "\r\n" +
                            "HOST-port: " + localPort + "\r\n" +
                            "Access-Method: manual\r\n" +
                            "Connection: close\r\n\r\n";
                    sendRawHttpRequest(cameraIp, 7788, req7788);
                    handshook = true;
                    Log.i(TAG, "Handshake to 7788 succeeded");
                } catch (Exception e) {
                    Log.w(TAG, "Handshake to 7788 warning: " + e.getMessage());
                }

                try {
                    DatagramSocket udpSock = new DatagramSocket();
                    if (activeWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try { activeWifiNetwork.bindSocket(udpSock); } catch (Exception ignored) {}
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

                for (int p : new int[]{7676, 52235, 80}) {
                    try {
                        String probe = "GET /smp_2_ HTTP/1.0\r\n" +
                                "HOST: " + cameraIp + ":" + p + "\r\n" +
                                "User-Agent: " + ua + "\r\n" +
                                "Connection: close\r\n\r\n";
                        sendRawHttpRequest(cameraIp, p, probe);
                    } catch (Exception ignored) {}
                }

                startKeepAlive(cameraIp, ua, rawMac, localIp, localPort);

                if (handshook || isPortOpen(cameraIp, 7679, 1500) || isPortOpen(cameraIp, 7788, 1500)) {
                    callback.onConnected(cameraIp);
                } else {
                    callback.onConnectionFailed("לא ניתן להתחבר למצלמה בכתובת " + cameraIp + ". ודא שהמצלמה במצב Remote Viewfinder.");
                }
            }
        }).start();
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

    private static void sendRawHttpRequest(String host, int port, String req) throws Exception {
        Socket s = null;
        try {
            s = createSocket();
            s.connect(new InetSocketAddress(host, port), 1200);
            s.setSoTimeout(1500);
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
                try {
                    String ping = "GET /mode/control HTTP/1.0\r\n" +
                            "HOST: " + cameraIp + ":7788\r\n" +
                            "User-Agent: " + ua + "\r\n" +
                            "HOST-Mac: " + rawMac + "\r\n" +
                            "HOST-Address: " + localIp + "\r\n" +
                            "HOST-port: " + localPort + "\r\n" +
                            "Connection: close\r\n\r\n";
                    sendRawHttpRequest(cameraIp, 7788, ping);
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

    public static void startLiveStream(final String cameraIp, final FrameCallback callback) {
        if (isStreaming.get()) {
            stopLiveStream();
        }
        isStreaming.set(true);

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String[] candidatePaths = new String[]{
                        "/livestream.avi",
                        "/liveview",
                        "/liveview.mjpg",
                        "/stream.mjpg"
                };

                for (String path : candidatePaths) {
                    if (!isStreaming.get()) break;

                    String streamUrl = "http://" + cameraIp + ":7679" + path;
                    Log.i(TAG, "Attempting RVF live stream at: " + streamUrl);

                    HttpURLConnection conn = null;
                    InputStream in = null;
                    try {
                        URL url = new URL(streamUrl);
                        conn = openConnection(url);
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(5000);
                        conn.setRequestProperty("User-Agent", getUserAgent(appContext));
                        conn.setRequestProperty("Connection", "keep-alive");
                        conn.connect();

                        int code = conn.getResponseCode();
                        Log.i(TAG, "Stream response code: " + code);
                        if (code == 200) {
                            in = new BufferedInputStream(conn.getInputStream(), 16384);
                            readMjpegStream(in, callback);
                            return;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Stream path " + path + " failed: " + e.getMessage());
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Exception ignored) {}
                        }
                        if (conn != null) {
                            try { conn.disconnect(); } catch (Exception ignored) {}
                        }
                    }
                }

                if (isStreaming.get()) {
                    callback.onStreamError("לא הצלחנו לפתוח שידור חי מפורט 7679. ודא שהמצלמה במצב Remote Viewfinder.");
                }
            }
        });
        streamThread.start();
    }

    private static void readMjpegStream(InputStream in, FrameCallback callback) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
        byte[] readBuf = new byte[4096];
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
                Log.w(TAG, "Live stream read interrupted: " + e.getMessage());
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

    public static void triggerShutter(final String cameraIp, final ShutterCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String ua = getUserAgent(appContext);
                    String[] captureRequests = new String[]{
                            "GET /mode/control?action=capture HTTP/1.0\r\nHOST: " + cameraIp + ":7788\r\nUser-Agent: " + ua + "\r\nConnection: close\r\n\r\n",
                            "POST /capture HTTP/1.0\r\nHOST: " + cameraIp + ":7679\r\nUser-Agent: " + ua + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                            "GET /shutter HTTP/1.0\r\nHOST: " + cameraIp + ":7679\r\nUser-Agent: " + ua + "\r\nConnection: close\r\n\r\n"
                    };

                    boolean sent = false;
                    for (String req : captureRequests) {
                        try {
                            int port = req.contains(":7788") ? 7788 : 7679;
                            sendRawHttpRequest(cameraIp, port, req);
                            sent = true;
                        } catch (Exception ignored) {}
                    }

                    if (sent) {
                        callback.onSuccess();
                    } else {
                        callback.onError("פקודת הצילום נכשלה");
                    }
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    private static synchronized void startLocalServer() {
        if (localServer == null || !localServer.isAlive()) {
            localServer = new LocalRvfServer();
            localServer.start();
        }
    }

    public static synchronized void disconnect() {
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
