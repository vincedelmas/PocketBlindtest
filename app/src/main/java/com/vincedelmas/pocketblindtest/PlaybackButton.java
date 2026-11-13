package com.vincedelmas.pocketblindtest;

import android.content.Context;
import android.graphics.*;
import android.widget.Button;

/** A clipped background fill, behind the label, driven by the actual audio frame position. */
final class PlaybackButton extends Button {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    PlaybackButton(Context context) { super(context); }
    void setProgress(float value) { progress=Math.max(0,Math.min(1,value)); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        if(progress>0) {
            float radius=28*getResources().getDisplayMetrics().density;
            Path clip=new Path(); clip.addRoundRect(0,0,getWidth(),getHeight(),radius,radius,Path.Direction.CW);
            canvas.save(); canvas.clipPath(clip); paint.setColor(0x406b9c75);
            canvas.drawRect(0,0,getWidth()*progress,getHeight(),paint); canvas.restore();
        }
        super.onDraw(canvas);
    }
    @Override public boolean performClick() { return super.performClick(); }
}
