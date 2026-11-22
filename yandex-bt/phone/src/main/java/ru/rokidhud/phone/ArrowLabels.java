package ru.rokidhud.phone;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** Explicit user-selected image matching. Unknown images are NEVER treated as guidance. */
final class ArrowLabels {
    private static final String PREFS="yandex_arrow_calibration_v1";
    private static final String SLOT="selected_slot";
    private static final String ID="selected_image_id";
    static String resolve(Context ctx,List<YandexLayout.Sample> samples) {
        SharedPreferences p=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        int pos=p.getInt(SLOT,-1);
        String name=p.getString(ID,null);
        if(pos<0||name==null)return "UNKNOWN";
        YandexLayout.Sample candidate=null;
        int count=0;
        for(YandexLayout.Sample s:samples)if(s.id.equals(name)){candidate=s;count++;}
        if(count!=1||"unknown_image".equals(name)){
            candidate=pos<samples.size()?samples.get(pos):null;
            if(candidate==null||!candidate.id.equals(name))return "UNKNOWN";
        }
        if(candidate==null)return "UNKNOWN";
        String result=p.getString(candidate.digest,"UNKNOWN");
        switch(result){
            case "RIGHT":case "LEFT":case "STRAIGHT":case "SLIGHT_LEFT":
            case "SLIGHT_RIGHT":case "UTURN":case "ROUNDABOUT":case "ARRIVE":return result;
            default:return "UNKNOWN";
        }
    }
    static void label(Context ctx,List<YandexLayout.Sample> samples,int pos,String maneuver){
        if(pos<0||pos>=samples.size())return;
        if("UNKNOWN".equals(maneuver))return;
        YandexLayout.Sample img=samples.get(pos);
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(img.digest,maneuver).putString(ID,img.id).putInt(SLOT,pos).apply();
    }
    static String labelFor(Context ctx,YandexLayout.Sample s){
        return ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(s.digest,"не размечено");
    }
}
