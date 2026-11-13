package com.vincedelmas.pocketblindtest;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Local cover artwork, with a record sleeve when the answer or artwork is unavailable. */
final class RecordArtwork extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final boolean revealed;
    private Bitmap artwork;

    RecordArtwork(Context context,boolean revealed) {
        super(context); this.revealed=revealed;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setArtwork(Bitmap bitmap) {
        artwork=bitmap; invalidate();
        if(bitmap!=null) { setAlpha(.65f); animate().alpha(1f).setDuration(220).start(); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size=Math.min(getWidth(),getHeight()), inset=size*.025f;
        float cx=getWidth()/2f, cy=getHeight()/2f;
        RectF sleeve=new RectF(cx-size/2+inset,cy-size/2+inset,cx+size/2-inset,cy+size/2-inset);
        float corner=size*.12f;
        if(artwork!=null) {
            Path mask=new Path(); mask.addRoundRect(sleeve,corner,corner,Path.Direction.CW);
            canvas.save(); canvas.clipPath(mask);
            float scale=Math.max(sleeve.width()/artwork.getWidth(),sleeve.height()/artwork.getHeight());
            float w=artwork.getWidth()*scale,h=artwork.getHeight()*scale;
            canvas.drawBitmap(artwork,null,new RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2),paint);
            canvas.restore();
            return;
        }
        paint.setShader(new LinearGradient(sleeve.left,sleeve.top,sleeve.right,sleeve.bottom,
            new int[]{0xff393632,0xff24272a,0xff18191b},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(sleeve,corner,corner,paint); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size*.004f); paint.setColor(0xff4a4742);
        canvas.drawRoundRect(sleeve,corner,corner,paint); paint.setStyle(Paint.Style.FILL);
        float radius=size*.36f;
        paint.setColor(0xff101113); canvas.drawCircle(cx,cy,radius,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size*.003f);
        for(int i=0;i<11;i++) {
            paint.setColor(i%2==0?0xff303133:0xff232426);
            canvas.drawCircle(cx,cy,radius-size*.018f*i,paint);
        }
        paint.setStrokeWidth(size*.018f); paint.setColor(0x224e565b);
        RectF ring=new RectF(cx-radius*.85f,cy-radius*.85f,cx+radius*.85f,cy+radius*.85f);
        canvas.drawArc(ring,205,50,false,paint); canvas.drawArc(ring,25,50,false,paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xffed4235); canvas.drawCircle(cx,cy,size*.115f,paint);
        paint.setColor(0xfff7eee0);
        if(!revealed) {
            paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); paint.setTextSize(size*.13f); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("?",cx,cy-(paint.ascent()+paint.descent())/2,paint);
        } else {
            canvas.drawCircle(cx,cy,size*.019f,paint);
        }
        paint.setColor(0xffb1aaa0);
        for(int i=0;i<3;i++) canvas.drawCircle(sleeve.left+size*.08f+i*size*.025f,sleeve.bottom-size*.07f,size*.004f,paint);
    }
}
