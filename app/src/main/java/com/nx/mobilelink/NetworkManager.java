package com.nx.mobilelink;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Centralized network management and process binding.
 * CRITICAL FOR ANDROID 8+:
 * When connected to a camera Wi-Fi without internet access, Android will
 * route all traffic to cellular mobile data unless the process is explicitly
 * bound to the Wi-Fi network!
 */
public class NetworkManager {
    private static final String TAG = "NetworkManager";

    public interface WifiListener {
        void onWifiChanged(boolean connected, String ssid, Network network);
    }

    private static Network activeWifiNetwork;
    private static ConnectivityManager.NetworkCallback globalNetworkCallback;
    private static WifiManager.WifiLock wifiLock;
    private static WifiManager.MulticastLock multicastLock;

    public static synchronized Network getActiveWifiNetwork() {
        return activeWifiNetwork;
    }

    public static synchronized void setActiveWifiNetwork(Network network) {
        activeWifiNetwork = network;
        MobileLinkClient.setActiveWifiNetwork(network);
        ViewfinderClient.setActiveWifiNetwork(network);
    }

    /**
     * Synchronously finds the Wi-Fi Network instance and binds the current process to it.
     * Guarantees all TCP/UDP/HTTP sockets go through Wi-Fi instead of Cellular Data.
     */
    public static synchronized Network bindProcessToWifi(Context context) {
        if (context == null) return activeWifiNetwork;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return activeWifiNetwork;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Network foundWifi = null;

                // 1. Try getActiveNetwork() first if it's Wi-Fi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network active = cm.getActiveNetwork();
                    if (active != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            foundWifi = active;
                        }
                    }
                }

                // 2. Scan all networks for TRANSPORT_WIFI
                if (foundWifi == null) {
                    for (Network net : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            foundWifi = net;
                            break;
                        }
                    }
                }

                // 3. Bind process to Wi-Fi
                if (foundWifi != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean bound = cm.bindProcessToNetwork(foundWifi);
                        Log.i(TAG, "Process bound to Wi-Fi network: " + foundWifi + ", success=" + bound);
                    } else {
                        ConnectivityManager.setProcessDefaultNetwork(foundWifi);
                    }
                    setActiveWifiNetwork(foundWifi);
                    acquireLocks(context);
                    return foundWifi;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error in bindProcessToWifi: " + e.getMessage());
        }
        return activeWifiNetwork;
    }

    /**
     * Registers a persistent NetworkCallback that automatically binds future Wi-Fi connections.
     */
    public static synchronized void registerWifiMonitoring(final Context context, final WifiListener listener) {
        bindProcessToWifi(context);

        if (globalNetworkCallback != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            globalNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "NetworkCallback onAvailable: " + network);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cm.bindProcessToNetwork(network);
                    } else {
                        ConnectivityManager.setProcessDefaultNetwork(network);
                    }
                    setActiveWifiNetwork(network);
                    acquireLocks(context);
                    if (listener != null) {
                        listener.onWifiChanged(true, getCurrentWifiSsid(context), network);
                    }
                }

                @Override
                public void onLost(Network network) {
                    Log.i(TAG, "NetworkCallback onLost: " + network);
                    if (activeWifiNetwork != null && activeWifiNetwork.equals(network)) {
                        setActiveWifiNetwork(null);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            cm.bindProcessToNetwork(null);
                        }
                    }
                    if (listener != null) {
                        listener.onWifiChanged(isWifiConnected(context), getCurrentWifiSsid(context), null);
                    }
                }
            };

            cm.registerNetworkCallback(request, globalNetworkCallback);
            Log.i(TAG, "Registered global Wi-Fi NetworkCallback");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register NetworkCallback: " + e.getMessage());
        }
    }

    public static synchronized void acquireLocks(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (wifiLock == null || !wifiLock.isHeld()) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NXCamLink:WifiLock");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                    Log.i(TAG, "Acquired high-performance WifiLock");
                }
                if (multicastLock == null || !multicastLock.isHeld()) {
                    multicastLock = wm.createMulticastLock("NXCamLink:MulticastLock");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                    Log.i(TAG, "Acquired MulticastLock for SSDP discovery");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire locks: " + e.getMessage());
        }
    }

    public static boolean isWifiConnected(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network net = cm.getActiveNetwork();
                    if (net != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return true;
                        }
                    }
                }
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return true;
                    }
                }
                NetworkInfo netInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnected()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static String getCurrentWifiSsid(Context context) {
        if (context == null) return null;
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getSSID() != null) {
                    String ssid = wi.getSSID();
                    if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 2) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    if (!ssid.equals("<unknown ssid>")) {
                        return ssid;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String getLocalIpAddress(Context context) {
        Network net = bindProcessToWifi(context);
        if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    LinkProperties lp = cm.getLinkProperties(net);
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
                if (name.contains("wlan") || name.contains("p2p") || name.contains("wlan0") || name.contains("ap")) {
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
     * Discovers all possible candidate IP addresses for the camera:
     * 1. LinkProperties routes (gateway)
     * 2. DhcpInfo gateway
     * 3. DhcpInfo serverAddress (DHCP Server is the camera in AP mode!)
     * 4. Derived subnet .1 from phone's IP
     * 5. Known Samsung NX defaults: 192.168.107.1, 192.168.49.1, 192.168.1.1
     */
    public static List<String> getCandidateCameraIps(Context context) {
        List<String> list = new ArrayList<>();
        bindProcessToWifi(context);

        String ssid = getCurrentWifiSsid(context);
        boolean isCameraSsid = ssid != null && (
                ssid.toUpperCase().contains("NX") ||
                ssid.toUpperCase().contains("SEC_") ||
                ssid.toUpperCase().contains("WB") ||
                ssid.toUpperCase().contains("SM-") ||
                ssid.toUpperCase().contains("DSC") ||
                ssid.toUpperCase().contains("CAMERA"));

        // If connected to a camera AP, 192.168.107.1 is the primary Samsung AP IP
        if (isCameraSsid) {
            list.add("192.168.107.1");
        }

        // 1. LinkProperties routes (gateway on TRANSPORT_WIFI)
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp != null) {
                            for (RouteInfo route : lp.getRoutes()) {
                                if (route.hasGateway() && route.getGateway() != null) {
                                    String gw = route.getGateway().getHostAddress();
                                    if (gw != null && !gw.equals("0.0.0.0") && !gw.isEmpty() && isSensibleCameraIp(gw) && !list.contains(gw)) {
                                        list.add(gw);
                                        Log.i(TAG, "Found camera IP from LinkProperties route: " + gw);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. DhcpInfo (serverAddress + gateway)
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null) {
                    if (dhcp.serverAddress != 0) {
                        int s = dhcp.serverAddress;
                        String sa = (s & 0xFF) + "." + ((s >> 8) & 0xFF) + "." + ((s >> 16) & 0xFF) + "." + ((s >> 24) & 0xFF);
                        if (!sa.equals("0.0.0.0") && isSensibleCameraIp(sa) && !list.contains(sa)) {
                            list.add(sa);
                            Log.i(TAG, "Found camera IP from DhcpInfo serverAddress: " + sa);
                        }
                    }
                    if (dhcp.gateway != 0) {
                        int g = dhcp.gateway;
                        String gw = (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
                        if (!gw.equals("0.0.0.0") && isSensibleCameraIp(gw) && !list.contains(gw)) {
                            list.add(gw);
                            Log.i(TAG, "Found camera IP from DhcpInfo gateway: " + gw);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. Derived .1 from phone's IP on Wi-Fi
        String localIp = getLocalIpAddress(context);
        if (localIp != null && localIp.contains(".") && isSensibleCameraIp(localIp)) {
            int lastDot = localIp.lastIndexOf('.');
            if (lastDot > 0) {
                String derived = localIp.substring(0, lastDot) + ".1";
                if (!derived.equals("0.0.0.0") && isSensibleCameraIp(derived) && !list.contains(derived)) {
                    list.add(derived);
                    Log.i(TAG, "Derived camera IP from local subnet: " + derived);
                }
            }
        }

        // 4. Common Samsung NX defaults
        if (!list.contains("192.168.107.1")) list.add("192.168.107.1");
        if (!list.contains("192.168.49.1")) list.add("192.168.49.1");
        if (!list.contains("192.168.1.1")) list.add("192.168.1.1");

        return list;
    }

    private static boolean isSensibleCameraIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) return false;
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) return false;
        if (ip.startsWith("192.168.107.") || ip.startsWith("192.168.49.") ||
            ip.startsWith("192.168.1.") || ip.startsWith("192.168.0.")) {
            return true;
        }
        // Exclude cellular carrier / mobile hotspot subnets
        if (ip.startsWith("192.168.16.") || ip.startsWith("192.168.43.") || ip.startsWith("10.") || ip.startsWith("172.")) {
            return false;
        }
        return true;
    }
}
