package com.babycam;

import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo;
import org.junit.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AudioFirstTrackSelectorTest {
    private MockedStatic<TextUtils> text;
    private MockedConstruction<SparseArray> arrays;
    private MockedConstruction<SparseBooleanArray> flags;
    private MockedStatic<Util> utilities;
    private MockedStatic<Pair> pairs;

    @Before public void androidCollections() {
        text = mockStatic(TextUtils.class);
        text.when(() -> TextUtils.isEmpty(any())).thenAnswer(call -> {
            CharSequence value = call.getArgument(0);
            return value == null || value.length() == 0;
        });
        text.when(() -> TextUtils.equals(any(), any())).thenAnswer(call ->
                java.util.Objects.equals(call.getArgument(0), call.getArgument(1)));
        arrays = mockConstruction(SparseArray.class, withSettings().defaultAnswer(RETURNS_SELF));
        flags = mockConstruction(SparseBooleanArray.class, withSettings().defaultAnswer(RETURNS_SELF));
        utilities = mockStatic(Util.class, call -> {
            if (call.getMethod().getName().equals("getSystemLanguageCodes")) return new String[]{"en"};
            return call.callRealMethod();
        });
        pairs = mockStatic(Pair.class);
        pairs.when(() -> Pair.create(any(), any())).thenAnswer(call -> {
            Pair<?, ?> pair = mock(Pair.class);
            Pair.class.getField("first").set(pair, call.getArgument(0));
            Pair.class.getField("second").set(pair, call.getArgument(1));
            return pair;
        });
    }
    @After public void cleanup() { pairs.close(); utilities.close(); flags.close(); arrays.close(); text.close(); }

    @Test public void audioAndVideoSourceSelectsOnlyAudioForThePrimarySession() throws Exception {
        ExoTrackSelection.Definition[] selected = select(true, C.FORMAT_HANDLED);
        assertNotNull(selected[0]);
        assertNull(selected[1]);
    }

    @Test public void videoOnlySourceSelectsVideoBeforeRtspSetup() throws Exception {
        ExoTrackSelection.Definition[] selected = select(false, C.FORMAT_HANDLED);
        assertNull(selected[0]);
        assertNotNull(selected[1]);
    }

    @Test public void unsupportedAudioDoesNotPreventSupportedVideoPlayback() throws Exception {
        ExoTrackSelection.Definition[] selected = select(true, C.FORMAT_UNSUPPORTED_TYPE);
        assertNull(selected[0]);
        assertNotNull(selected[1]);
    }

    @SuppressWarnings("deprecation") // Headless JVM tests deliberately use parameters without a Context.
    private static ExoTrackSelection.Definition[] select(boolean hasAudio, int audioSupport) throws Exception {
        AudioFirstTrackSelector selector = mock(AudioFirstTrackSelector.class, CALLS_REAL_METHODS);
        MappedTrackInfo info = mock(MappedTrackInfo.class);
        when(info.getRendererCount()).thenReturn(2);
        when(info.getRendererType(0)).thenReturn(C.TRACK_TYPE_AUDIO);
        when(info.getRendererType(1)).thenReturn(C.TRACK_TYPE_VIDEO);
        TrackGroupArray audio = hasAudio ? new TrackGroupArray(new TrackGroup("audio", new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(1).setSampleRate(44_100).build()))
                : TrackGroupArray.EMPTY;
        TrackGroupArray video = new TrackGroupArray(new TrackGroup("video", new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build()));
        when(info.getTrackGroups(0)).thenReturn(audio);
        when(info.getTrackGroups(1)).thenReturn(video);
        int[][][] supports = { hasAudio ? new int[][]{{RendererCapabilities.create(audioSupport)}} : new int[0][],
                {{RendererCapabilities.create(C.FORMAT_HANDLED)}} };
        ExoTrackSelection.Definition[] definitions = new ExoTrackSelection.Definition[2];
        DefaultTrackSelector.Parameters parameters = DefaultTrackSelector.Parameters.DEFAULT_WITHOUT_CONTEXT
                .buildUpon().setConstrainAudioChannelCountToDeviceCapabilities(false).build();
        selector.selectAllTracks(definitions, info, supports, new int[]{0, 0}, parameters);
        return definitions;
    }
}
