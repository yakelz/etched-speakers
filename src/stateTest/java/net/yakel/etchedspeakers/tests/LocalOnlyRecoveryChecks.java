package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment.Identity;

final class LocalOnlyRecoveryChecks {
    private static int checks;
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); checks++; }
    static void run() {
        check(OriginalRecovery.nativeTimeline(0,1,true),"local native holder requires no remote listener");
        check(!OriginalRecovery.nativeTimeline(0,0,true),"no native radio after final holder leaves");
        check(!OriginalRecovery.nativeTimeline(0,1,false),"ejected native disc cannot create timeline");
        check(OriginalRecovery.nativeTimeline(1,0,true),"remote native eligibility unchanged");
        check(OriginalRecovery.timelineEligible(true,true),"local authoritative timeline admits Album without remoteOwned");
        check(!OriginalRecovery.timelineEligible(true,false),"remote-only snapshot does not create original");
        check(CanonicalAlignment.acceptsLocalObservation(false),"local recovery keeps original authority path");
        check(!CanonicalAlignment.acceptsLocalObservation(true),"remote priority unchanged");
        check(OriginalRecovery.suppressEnd(false,false),"URL category channel loss is not an auto-next or EOF");
        check(!OriginalRecovery.suppressEnd(false,true),"real local EOF retains Etched sequence behavior");
        check(OriginalRecovery.suppressEnd(true,true),"remote-owned EOF waits for canonical decision");
        for(var kind:new OriginalSourceKind[]{OriginalSourceKind.ALBUM_ETCHED,OriginalSourceKind.VANILLA_NATIVE,OriginalSourceKind.VANILLA_ETCHED}) {
            var id=new Identity("pos",4,"media",0,0,kind);
            var preparation=new CanonicalAlignment.Preparation(id); preparation.cancel();
            check(!preparation.current(id),"eject cancels "+kind);
            check(!CanonicalAlignment.accepts(id,99,new Identity("pos",5,"media",0,0,kind),10),"same-media reinsert rejects old "+kind);
        }
        check(!CanonicalAlignment.accepts(new Identity("pos",1,"url",0,0,OriginalSourceKind.VANILLA_ETCHED),10,
                new Identity("pos",1,"url",0,0,OriginalSourceKind.ALBUM_ETCHED),9),"BE source kind prevents same-URL cross-association");
        var gate=new OriginalRecovery(); gate.volume(false);
        check(!gate.begin(0),"muted URL does not create decoder"); gate.volume(true);
        check(gate.begin(1) && !gate.begin(2),"URL restoration requests one recovery");
        var timeline=new RemoteTimeline();
        timeline.observe("local",1,"url",882000,44100,false,400,1);
        timeline.advance(500);
        check(timeline.at(500)==1102500,"measured local anchor survives five seconds without Channel");
        check(timeline.observe("local",2,"url",1102500,44100,false,500,2).equals("CONTINUE") && timeline.generation()==1,
                "replacement master keeps occurrence and absolute discarded-frame position");
        check(timeline.eof("local",2,500) && !timeline.active(),"real replacement EOF remains terminal");
        check(ListenerRetention.HANDOFF_GRACE==1200,"existing remote handoff grace unchanged");
        check(new NativeDiscClock(600,0,0).target(48000,0)==1440000,"native Speaker timeline conversion unchanged");
        System.out.println("Local-only recovery checks: "+checks+" PASS");
    }
}
