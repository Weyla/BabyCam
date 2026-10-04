package com.babycam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.util.function.Consumer;

/** Observe Wi-Fi/Ethernet link changes on the main thread. */
final class LanNetworkMonitor implements Closeable {
    private final ConnectivityManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean closed;
    private String preferredAddress;
    private final Runnable refresh;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { schedule(); }
        @Override public void onLost(Network network) { schedule(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { schedule(); }
    };

    LanNetworkMonitor(Context context, Consumer<String> listener) {
        manager = context.getSystemService(ConnectivityManager.class);
        preferredAddress = findAddress(context);
        refresh = () -> {
            if (!closed) {
                preferredAddress = findAddress(context, preferredAddress);
                listener.accept(preferredAddress);
            }
        };
        if (manager != null) manager.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback, handler);
    }

    private void schedule() {
        handler.removeCallbacks(refresh);
        if (!closed) handler.postDelayed(refresh, 500);
    }

    @SuppressWarnings("deprecation") // Includes LAN networks without internet validation.
    static String findAddress(Context context) {
        return findAddress(context, null);
    }

    @SuppressWarnings("deprecation") // Includes LAN networks without internet validation.
    static String findAddress(Context context, String preferredAddress) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return "127.0.0.1";
        String firstAddress = "127.0.0.1";
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress address : properties.getLinkAddresses()) {
                String host = address.getAddress().getHostAddress();
                if (RtspServer.isPrivateIpv4Literal(host)) {
                    if (host.equals(preferredAddress)) return host;
                    if ("127.0.0.1".equals(firstAddress)) firstAddress = host;
                }
            }
        }
        return firstAddress;
    }

    @Override public void close() {
        closed = true;
        handler.removeCallbacks(refresh);
        if (manager != null) manager.unregisterNetworkCallback(callback);
    }
}
