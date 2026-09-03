package com.babycam;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.Closeable;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** DNS-SD advertising and browsing for BabyCam devices on the current LAN. */
final class LocalDeviceDiscovery {
    static final String SERVICE_TYPE = "_babycam._tcp.";

    interface Listener {
        void onDevicesChanged(Device[] devices);

        void onDiscoveryError(String message);
    }

    static final class Device {
        final String name;
        final String host;
        final int rtspPort;
        final String username;
        final boolean videoCapable;

        Device(String name, String host, int rtspPort, String username, boolean videoCapable) {
            this.name = name;
            this.host = host;
            this.rtspPort = rtspPort;
            this.username = username;
            this.videoCapable = videoCapable;
        }

        String displayName() {
            return name + "  •  " + host + ":" + rtspPort;
        }
    }

    /** Serializes unregister/register because NsdManager completes both asynchronously. */
    static final class Advertiser implements Closeable {
        private final NsdManager manager;
        private NsdManager.RegistrationListener activeListener;
        private NsdServiceInfo desiredInfo;
        private String desiredSignature;
        private String activeSignature;
        private boolean unregistering;

        Advertiser(Context context) {
            manager = context.getSystemService(NsdManager.class);
        }

        synchronized void start(String serviceName, int rtspPort, String username,
                                boolean videoCapable) {
            if (manager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(sanitizeName(serviceName));
            info.setServiceType(SERVICE_TYPE);
            info.setPort(rtspPort);
            try {
                info.setAttribute("v", "3");
                info.setAttribute("user", limit(username, 48));
                info.setAttribute("video", videoCapable ? "1" : "0");
            } catch (IllegalArgumentException error) {
                return;
            }
            String signature = info.getServiceName() + "|" + rtspPort + "|"
                    + username + "|" + videoCapable;
            if (signature.equals(desiredSignature)
                    && (activeListener != null || unregistering)) return;
            desiredInfo = info;
            desiredSignature = signature;
            if (activeListener == null && !unregistering) {
                registerDesired();
            } else if (!signature.equals(activeSignature) && !unregistering) {
                unregistering = true;
                try {
                    manager.unregisterService(activeListener);
                } catch (IllegalArgumentException ignored) {
                    unregistering = false;
                    activeListener = null;
                    registerDesired();
                }
            }
        }

        private synchronized void registerDesired() {
            if (manager == null || desiredInfo == null || activeListener != null) return;
            NsdServiceInfo registeringInfo = desiredInfo;
            String registeringSignature = desiredSignature;
            NsdManager.RegistrationListener listener =
                    new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo info) {
                    synchronized (Advertiser.this) {
                        if (activeListener == this) activeSignature = registeringSignature;
                    }
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo info, int code) {
                    synchronized (Advertiser.this) {
                        if (activeListener != this) return;
                        activeListener = null;
                        activeSignature = null;
                        if (!registeringSignature.equals(desiredSignature)) registerDesired();
                    }
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo info) {
                    registrationEnded(this);
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo info, int code) {
                    registrationEnded(this);
                }
            };
            activeListener = listener;
            try {
                manager.registerService(registeringInfo, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (RuntimeException error) {
                activeListener = null;
                activeSignature = null;
            }
        }

        private synchronized void registrationEnded(NsdManager.RegistrationListener listener) {
            if (activeListener != listener) return;
            activeListener = null;
            activeSignature = null;
            unregistering = false;
            registerDesired();
        }

        @Override
        public synchronized void close() {
            desiredInfo = null;
            desiredSignature = null;
            activeSignature = null;
            if (manager != null && activeListener != null && !unregistering) {
                try {
                    unregistering = true;
                    manager.unregisterService(activeListener);
                } catch (IllegalArgumentException ignored) {
                    unregistering = false;
                    activeListener = null;
                }
            }
        }
    }

    /** Resolves devices one at a time for compatibility with older NsdManager versions. */
    static final class Browser implements Closeable {
        private final NsdManager manager;
        private final Listener listener;
        private final Map<String, Device> devices = new LinkedHashMap<>();
        private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
        private final Set<String> queuedNames = new HashSet<>();
        private NsdManager.DiscoveryListener discoveryListener;
        private boolean resolving;
        private boolean closed;

