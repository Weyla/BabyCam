package com.babycam;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Optional device effects; unsupported or broken effects must never stop monitoring. */
final class MicrophoneEffects implements AutoCloseable {
    private final List<AudioEffect> effects = new ArrayList<>();

    static MicrophoneEffects attach(int sessionId) {
        MicrophoneEffects result = new MicrophoneEffects();
        result.configure(() -> AcousticEchoCanceler.isAvailable()
                ? AcousticEchoCanceler.create(sessionId) : null, true);
        result.configure(() -> NoiseSuppressor.isAvailable()
                ? NoiseSuppressor.create(sessionId) : null, true);
        // Avoid automatically increasing microphone gain and feeding nearby speakers back.
        result.configure(() -> AutomaticGainControl.isAvailable()
                ? AutomaticGainControl.create(sessionId) : null, false);
        return result;
    }

    private interface Factory { AudioEffect create(); }

    private void configure(Factory factory, boolean enabled) {
        try {
            AudioEffect effect = factory.create();
            if (effect != null) {
                effects.add(effect);
                if (effect.setEnabled(enabled) != AudioEffect.SUCCESS) {
                    Log.w("BabyCamAudio", "Microphone effect could not be configured");
                }
            }
        } catch (RuntimeException error) {
            Log.w("BabyCamAudio", "Microphone effect unavailable", error);
        }
    }

    @Override public void close() {
        for (AudioEffect effect : effects) {
            try { effect.release(); }
            catch (RuntimeException error) {
                Log.w("BabyCamAudio", "Microphone effect release failed", error);
            }
        }
        effects.clear();
    }
}
