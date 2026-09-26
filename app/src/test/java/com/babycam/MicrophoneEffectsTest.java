package com.babycam;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

public class MicrophoneEffectsTest {
    @Test public void supportedEffectsAreConfiguredAndReleased() {
        try (MockedStatic<AcousticEchoCanceler> aec = mockStatic(AcousticEchoCanceler.class);
             MockedStatic<NoiseSuppressor> ns = mockStatic(NoiseSuppressor.class);
             MockedStatic<AutomaticGainControl> agc = mockStatic(AutomaticGainControl.class)) {
            AcousticEchoCanceler echo = mock(AcousticEchoCanceler.class);
            NoiseSuppressor noise = mock(NoiseSuppressor.class);
            AutomaticGainControl gain = mock(AutomaticGainControl.class);
            aec.when(AcousticEchoCanceler::isAvailable).thenReturn(true);
            ns.when(NoiseSuppressor::isAvailable).thenReturn(true);
            agc.when(AutomaticGainControl::isAvailable).thenReturn(true);
            aec.when(() -> AcousticEchoCanceler.create(42)).thenReturn(echo);
            ns.when(() -> NoiseSuppressor.create(42)).thenReturn(noise);
            agc.when(() -> AutomaticGainControl.create(42)).thenReturn(gain);
            MicrophoneEffects effects = MicrophoneEffects.attach(42);
            verify(echo).setEnabled(true);
            verify(noise).setEnabled(true);
            verify(gain).setEnabled(false);
            effects.close();
            effects.close();
            verify(echo, times(1)).release();
            verify(noise, times(1)).release();
            verify(gain, times(1)).release();
        }
    }

    @Test public void brokenOrMissingEffectsDoNotPreventCapture() {
        try (MockedStatic<AcousticEchoCanceler> aec = mockStatic(AcousticEchoCanceler.class);
             MockedStatic<NoiseSuppressor> ns = mockStatic(NoiseSuppressor.class);
             MockedStatic<AutomaticGainControl> agc = mockStatic(AutomaticGainControl.class);
             MockedStatic<Log> log = mockStatic(Log.class)) {
            aec.when(AcousticEchoCanceler::isAvailable).thenReturn(true);
            aec.when(() -> AcousticEchoCanceler.create(42)).thenThrow(new IllegalStateException());
            ns.when(NoiseSuppressor::isAvailable).thenReturn(true);
            ns.when(() -> NoiseSuppressor.create(42)).thenReturn(null);
            MicrophoneEffects.attach(42).close();
            agc.verify(AutomaticGainControl::isAvailable);
        }
    }
}