        Browser(Context context, Listener listener) {
            manager = context.getSystemService(NsdManager.class);
            this.listener = listener;
        }

        synchronized void start() {
            close();
            if (manager == null) {
                listener.onDiscoveryError("Device discovery is unavailable");
                return;
            }
            NsdManager.DiscoveryListener callback = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String serviceType) { }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    if (closed) return;
                    if (info.getServiceType() != null
                            && info.getServiceType().startsWith("_babycam._tcp")) {
                        enqueueResolve(info);
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                    synchronized (Browser.this) {
                        if (closed) return;
                        devices.entrySet().removeIf(entry ->
                                entry.getValue().name.equals(info.getServiceName()));
                        queuedNames.remove(info.getServiceName());
                        publish();
                    }
                }

                @Override public void onDiscoveryStopped(String serviceType) { }

                @Override
                public void onStartDiscoveryFailed(String type, int code) {
                    synchronized (Browser.this) {
                        if (discoveryListener != this) return;
                        discoveryListener = null;
                        listener.onDiscoveryError("Could not search this local network");
                    }
                }

                @Override
                public void onStopDiscoveryFailed(String type, int code) {
                    synchronized (Browser.this) {
                        if (discoveryListener == this) discoveryListener = null;
                    }
                }
            };
            discoveryListener = callback;
            try {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback);
            } catch (RuntimeException error) {
                discoveryListener = null;
                listener.onDiscoveryError("Could not search this local network");
            }
        }

        private synchronized void enqueueResolve(NsdServiceInfo info) {
            if (closed) return;
            if (!queuedNames.add(info.getServiceName())) return;
            resolveQueue.add(info);
            resolveNext();
        }

        @SuppressWarnings("deprecation") // Required for the app's API 26 minimum.
        private synchronized void resolveNext() {
            if (resolving || discoveryListener == null) return;
            NsdServiceInfo service = resolveQueue.poll();
            if (service == null) return;
            resolving = true;
            try {
                manager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        finishResolve(info);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        synchronized (Browser.this) {
                            if (closed) return;
                        }
                        InetAddress address = info.getHost();
                        String host = address == null ? null : address.getHostAddress();
                        if (RtspServer.isPrivateIpv4Literal(host)) {
                            int port = info.getPort();
                            Device device = new Device(info.getServiceName(), host, port,
                                    attribute(info, "user", AppSettings.DEFAULT_USERNAME),
                                    "1".equals(attribute(info, "video", "1")));
                            synchronized (Browser.this) {
                                devices.put(host + ":" + port, device);
                                publish();
                            }
                        }
                        finishResolve(info);
                    }
                });
            } catch (RuntimeException ignored) {
                finishResolve(service);
            }
        }

        private synchronized void finishResolve(NsdServiceInfo info) {
            queuedNames.remove(info.getServiceName());
            resolving = false;
            resolveNext();
        }

        private void publish() {
            if (!closed) listener.onDevicesChanged(devices.values().toArray(new Device[0]));
        }

        @Override
        public synchronized void close() {
            closed = true;
            devices.clear();
            resolveQueue.clear();
            queuedNames.clear();
            resolving = false;
            if (manager != null && discoveryListener != null) {
                try {
                    manager.stopServiceDiscovery(discoveryListener);
                } catch (IllegalArgumentException ignored) {
                }
                discoveryListener = null;
            }
        }
    }

    private static String attribute(NsdServiceInfo info, String key, String fallback) {
        byte[] value = info.getAttributes().get(key);
        return value == null ? fallback : new String(value, StandardCharsets.UTF_8);
    }

    private static String sanitizeName(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9 _-]", "");
        return clean.isEmpty() ? "BabyCam" : clean.substring(0, Math.min(clean.length(), 48));
    }

    private static String limit(String value, int maximum) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), maximum));
    }

    private LocalDeviceDiscovery() {
    }
}
