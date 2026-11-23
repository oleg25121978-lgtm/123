package ru.rokidhud.mapsrelay;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import java.util.Locale;

/** Finds native Yandex lane-guidance glyphs, not made-up arrows or advice. */
public final class MapsHudLanes {
    private MapsHudLanes(){}

    private static String label(View v) {
        String type=v.getClass().getName().toLowerCase(Locale.ROOT);
        if(v.getId()!=View.NO_ID) {
            try {
                Resources r=v.getResources();
                type += " "+r.getResourceEntryName(v.getId()).toLowerCase(Locale.ROOT);
            }catch(Throwable ignored){}
        }
        return type;
    }
    private static boolean laneNamed(View view) {
        String s=label(view);
        return s.contains("lane")||s.contains("lanesign")||s.contains("polosa");
    }
    private static boolean suitable(View view) {
        return view!=null && view.isShown() &&
            view.getWidth()>=12 && view.getHeight()>=12 &&
            view.getHeight()<=220 && view.getWidth()<=720;
    }
    private static View find(View view,int depth) {
        if(view==null || depth>22 || view.getVisibility()!=View.VISIBLE)return null;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            View first=null;
            int matchingChildren=0;
            for(int i=0;i<group.getChildCount();i++) {
                View child=group.getChildAt(i);
                View candidate=find(child,depth+1);
                if(candidate!=null) {
                    if(first==null)first=candidate;
                    matchingChildren++;
                }
            }
            // Several lane arrows must stay together as one native strip.
            if(matchingChildren>=2 && suitable(view))return view;
            if(laneNamed(view) && suitable(view))return view;
            return first;
        }
        return laneNamed(view) && suitable(view)?view:null;
    }

    /**
     * First prefer lane arrows contained in the active maneuver widget.
     * Only then search the surrounding guidance UI. No lane advice is
     * synthesized if Yandex omits it for a route or transportation mode.
     */
    public static View locate(View maneuver,View root) {
        View inside=find(maneuver,0);
        if(inside!=null && inside!=maneuver)return inside;
        View elsewhere=find(root,0);
        if(elsewhere==maneuver)return null;
        return elsewhere;
    }
}
