package com.babycam;

import android.content.Context;
import android.net.*;
import org.junit.Test;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Arrays;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@SuppressWarnings("deprecation")
public class LanNetworkMonitorTest {
    @Test public void keepsTheBoundLanWhenEnumerationOrderChangesAndFallsBackWhenLost() throws Exception {
        Context context = mock(Context.class);
        ConnectivityManager manager = mock(ConnectivityManager.class);
        when(context.getSystemService(ConnectivityManager.class)).thenReturn(manager);
        Network first = mock(Network.class), second = mock(Network.class);
        NetworkCapabilities capabilities = mock(NetworkCapabilities.class);
        when(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
        for (Network network : new Network[]{first, second}) {
            when(manager.getNetworkCapabilities(network)).thenReturn(capabilities);
        }
        LinkProperties firstProperties = mock(LinkProperties.class), secondProperties = mock(LinkProperties.class);
        LinkAddress firstAddress = mock(LinkAddress.class), secondAddress = mock(LinkAddress.class);
        when(firstAddress.getAddress()).thenReturn(InetAddress.getByName("192.168.1.20"));
        when(secondAddress.getAddress()).thenReturn(InetAddress.getByName("192.168.2.20"));
        when(firstProperties.getLinkAddresses()).thenReturn(Arrays.asList(firstAddress));
        when(secondProperties.getLinkAddresses()).thenReturn(Arrays.asList(secondAddress));
        when(manager.getLinkProperties(first)).thenReturn(firstProperties);
        when(manager.getLinkProperties(second)).thenReturn(secondProperties);
        when(manager.getAllNetworks()).thenReturn(new Network[]{second, first});
        assertEquals("192.168.1.20", LanNetworkMonitor.findAddress(context, "192.168.1.20"));
        when(manager.getAllNetworks()).thenReturn(new Network[]{second});
        assertEquals("192.168.2.20", LanNetworkMonitor.findAddress(context, "192.168.1.20"));
    }
    @Test public void selectsChangedWifiAddressInsteadOfCellularPrivateAddress() throws Exception {
        Context context = mock(Context.class);
        ConnectivityManager manager = mock(ConnectivityManager.class);
        when(context.getSystemService(ConnectivityManager.class)).thenReturn(manager);
        Network mobile = mock(Network.class);
        Network wifi = mock(Network.class);
        when(manager.getAllNetworks()).thenReturn(new Network[]{mobile, wifi});
        NetworkCapabilities mobileCapabilities = mock(NetworkCapabilities.class);
        NetworkCapabilities wifiCapabilities = mock(NetworkCapabilities.class);
        when(wifiCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
        when(manager.getNetworkCapabilities(mobile)).thenReturn(mobileCapabilities);
        when(manager.getNetworkCapabilities(wifi)).thenReturn(wifiCapabilities);
        LinkProperties properties = mock(LinkProperties.class);
        LinkAddress address = mock(LinkAddress.class);
        when(manager.getLinkProperties(wifi)).thenReturn(properties);
        when(properties.getLinkAddresses()).thenReturn(Collections.singletonList(address));
        when(address.getAddress()).thenReturn(InetAddress.getByName("192.168.1.20"));
        assertEquals("192.168.1.20", LanNetworkMonitor.findAddress(context));
        when(address.getAddress()).thenReturn(InetAddress.getByName("192.168.2.30"));
        assertEquals("192.168.2.30", LanNetworkMonitor.findAddress(context));
        when(manager.getAllNetworks()).thenReturn(new Network[]{mobile});
        assertEquals("127.0.0.1", LanNetworkMonitor.findAddress(context));
        verify(manager, never()).getLinkProperties(mobile);
    }
}
