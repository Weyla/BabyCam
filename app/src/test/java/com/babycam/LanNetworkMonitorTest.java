package com.babycam;

import android.content.Context;
import android.net.*;
import org.junit.Test;
import java.net.InetAddress;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@SuppressWarnings("deprecation")
public class LanNetworkMonitorTest {
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
