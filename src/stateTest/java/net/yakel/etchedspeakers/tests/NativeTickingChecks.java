package net.yakel.etchedspeakers.tests;

import java.util.*;
import net.yakel.etchedspeakers.source.model.*;
import static net.yakel.etchedspeakers.source.model.SourceTicketMode.*;
import static net.yakel.etchedspeakers.source.model.SourcePlaybackState.*;

final class NativeTickingChecks {
    private static int checks;
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
    private static SourcePlaybackState playing(Reason reason) {
        var track=new TrackReference(TrackReference.Kind.SOUND_EVENT,"minecraft:music_disc.creator",Optional.empty(),0,0);
        return new SourcePlaybackState(Availability.AVAILABLE,Optional.of(SourceType.VANILLA_JUKEBOX),Playback.PLAYING,
                List.of(track),Optional.of(track),Optional.empty(),0,reason);
    }
    static void run() {
        var nativeState=playing(Reason.VANILLA_SONG_PLAYER);
        check(desired(true,LOAD_ONLY,nativeState,1,false)==TICKING_NATIVE,"remote native upgrade");
        check(desired(true,LOAD_ONLY,playing(Reason.ALBUM_CLIENT_SEQUENCE),1,false)==LOAD_ONLY,"Album unchanged");
        check(desired(true,LOAD_ONLY,playing(Reason.ETCHED_CLIENT_SEQUENCE),1,false)==LOAD_ONLY,"URL unchanged");
        check(desired(false,NONE,nativeState,0,false)==NONE,"local-only never admits a ticket");
        check(desired(true,TICKING_NATIVE,nativeState,0,true)==TICKING_NATIVE,"remote grace continues real ticking");
        check(desired(true,LOAD_ONLY,nativeState,0,true)==LOAD_ONLY,"local-only grace cannot invent remote native demand");
        check(desired(true,TICKING_NATIVE,nativeState,1,false)==TICKING_NATIVE,"return during grace keeps mode");
        check(desired(false,TICKING_NATIVE,nativeState,0,false)==NONE,"expiry releases mode");
        var stopped=new SourcePlaybackState(Availability.AVAILABLE,Optional.of(SourceType.VANILLA_JUKEBOX),Playback.STOPPED,
                nativeState.availableTracks(),Optional.empty(),Optional.empty(),0,Reason.VANILLA_SONG_PLAYER);
        check(desired(true,TICKING_NATIVE,stopped,1,false)==LOAD_ONLY,"natural end downgrades");
        var empty=new SourcePlaybackState(Availability.AVAILABLE,Optional.of(SourceType.VANILLA_JUKEBOX),Playback.STOPPED,
                List.of(),Optional.empty(),Optional.empty(),0,Reason.EMPTY);
        check(desired(true,TICKING_NATIVE,empty,1,false)==LOAD_ONLY,"eject downgrades");
        check(desired(true,TICKING_NATIVE,SourcePlaybackState.unavailable(Availability.SOURCE_MISSING,1),1,false)==LOAD_ONLY,"missing source cannot keep stale native demand");
        check(desired(true,TICKING_NATIVE,playing(Reason.ETCHED_CLIENT_SEQUENCE),1,false)==LOAD_ONLY,"current kind overrides stale native mode");
        var lifecycle=new ListenerRetention<String>(ListenerRetention.HANDOFF_GRACE);
        var listener=Set.of(new UUID(1,1));
        lifecycle.request("A",listener,0); lifecycle.ready("A");
        lifecycle.get("A").nativeRemote(true);
        lifecycle.reconcile(Map.of(),Map.of("A",listener),5);
        check(lifecycle.get("A").nativeRemote() && !lifecycle.get("A").ticketNeeded(),"remote provenance survives local holder without changing general lifetime policy");
        check(desired(true,TICKING_NATIVE,nativeState,0,true)==TICKING_NATIVE,"remote-origin native continuity remains ticking during local handoff");
        lifecycle.reconcile(Map.of(),10);
        check(desired(lifecycle.get("A").ticketNeeded(),lifecycle.get("A").nativeRemote()?TICKING_NATIVE:NONE,nativeState,0,true)==TICKING_NATIVE,"native grace reacquires ticking after remote to local to absent handoff");
        check(lifecycle.get("A").grace()==1210,"production 1200-tick grace");
        check(!lifecycle.expired("A",1209),"entire grace retained");
        lifecycle.reconcile(Map.of("A",listener),100);
        check(lifecycle.get("A").grace()==-1,"return cancels expiry");
        lifecycle.reconcile(Map.of(),200);
        check(lifecycle.expired("A",1400),"expiry based on server ticks");
        lifecycle.request("B",listener,200);
        lifecycle.release("A",true);
        check(lifecycle.get("B")!=null && lifecycle.get("A")==null,"break removes only one source");
        check(!lifecycle.release("A",false),"cleanup idempotent");
        lifecycle.clear(); check(lifecycle.keys().isEmpty(),"dimension unload cleanup");
        lifecycle.request("C",listener,0); lifecycle.clear(); check(lifecycle.keys().isEmpty(),"server stop cleanup");
        for(int i=0;i<32;i++) lifecycle.request("key"+i,listener,0);
        check(lifecycle.keys().size()==32 && !lifecycle.request("overflow",listener,0),"one shared cap regardless of mode");
        check(desired(true,TICKING_NATIVE,nativeState,1,false)==desired(true,LOAD_ONLY,nativeState,1,false),"upgrade idempotent");
        check(desired(true,LOAD_ONLY,empty,1,false)==desired(true,TICKING_NATIVE,empty,1,false),"downgrade idempotent");
        check(desired(false,NONE,nativeState,0,false)==NONE,"pure local holder never creates native ticking demand");
        System.out.println("Native ticking checks: "+checks+" PASS");
    }
}
