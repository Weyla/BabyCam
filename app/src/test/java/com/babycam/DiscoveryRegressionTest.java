package com.babycam;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.net.InetAddress;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@SuppressWarnings("deprecation") // Exercising the API-26-compatible discovery path.
public class DiscoveryRegressionTest {
    @Test public void startDeliversDeviceAndIgnoresCallbacksAfterClose() throws Exception {
        Context context = mock(Context.class);
        NsdManager manager = mock(NsdManager.class);
        when(context.getSystemService(NsdManager.class)).thenReturn(manager);
        LocalDeviceDiscovery.Listener listener = mock(LocalDeviceDiscovery.Listener.class);
        LocalDeviceDiscovery.Browser browser = new LocalDeviceDiscovery.Browser(context, listener);
        browser.start();
        ArgumentCaptor<NsdManager.DiscoveryListener> discovery = ArgumentCaptor.forClass(NsdManager.DiscoveryListener.class);
        verify(manager).discoverServices(eq(LocalDeviceDiscovery.SERVICE_TYPE), anyInt(), discovery.capture());
        NsdServiceInfo info = mock(NsdServiceInfo.class);
        when(info.getServiceName()).thenReturn("Nursery");
        when(info.getServiceType()).thenReturn(LocalDeviceDiscovery.SERVICE_TYPE);
        when(info.getHost()).thenReturn(InetAddress.getByName("192.168.1.20"));
        when(info.getPort()).thenReturn(8554);
        when(info.getAttributes()).thenReturn(Collections.emptyMap());
        discovery.getValue().onServiceFound(info);
        ArgumentCaptor<NsdManager.ResolveListener> resolve = ArgumentCaptor.forClass(NsdManager.ResolveListener.class);
        verify(manager).resolveService(eq(info), resolve.capture());
        resolve.getValue().onServiceResolved(info);
        ArgumentCaptor<LocalDeviceDiscovery.Device[]> devices = ArgumentCaptor.forClass(LocalDeviceDiscovery.Device[].class);
        verify(listener).onDevicesChanged(devices.capture());
        assertEquals("192.168.1.20", devices.getValue()[0].host);
        browser.close();
        clearInvocations(listener, manager);
        discovery.getValue().onServiceFound(info);
        resolve.getValue().onServiceResolved(info);
        verifyNoInteractions(listener, manager);
    }

    @Test public void restartingIgnoresOldDiscoveryGeneration() {
        Context context = mock(Context.class);
        NsdManager manager = mock(NsdManager.class);
        when(context.getSystemService(NsdManager.class)).thenReturn(manager);
        LocalDeviceDiscovery.Browser browser = new LocalDeviceDiscovery.Browser(context,
                mock(LocalDeviceDiscovery.Listener.class));
        browser.start();
        browser.start();
        ArgumentCaptor<NsdManager.DiscoveryListener> callbacks = ArgumentCaptor.forClass(NsdManager.DiscoveryListener.class);
        verify(manager, times(2)).discoverServices(anyString(), anyInt(), callbacks.capture());
        NsdServiceInfo info = mock(NsdServiceInfo.class);
        callbacks.getAllValues().get(0).onServiceFound(info);
        verify(manager, never()).resolveService(any(), any());
        browser.close();
    }
}
