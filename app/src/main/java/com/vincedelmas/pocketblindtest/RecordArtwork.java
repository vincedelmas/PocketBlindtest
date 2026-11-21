package com.vincedelmas.pocketblindtest;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Local cover artwork, with a spinning vinyl record sleeve when unrevealed or artwork is unavailable. */
final class RecordArtwork extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path mask = new Path();
    private final boolean revealed;
    private Bitmap artwork;
    private float discAngle;
    private ValueAnimator spinner;

    RecordArtwork(Context context, boolean revealed) {
        super(context);
        this.revealed = revealed;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setArtwork(Bitmap bitmap) {
        artwork = bitmap;
        if (bitmap != null) setSpinning(false);
        invalidate();
        if (bitmap != null) {
            setAlpha(0.55f);
            setScaleX(0.96f);
            setScaleY(0.96f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
        }
    }

    void setSpinning(boolean active) {
        if (active && artwork == null) {
            if (spinner != null && spinner.isRunning()) return;
            float start = discAngle % 360f;
            spinner = ValueAnimator.ofFloat(start, start + 360f);
            spinner.setDuration(4200);
            spinner.setRepeatCount(ValueAnimator.INFINITE);
            spinner.setInterpolator(new LinearInterpolator());
            spinner.addUpdateListener(anim -> {
                discAngle = (float) anim.getAnimatedValue();
                invalidate();
            });
            spinner.start();
        } else if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        setSpinning(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0f) return;
        float inset = size * 0.02f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        RectF sleeve = new RectF(cx - size / 2f + inset, cy - size / 2f + inset, cx + size / 2f - inset, cy + size / 2f - inset);
        float corner = size * 0.13f;

        if (artwork != null) {
            mask.reset();
            mask.addRoundRect(sleeve, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mask);
            float scale = Math.max(sleeve.width() / artwork.getWidth(), sleeve.height() / artwork.getHeight());
            float w = artwork.getWidth() * scale, h = artwork.getHeight() * scale;
            canvas.drawBitmap(artwork, null, new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), paint);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.005f));
            paint.setColor(0x28FFFFFF);
            canvas.drawRoundRect(sleeve, corner, corner, paint);
            paint.setStyle(Paint.Style.FILL);
            return;
        }

        // Matte studio sleeve background
        paint.setShader(new LinearGradient(sleeve.left, sleeve.top, sleeve.right, sleeve.bottom,
            new int[]{0xFF23262A, 0xFF17191C, 0xFF111214}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(sleeve, corner, corner, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, size * 0.005f));
        paint.setColor(0xFF31353B);
        canvas.drawRoundRect(sleeve, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);

        // Ambient disc shadow
        float radius = size * 0.37f;
        paint.setShader(new RadialGradient(cx, cy + size * 0.015f, radius * 1.12f,
            new int[]{0x66000000, 0x00000000}, new float[]{0.82f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy + size * 0.015f, radius * 1.12f, paint);
        paint.setShader(null);

        // Rotating vinyl disc
        canvas.save();
        canvas.rotate(discAngle, cx, cy);

        paint.setColor(0xFF0E0F11);
        canvas.drawCircle(cx, cy, radius, paint);

        // Subtle metallic vinyl sheen
        paint.setShader(new SweepGradient(cx, cy,
            new int[]{0x00FFFFFF, 0x16FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
            new float[]{0f, 0.14f, 0.28f, 0.50f, 0.64f, 0.80f}));
        canvas.drawCircle(cx, cy, radius * 0.96f, paint);
        paint.setShader(null);

        // Fine concentric grooves
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, size * 0.003f));
        for (int i = 0; i < 12; i++) {
            paint.setColor(i % 2 == 0 ? 0xFF292B2F : 0xFF1B1D20);
            canvas.drawCircle(cx, cy, radius - size * 0.017f * i, paint);
        }

        // Rim highlight
        paint.setStrokeWidth(Math.max(1f, size * 0.004f));
        paint.setColor(0xFF3A3D42);
        canvas.drawCircle(cx, cy, radius, paint);

        // Center crimson label
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFED4235);
        canvas.drawCircle(cx, cy, size * 0.125f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, size * 0.005f));
        paint.setColor(0x33000000);
        canvas.drawCircle(cx, cy, size * 0.10f, paint);
        paint.setStyle(Paint.Style.FILL);

        // Subtle label index mark so rotation is clearly visible
        paint.setColor(0x44F5F1E8);
        canvas.drawCircle(cx, cy - size * 0.082f, size * 0.009f, paint);

        canvas.restore();

        // Center mark stays upright
        paint.setColor(0xFFF5F1E8);
        if (!revealed) {
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(size * 0.125f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("?", cx, cy - (paint.ascent() + paint.descent()) / 2f, paint);
        } else {
            paint.setColor(0xFF121416);
            canvas.drawCircle(cx, cy, size * 0.022f, paint);
        }

        // Minimalist corner dots (Nothing-inspired studio mark)
        paint.setColor(0xFF6E6D68);
        for (int i = 0; i < 3; i++) {
            canvas.drawCircle(sleeve.left + size * 0.08f + i * size * 0.026f, sleeve.bottom - size * 0.068f, size * 0.0055f, paint);
        }
    }
}
