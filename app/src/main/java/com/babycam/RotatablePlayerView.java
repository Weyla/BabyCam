package com.babycam;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.TextureView;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/** Rotates only the picture, keeping playback controls upright and the image fitted. */
@OptIn(markerClass = UnstableApi.class)
public final class RotatablePlayerView extends PlayerView {
    private int videoRotation;
    private float sourceAspectRatio;
    private AspectRatioFrameLayout frame;

    public RotatablePlayerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        if (getVideoSurfaceView() != null) {
            getVideoSurfaceView().addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> applyTransform());
        }
    }

    void setVideoRotation(int degrees) {
        videoRotation = normalizeRotation(degrees);
        updateAspectRatio();
        applyTransform();
    }

    static int normalizeRotation(int degrees) {
        return degrees % 90 == 0 ? Math.floorMod(degrees, 360) : 0;
    }

    @Override
    protected void onContentAspectRatioChanged(@Nullable AspectRatioFrameLayout contentFrame,
                                               float aspectRatio) {
        frame = contentFrame;
        sourceAspectRatio = aspectRatio;
        updateAspectRatio();
    }

    private void updateAspectRatio() {
        if (frame != null) frame.setAspectRatio(videoRotation % 180 != 0
                && sourceAspectRatio > 0 ? 1f / sourceAspectRatio : sourceAspectRatio);
    }

    private void applyTransform() {
        if (!(getVideoSurfaceView() instanceof TextureView)) return;
        TextureView texture = (TextureView) getVideoSurfaceView();
        float width = texture.getWidth();
        float height = texture.getHeight();
        if (width == 0 || height == 0) return;
        Matrix transform = new Matrix();
        transform.postRotate(videoRotation, width / 2f, height / 2f);
        RectF bounds = new RectF(0, 0, width, height);
        transform.mapRect(bounds);
        transform.postScale(width / bounds.width(), height / bounds.height(),
                width / 2f, height / 2f);
        texture.setTransform(transform);
    }
}
