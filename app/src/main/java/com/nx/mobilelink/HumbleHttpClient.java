package com.nx.mobilelink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Direct port and enhancement of Samsung's com.samsungimaging.filesharing.HumbleHttpClient.
 * 
 * WHY THIS IS CRITICAL FOR SAMSUNG NX CAMERAS:
 * Standard Android HttpURLConnection uses HTTP/1.1 with persistent keep-alive
 * socket connection pooling. Samsung NX VxWorks RTOS HTTP server has a tiny
 * internal socket table (max 3-4 concurrent sockets). Pooling connections or sending
 * complex HTTP/1.1 headers rapidly exhausts the camera's socket descriptors,
 * causing the camera to drop Wi-Fi with "Network disconnected" (הרשת התנתקה)
 * after downloading exactly 3 photos!
 * 
 * HumbleHttpClient uses raw TCP sockets with strict HTTP/1.0 and immediate
 * socket closure upon stream completion, guaranteeing zero socket exhaustion.
 */
public class HumbleHttpClient {
    private static final String TAG = "HumbleHttpClient";

    private Socket sock;
    private InputStream is;
    private OutputStream os;
    private int contentLength = -1;
    private String contentType = null;
    private int responseCode = -1;
    private final Map<String, String> headers = Collections.synchronizedMap(new HashMap<String, String>());

    public interface ProgressCallback {
        void onProgress(long bytesRead, long totalBytes, int percent);
    }

    public HumbleHttpClient() {
    }

    public synchronized void close() {
        if (is != null) {
            try { is.close(); } catch (Throwable ignored) {}
            is = null;
        }
        if (os != null) {
            try { os.close(); } catch (Throwable ignored) {}
            os = null;
        }
        if (sock != null) {
            try { sock.close(); } catch (Throwable ignored) {}
            sock = null;
        }
        contentLength = -1;
        contentType = null;
        headers.clear();
    }

    /**
     * Executes a raw HTTP/1.0 GET request matching Samsung's HumbleHttpClient.
     * Reads headers and returns the raw body InputStream.
     * The caller MUST call close() when finished reading the stream!
     */
    public InputStream request(Network network, String strUrl, String userAgent, int timeoutMs) throws Exception {
        close();

        URL url = new URL(strUrl);
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : 80;
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";
        if (url.getQuery() != null && !url.getQuery().isEmpty()) {
            path += "?" + url.getQuery();
        }

        sock = new Socket();
        if (network == null) {
            network = NetworkManager.getActiveWifiNetwork();
        }
        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                network.bindSocket(sock);
            } catch (Exception e) {
                Log.w(TAG, "Could not bind socket to network: " + e.getMessage());
            }
        }

        sock.connect(new InetSocketAddress(host, port), Math.max(timeoutMs, 5000));
        sock.setSoTimeout(Math.max(timeoutMs, 15000));

        os = sock.getOutputStream();
        is = sock.getInputStream();

        // Format exact HTTP/1.0 request as Samsung did
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.0\r\n");
        sb.append("Host: ").append(host).append(":").append(port).append("\r\n");
        if (userAgent != null && !userAgent.isEmpty()) {
            sb.append("User-Agent: ").append(userAgent).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");

        byte[] reqBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        os.write(reqBytes);
        os.flush();

        // Read response headers byte-by-byte to avoid buffering past header boundary
        parseHeaders(is);

        if (responseCode != 200 && responseCode != 206) {
            close();
            throw new Exception("HTTP server returned status: " + responseCode);
        }

        return is;
    }

    private void parseHeaders(InputStream in) throws Exception {
        // Read header lines byte-by-byte
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new Exception("Empty response from camera");
        }

        String[] statusParts = statusLine.split(" ");
        if (statusParts.length >= 2) {
            try {
                responseCode = Integer.parseInt(statusParts[1].trim());
            } catch (Exception ignored) {
                responseCode = 200;
            }
        } else {
            responseCode = 200;
        }

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                headers.put(key.toLowerCase(), val);

                if ("content-type".equalsIgnoreCase(key)) {
                    contentType = val;
                } else if ("content-length".equalsIgnoreCase(key)) {
                    try {
                        contentLength = Integer.parseInt(val);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static final int MAX_HEADER_LINE_LENGTH = 8192;

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                continue; // ignore CR
            }
            if (b == '\n') {
                break; // LF ends line
            }
            baos.write(b);
            if (baos.size() > MAX_HEADER_LINE_LENGTH) {
                throw new java.io.IOException("HTTP header line exceeded maximum length (" + MAX_HEADER_LINE_LENGTH + " bytes)");
            }
        }
        if (b == -1 && baos.size() == 0) {
            return null;
        }
        return baos.toString("US-ASCII");
    }

    public int getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public int getResponseCode() {
        return responseCode;
    }

    /**
     * Downloads full photo directly to an OutputStream with progress reporting.
     * Ensures immediate socket closure upon completion or error.
     */
    public static void download(Network network, String urlStr, String userAgent, OutputStream out, ProgressCallback callback) throws Exception {
        HumbleHttpClient client = new HumbleHttpClient();
        try {
            InputStream in = client.request(network, urlStr, userAgent, 30000);
            long totalBytes = client.getContentLength();
            byte[] buf = new byte[32 * 1024];
            long totalRead = 0;
            int len;

            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                totalRead += len;
                if (callback != null) {
                    int percent = (totalBytes > 0) ? (int) ((totalRead * 100) / totalBytes) : -1;
                    callback.onProgress(totalRead, totalBytes, percent);
                }
            }
            out.flush();
        } finally {
            client.close();
        }
    }

    /**
     * Downloads a thumbnail directly to a Bitmap with zero lingering sockets.
     */
    public static Bitmap downloadBitmap(Network network, String urlStr, String userAgent) {
        HumbleHttpClient client = new HumbleHttpClient();
        try {
            InputStream in = client.request(network, urlStr, userAgent, 8000);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            byte[] bytes = baos.toByteArray();
            if (bytes.length > 0) {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed downloading bitmap from " + urlStr + ": " + e.getMessage());
        } finally {
            client.close();
        }
        return null;
    }
}
