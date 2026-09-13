package com.babycam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class CodecCleanupTest {
    @Test public void failedConfigurationReleasesCodecBeforeTryingNext() throws Exception {
        MediaCodecInfo first = mock(MediaCodecInfo.class);
        MediaCodecInfo second = mock(MediaCodecInfo.class);
        for (MediaCodecInfo info : new MediaCodecInfo[]{first, second}) {
            when(info.isEncoder()).thenReturn(true);
            when(info.getSupportedTypes()).thenReturn(new String[]{"video/avc"});
        }
        when(first.getName()).thenReturn("OMX.vendor.first");
        when(second.getName()).thenReturn("OMX.vendor.second");
        MediaCodec rejected = mock(MediaCodec.class);
        MediaCodec accepted = mock(MediaCodec.class);
        MediaFormat format = mock(MediaFormat.class);
        doThrow(new IllegalArgumentException("unsupported profile")).when(rejected)
                .configure(eq(format), isNull(), isNull(), anyInt());
        try (MockedConstruction<MediaCodecList> lists = mockConstruction(MediaCodecList.class,
                    (mock, context) -> when(mock.getCodecInfos()).thenReturn(new MediaCodecInfo[]{first, second}));
             MockedStatic<MediaCodec> factory = mockStatic(MediaCodec.class)) {
            factory.when(() -> MediaCodec.createByCodecName("OMX.vendor.first")).thenReturn(rejected);
            factory.when(() -> MediaCodec.createByCodecName("OMX.vendor.second")).thenReturn(accepted);
            assertSame(accepted, CodecUtils.createHardwareEncoder("video/avc", format));
            verify(rejected).release();
            verify(accepted, never()).release();
        }
    }
}
