package com.vincedelmas.pocketblindtest;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.*;

/** Decode before playback: clip length is a PCM frame count, never a startup timer. */
final class ClipAudio implements AutoCloseable {
    private final Context context;
    private final Handler main;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private AudioTrack track;
    private int audioFrames;
    float progress() {
        if(track==null || audioFrames==0 || track.getState()!=AudioTrack.STATE_INITIALIZED) return 0;
        return Math.min(1f, (float)Integer.toUnsignedLong(track.getPlaybackHeadPosition())/audioFrames);
    }

    ClipAudio(Context context, Handler main) { this.context=context; this.main=main; }

    void play(Uri uri, int milliseconds, AudioAttributes attributes, Runnable started, Runnable completed, Runnable failed) {
        worker.execute(() -> {
            try {
                Pcm pcm=decode(uri,milliseconds);
                main.post(() -> {
                    if(cancelled) return;
                    try {
                        int mask=pcm.channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
                        track=new AudioTrack.Builder().setAudioAttributes(attributes)
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(pcm.rate).setChannelMask(mask).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.bytes.length).build();
                        if(track.getState()==AudioTrack.STATE_UNINITIALIZED || track.write(pcm.bytes,0,pcm.bytes.length)!=pcm.bytes.length)
                            throw new IllegalStateException("Audio buffer unavailable");
                        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                            @Override public void onMarkerReached(AudioTrack t) { if(!cancelled) completed.run(); }
                            @Override public void onPeriodicNotification(AudioTrack t) {}
                        },main);
                        track.setNotificationMarkerPosition(pcm.bytes.length/(2*pcm.channels)-1);
                        audioFrames=pcm.bytes.length/(2*pcm.channels)-pcm.rate/2;
                        track.play(); started.run();
                    } catch(Exception e) { if(!cancelled) failed.run(); }
                });
            } catch(Exception e) { main.post(() -> { if(!cancelled) failed.run(); }); }
            finally { worker.shutdown(); }
        });
    }

    private record Pcm(byte[] bytes,int rate,int channels) {}

    private Pcm decode(Uri uri,int milliseconds) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try {
            extractor.setDataSource(context,uri,null);
            MediaFormat input=null;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("audio/")) { extractor.selectTrack(i); input=f; break; }
            }
            if(input==null) throw new IllegalArgumentException("No audio track");
            input.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            codec=MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            codec.configure(input,null,null,0); codec.start();
            int rate=input.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels=input.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            ByteArrayOutputStream samples=new ByteArrayOutputStream();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputDone=false, audible=false, outputDone=false;
            long scanned=0, deadline=SystemClock.elapsedRealtime()+15000;
            while(!cancelled && SystemClock.elapsedRealtime()<deadline) {
                if(!inputDone) {
                    int index=codec.dequeueInputBuffer(10000);
                    if(index>=0) {
                        ByteBuffer buffer=codec.getInputBuffer(index);
                        int size=extractor.readSampleData(buffer,0);
                        if(size<0 || extractor.getSampleTime()>(milliseconds+11000L)*1000) {
                            codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true;
                        } else {
                            codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0); extractor.advance();
                        }
                    }
                }
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output=codec.getOutputFormat();
                    rate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding=output.getInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                    if(channels<1 || channels>2 || encoding!=AudioFormat.ENCODING_PCM_16BIT || rate>192000)
                        throw new IllegalArgumentException("Unsupported decoded format");
                } else if(index>=0) {
                    ByteBuffer buffer=codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                    buffer.position(info.offset); buffer.limit(info.offset+info.size);
                    int frameBytes=channels*2, wanted=(int)((long)rate*milliseconds/1000)*frameBytes;
                    byte[] frame=new byte[frameBytes];
                    while(buffer.remaining()>=frameBytes && samples.size()<wanted) {
                        boolean signal=false;
                        buffer.get(frame);
                        for(int c=0;c<channels;c++) {
                            short value=(short)((frame[c*2]&255)|(frame[c*2+1]<<8));
                            if(Math.abs((int)value)>128) signal=true;
                        }
                        // Skip at most ten seconds of near-silence; every attempt uses the same onset.
                        if(signal || scanned>=rate*10L) audible=true;
                        scanned++;
                        if(audible) samples.write(frame);
                    }
                    codec.releaseOutputBuffer(index,false);
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    if(samples.size()>=wanted || outputDone) break;
                }
            }
            if(cancelled || samples.size()==0) throw new IllegalStateException("No decoded audio");
            if(!outputDone && samples.size()<(long)rate*milliseconds/1000*channels*2)
                throw new IllegalStateException("Audio decoding timed out");
            // A silent tail lets the output pipeline drain; it never adds song content.
            byte[] bytes=Arrays.copyOf(samples.toByteArray(),samples.size()+rate/2*channels*2);
            return new Pcm(bytes,rate,channels);
        } finally {
            if(codec!=null) codec.release();
            extractor.release();
        }
    }

    @Override public void close() {
        cancelled=true; worker.shutdownNow();
        if(track!=null) {
            try { if(track.getState()==AudioTrack.STATE_INITIALIZED) { track.pause(); track.flush(); } }
            finally { track.release(); track=null; }
        }
    }
}
