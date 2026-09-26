package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment.*;

/** Deterministic policy checks, not a claim of audible multiplayer acceptance. */
final class LocalCanonicalChecks {
    private static int checks;
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
    static void run() {
        var id=new Identity("overworld:13,67,22",4,"hash-a",0,1);
        var next=new Identity(id.source(),5,id.media(),0,2);
        check(CanonicalAlignment.matches(id,id.source(),id.media(),0,1),"matching source/media/occurrence");
        check(!CanonicalAlignment.matches(id,"nether:13,67,22",id.media(),0,1),"dimension isolated");
        check(!CanonicalAlignment.matches(id,"overworld:14,67,22",id.media(),0,1),"same URL at another position isolated");
        check(!CanonicalAlignment.matches(id,id.source(),"hash-b",0,1),"wrong media never pre-rolled");
        check(!CanonicalAlignment.matches(id,id.source(),id.media(),1,1),"repeated slot isolated");
        check(!CanonicalAlignment.matches(id,id.source(),id.media(),0,2),"repeated track occurrence isolated");
        check(!CanonicalAlignment.matches(null,id.source(),id.media(),0,1),"no canonical means no reconciliation");
        check(CanonicalAlignment.accepts(id,20,null,0),"initial canonical snapshot");
        check(!CanonicalAlignment.accepts(id,19,id,20),"older anchor rejected");
        check(!CanonicalAlignment.accepts(id,99,next,20),"old generation cannot resurrect");
        check(CanonicalAlignment.accepts(next,21,id,20),"same URL new generation accepted");
        check(!CanonicalAlignment.accepts(new Identity(id.source(),4,"hash-b",0,1),21,id,20),"same generation cannot change media");
        check(!CanonicalAlignment.accepts(new Identity("other",4,id.media(),0,1),21,id,20),"snapshot source mismatch rejected");
        var prep=new Preparation(id);
        check(prep.current(id),"matching task can hand off");
        check(!prep.current(next),"Next cancels old-generation handoff");
        check(!prep.current(new Identity(id.source(),5,id.media(),0,1)),"same occurrence different generation still stale");
        prep.cancel();
        check(!prep.current(id),"eject cancellation permanent");
        prep.cancel(); check(!prep.current(id),"idempotent cancel");
        var second=new Preparation(new Identity("other",4,id.media(),0,1));
        check(second.current(new Identity("other",4,id.media(),0,1)),"other source preparation survives");
        second.cancel(); check(!second.current(new Identity("other",4,id.media(),0,1)),"disconnect/world cleanup invalidates pending work");
        var clock=new Clock(44100*60L,44100,false,1_000_000_000L,2);
        check(clock.target(1_000_000_000L)==44100*60L+4410,"snapshot delay projected with RemoteTimeline");
        check(clock.target(3_000_000_000L)-clock.target(1_000_000_000L)==88200,"moving target advances while decoder prepares");
        check(clock.target(0)==clock.target(1_000_000_000L),"negative local elapsed clamped");
        check(new Clock(1234,44100,true,0,20).target(2_000_000_000L)==1234,"paused target fixed");
        check(new Clock(0,0,false,0,20).target(2_000_000_000L)==0,"pending format no invented time");
        check(new Clock(RemoteTimeline.MAX_SECONDS*44100,44100,false,0,0).target(1_000_000_000L)==RemoteTimeline.MAX_SECONDS*44100,"projection bounded");
        check(!CanonicalAlignment.keepLocalSpeaker(true,false,true,false,true,1),"nearby Speaker rejects unaligned local master");
        check(!CanonicalAlignment.keepLocalSpeaker(true,true,true,false,true,1),"canonical ownership also wins integrated shortcut");
        check(CanonicalAlignment.keepLocalSpeaker(false,true,true,false,true,1),"local-only integrated route unchanged");
        check(CanonicalAlignment.keepLocalSpeaker(false,false,true,false,true,1),"ordinary nearby local route unchanged");
        var drift=new DriftGate();
        check(!drift.sample(4410,44100,false,0),"100ms does not restart");
        check(!drift.sample(22050,44100,false,1_000_000_000L),"500ms does not restart");
        check(!drift.sample(88200,44100,false,2_000_000_000L),"one large sample insufficient");
        check(!drift.sample(88200,44100,false,3_000_000_000L),"two large samples insufficient");
        check(drift.sample(88200,44100,false,4_000_000_000L),"sustained lag requests one reconcile");
        check(!drift.sample(88200,44100,false,5_000_000_000L),"request resets sample count");
        drift.sample(88200,44100,false,6_000_000_000L);
        check(!drift.sample(88200,44100,false,7_000_000_000L),"cooldown stops restart storm");
        check(!drift.sample(88200,44100,true,15_000_000_000L),"pause cancels drift suspicion");
        check(!drift.sample(88200,44100,false,16_000_000_000L),"fresh samples needed after pause");
        drift.sample(88200,44100,false,17_000_000_000L);
        check(drift.sample(88200,44100,false,18_000_000_000L),"new persistent stall can reconcile after cooldown");
        var timeline=new RemoteTimeline();
        timeline.bootstrap(id.media(),4,100);
        timeline.remoteProgress(0,44100,100); timeline.advance(1300);
        long expected=timeline.at(1300);
        if(CanonicalAlignment.acceptsLocalObservation(true)) timeline.observe("A",99,"wrong",0,44100,false,1300,999);
        check(timeline.generation()==4 && timeline.at(1300)==expected && timeline.media().equals(id.media()),"returning local frame0 cannot reset remote authority");
        check(CanonicalAlignment.acceptsLocalObservation(false),"normal local report remains allowed");
        var terminal=new Preparation(next); terminal.cancel();
        check(!terminal.current(null),"canonical release clears handoff ownership");
        var autoNext=new Preparation(id);
        check(!autoNext.current(next),"stale auto-next decoder cannot attach to new generation");
        System.out.println("Local canonical checks: "+checks+" PASS");
    }
}
