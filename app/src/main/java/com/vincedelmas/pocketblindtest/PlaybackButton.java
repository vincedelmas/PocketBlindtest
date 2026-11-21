package com.vincedelmas.pocketblindtest;

import android.content.Context;
import android.graphics.*;
import com.google.android.material.button.MaterialButton;

/** A clipped background fill, behind the label, driven by the actual audio frame position. */
final class PlaybackButton extends MaterialButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private float progress;

    PlaybackButton(Context context) { super(context); }

    void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (progress > 0f && getWidth() > 0 && getHeight() > 0) {
            float density = getResources().getDisplayMetrics().density;
            float radius = Math.min(28f * density, getHeight() / 2f);
            float fillWidth = getWidth() * progress;
            clip.reset();
            clip.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setShader(new LinearGradient(0, 0, fillWidth, 0, 0x337EE0A3, 0x667EE0A3, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, fillWidth, getHeight(), paint);
            paint.setShader(null);
            paint.setColor(0x997EE0A3);
            canvas.drawRect(Math.max(0f, fillWidth - 2f * density), 0, fillWidth, getHeight(), paint);
            canvas.restore();
        }
        super.onDraw(canvas);
    }

    @Override public boolean performClick() { return super.performClick(); }
}
