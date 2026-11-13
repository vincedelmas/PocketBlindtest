package com.vincedelmas.pocketblindtest;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** Private on-device history. Round IDs make repeated lifecycle saves idempotent. */
final class GameHistory extends SQLiteOpenHelper {
    record Group(String name,int played,int found,double meanMs) {}
    record Summary(int played,int found,double meanMs,List<Group> genres,List<Group> fiveYears,List<Group> decades) {}
    record Session(String id,int size,long started,int played,int found) {}
    record Result(String title,String artist,boolean found,int clipMs) {}

    GameHistory(Context context) { super(context,"game-history.db",null,1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, size INTEGER NOT NULL, started INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, session TEXT NOT NULL, track INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, genre TEXT NOT NULL, year INTEGER NOT NULL, found INTEGER NOT NULL, clip_ms INTEGER NOT NULL, attempts INTEGER NOT NULL, ended INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX results_session ON results(session)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unknown history version"); }
    String start(int size) {
        String id=UUID.randomUUID().toString(); ContentValues v=new ContentValues();
        v.put("id",id); v.put("size",size); v.put("started",System.currentTimeMillis()); getWritableDatabase().insertOrThrow("sessions",null,v); return id;
    }
    void record(String id,String session,long track,String title,String artist,String genre,int year,boolean found,int clipMs,int attempts) {
        ContentValues v=new ContentValues(); v.put("id",id); v.put("session",session); v.put("track",track); v.put("title",title); v.put("artist",artist);
        v.put("genre",genre); v.put("year",year); v.put("found",found?1:0); v.put("clip_ms",clipMs); v.put("attempts",attempts); v.put("ended",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("results",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    void reset() {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { db.execSQL("DELETE FROM results"); db.execSQL("DELETE FROM sessions"); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    void remove(String round) { getWritableDatabase().delete("results","id=?",new String[]{round}); }
    Boolean found(String round) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT found FROM results WHERE id=?",new String[]{round})) { return c.moveToFirst()?c.getInt(0)==1:null; }
    }
    Summary summary(String session) {
        String where=session==null?"":" WHERE session=?"; String[] args=session==null?null:new String[]{session};
        int played=0,found=0; double mean=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(found),0), AVG(CASE WHEN found=1 THEN clip_ms END) FROM results"+where,args)) {
            if(c.moveToFirst()) { played=c.getInt(0); found=c.getInt(1); mean=c.getDouble(2); }
        }
        return new Summary(played,found,mean,groups("genre COLLATE NOCASE",where,args),groups("CASE WHEN year=0 THEN 'Unknown' ELSE CAST((year/5)*5 AS TEXT)||'–'||CAST((year/5)*5+4 AS TEXT) END",where,args),
            groups("CASE WHEN year=0 THEN 'Unknown' ELSE CAST((year/10)*10 AS TEXT)||'s' END",where,args));
    }
    private List<Group> groups(String expression,String where,String[] args) {
        ArrayList<Group> groups=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+expression+" AS label, COUNT(*), SUM(found), AVG(CASE WHEN found=1 THEN clip_ms END) FROM results"+where+" GROUP BY label ORDER BY COUNT(*) DESC, label COLLATE NOCASE",args)) {
            while(c.moveToNext()) groups.add(new Group(c.getString(0),c.getInt(1),c.getInt(2),c.getDouble(3)));
        }
        return groups;
    }
    List<Session> sessions() {
        ArrayList<Session> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.size,s.started,COUNT(r.id),COALESCE(SUM(r.found),0) FROM sessions s LEFT JOIN results r ON s.id=r.session GROUP BY s.id HAVING COUNT(r.id)>0 ORDER BY s.started DESC LIMIT 30",null)) {
            while(c.moveToNext()) list.add(new Session(c.getString(0),c.getInt(1),c.getLong(2),c.getInt(3),c.getInt(4)));
        }
        return list;
    }
    List<Result> results(String session) {
        ArrayList<Result> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT title,artist,found,clip_ms FROM results WHERE session=? ORDER BY ended DESC LIMIT 50",new String[]{session})) {
            while(c.moveToNext()) list.add(new Result(c.getString(0),c.getString(1),c.getInt(2)==1,c.getInt(3)));
        }
        return list;
    }
}
