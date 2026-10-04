package com.babycam;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;

/** Select audio alone when available, otherwise video, before the first RTSP SETUP. */
@OptIn(markerClass = UnstableApi.class)
final class AudioFirstTrackSelector extends DefaultTrackSelector {
    AudioFirstTrackSelector(Context context) {
        super(context);
    }

    @Override
    protected void selectAllTracks(ExoTrackSelection.Definition[] definitions,
            MappedTrackInfo info, int[][][] supports, int[] mixedMimeSupports,
            Parameters parameters) throws ExoPlaybackException {
        super.selectAllTracks(definitions, info, supports, mixedMimeSupports, parameters);
        boolean selectedAudio = false;
        for (int i = 0; i < definitions.length; i++) {
            if (definitions[i] != null && info.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                selectedAudio = true;
            }
        }
        if (selectedAudio) {
            for (int i = 0; i < definitions.length; i++) {
                if (info.getRendererType(i) == C.TRACK_TYPE_VIDEO) definitions[i] = null;
            }
        }
    }
}
