/*
 * Copyright (c) 2026 FL4K901. All rights reserved.
 * SPDX-License-Identifier: MIT
 *
 * This software is licensed under the MIT License.
 * See LICENSE file in the project root for full license terms.
 */

package com.nx.mobilelink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw HTTP/1.0 and UPnP SOAP client reconstructed from CyberGarage's
 * org.cybergarage.http.HTTPRequest, org.cybergarage.xml.Parser, and
 * org.cybergarage.soap.SOAPRequest in mobilelink-1-7-13.apk and
 * remote-viewfinder-2-2-11-multi-android.apk.
 *
 * WHY THIS IS CRITICAL FOR SAMSUNG NX1000 CAMERAS:
 * 1. Standard Android HttpURLConnection forces HTTP/1.1 with gzip and connection
 *    pooling. Samsung NX1000's embedded RTOS HTTP/UPnP server expects HTTP/1.0
 *    requests with explicit User-Agent (SEC_DSC_<MAC> or SEC_RVF_<MAC>),
 *    HOST: <host>:<port>, and Content-Length headers.
 * 2. Respecting Content-Length and closing sockets immediately prevents
 *    exhausting the camera's 3-4 socket descriptor limit.
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
     * Executes a raw HTTP/1.0 GET request matching CyberGarage's HTTPRequest / Parser.parse:
     *   GET <path> HTTP/1.0
     *   User-Agent: <userAgent>
     *   HOST: <host>:<port>
     *   Content-Length: 0
     *
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

        sock.connect(new InetSocketAddress(host, port), Math.max(timeoutMs, 2500));
        sock.setSoTimeout(Math.max(timeoutMs, 5000));

        os = sock.getOutputStream();
        is = sock.getInputStream();

        // Exact CyberGarage HTTP/1.0 GET header order from org.cybergarage.xml.Parser.parse
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.0\r\n");
        if (userAgent != null && !userAgent.isEmpty()) {
            sb.append("User-Agent: ").append(userAgent).append("\r\n");
        }
        sb.append("HOST: ").append(host).append(":").append(port).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        byte[] reqBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        os.write(reqBytes);
        os.flush();

        parseHeaders(is);

        if (responseCode != 200 && responseCode != 206) {
            close();
            throw new Exception("HTTP server returned status: " + responseCode);
        }

        return is;
    }

    /**
     * Fetches UPnP Device Description XML or Service SCPD XML using the exact
     * dual-path strategy from org.cybergarage.xml.Parser.parse(URL, String userAgent)
     * in mobilelink-1-7-13.apk and remote-viewfinder-2-2-11-multi-android.apk:
     * 1. Raw socket HTTP/1.0 GET with User-Agent, HOST: <host>:<port>, Content-Length: 0
     * 2. Fallback HttpURLConnection GET with Content-Length: 0, HOST: <host>, User-Agent
     */
    public static String httpGetXml(Network network, String urlStr, String userAgent, int timeoutMs) {
        if (network == null) {
            network = NetworkManager.getActiveWifiNetwork();
        }
        // Path 1: CyberGarage raw HTTP/1.0 socket GET
        HumbleHttpClient client = new HumbleHttpClient();
        try {
            InputStream in = client.request(network, urlStr, userAgent, timeoutMs);
            int expected = client.getContentLength();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(expected > 0 ? expected : 4096);
            byte[] buf = new byte[4096];
            int totalRead = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                baos.write(buf, 0, len);
                totalRead += len;
                if (expected > 0 && totalRead >= expected) {
                    break;
                }
            }
            String xml = baos.toString("UTF-8");
            if (xml != null && !xml.trim().isEmpty()) {
                return xml;
            }
        } catch (Exception e) {
            Log.d(TAG, "Raw HTTP/1.0 GET notice on " + urlStr + ": " + e.getMessage());
        } finally {
            client.close();
        }

        // Path 2: CyberGarage HttpURLConnection fallback
        HttpURLConnection urlCon = null;
        InputStream urlIn = null;
        try {
            URL locationUrl = new URL(urlStr);
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                urlCon = (HttpURLConnection) network.openConnection(locationUrl);
            } else {
                urlCon = (HttpURLConnection) locationUrl.openConnection();
            }
            urlCon.setRequestMethod("GET");
            urlCon.setConnectTimeout(timeoutMs);
            urlCon.setReadTimeout(timeoutMs);
            urlCon.setRequestProperty("Content-Length", "0");
            urlCon.setRequestProperty("HOST", locationUrl.getHost());
            if (userAgent != null && !userAgent.isEmpty()) {
                urlCon.setRequestProperty("User-Agent", userAgent);
            }
            urlCon.setRequestProperty("Connection", "close");

            int code = urlCon.getResponseCode();
            if (code == 200) {
                urlIn = new BufferedInputStream(urlCon.getInputStream());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = urlIn.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toString("UTF-8");
            }
        } catch (Exception e) {
            Log.d(TAG, "Fallback HttpURLConnection GET failed on " + urlStr + ": " + e.getMessage());
        } finally {
            if (urlIn != null) {
                try { urlIn.close(); } catch (Exception ignored) {}
            }
            if (urlCon != null) {
                try { urlCon.disconnect(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Executes a UPnP SOAP Action POST request matching CyberGarage's SOAPRequest / HTTPRequest:
     *   POST <path> HTTP/1.0
     *   Content-Type: text/xml; charset="utf-8"
     *   SOAPACTION: "<serviceType>#<actionName>"
     *   User-Agent: <userAgent>
     *   HOST: <host>:<port>
     *   Content-Length: <bytes>
     */
    public static String httpPostSoap(Network network, String urlStr, String serviceType, String actionName, String soapBody, String userAgent, int timeoutMs) throws Exception {
        if (network == null) {
            network = NetworkManager.getActiveWifiNetwork();
        }
        byte[] bodyBytes = soapBody.getBytes(StandardCharsets.UTF_8);

        // Path 1: CyberGarage raw HTTP/1.0 socket POST
        HumbleHttpClient client = new HumbleHttpClient();
        try {
            URL url = new URL(urlStr);
            String host = url.getHost();
            int port = url.getPort() != -1 ? url.getPort() : 80;
            String path = url.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                path += "?" + url.getQuery();
            }

            client.sock = new Socket();
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { network.bindSocket(client.sock); } catch (Exception ignored) {}
            }
            client.sock.connect(new InetSocketAddress(host, port), Math.max(timeoutMs, 3000));
            client.sock.setSoTimeout(Math.max(timeoutMs, 5000));

            client.os = client.sock.getOutputStream();
            client.is = client.sock.getInputStream();

            StringBuilder sb = new StringBuilder();
            sb.append("POST ").append(path).append(" HTTP/1.0\r\n");
            sb.append("Content-Type: text/xml; charset=\"utf-8\"\r\n");
            sb.append("SOAPACTION: \"").append(serviceType).append("#").append(actionName).append("\"\r\n");
            if (userAgent != null && !userAgent.isEmpty()) {
                sb.append("User-Agent: ").append(userAgent).append("\r\n");
            }
            sb.append("HOST: ").append(host).append(":").append(port).append("\r\n");
            sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");

            client.os.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            client.os.write(bodyBytes);
            client.os.flush();

            client.parseHeaders(client.is);
            int expected = client.getContentLength();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(expected > 0 ? expected : 4096);
            byte[] buf = new byte[4096];
            int totalRead = 0;
            int len;
            while ((len = client.is.read(buf)) != -1) {
                baos.write(buf, 0, len);
                totalRead += len;
                if (expected > 0 && totalRead >= expected) {
                    break;
                }
            }
            String resp = baos.toString("UTF-8");
            if (client.responseCode >= 200 && client.responseCode < 300 && resp != null && !resp.isEmpty()) {
                return resp;
            }
        } catch (Exception e) {
            Log.d(TAG, "Raw HTTP/1.0 SOAP POST notice (" + actionName + "): " + e.getMessage());
        } finally {
            client.close();
        }

        // Path 2: HttpURLConnection fallback
        URL url = new URL(urlStr);
        HttpURLConnection conn;
        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            conn = (HttpURLConnection) network.openConnection(url);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + actionName + "\"");
            if (userAgent != null && !userAgent.isEmpty()) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            OutputStream out = conn.getOutputStream();
            out.write(bodyBytes);
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                in.close();
                return baos.toString("UTF-8");
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private void parseHeaders(InputStream in) throws Exception {
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
                continue;
            }
            if (b == '\n') {
                break;
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
     * Respects Content-Length and ensures immediate socket closure upon completion.
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
                if (totalBytes > 0 && totalRead >= totalBytes) {
                    break;
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
            int expected = client.getContentLength();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(expected > 0 ? expected : 8192);
            byte[] buf = new byte[8192];
            int totalRead = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                baos.write(buf, 0, len);
                totalRead += len;
                if (expected > 0 && totalRead >= expected) {
                    break;
                }
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
