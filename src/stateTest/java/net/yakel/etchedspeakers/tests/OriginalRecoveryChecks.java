package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment.Identity;

final class OriginalRecoveryChecks {
    private static int checks;
    private static void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); checks++; }
    static void run() {
        var gate=new OriginalRecovery(); long now=1_000_000_000L;
        gate.volume(false);
        check(!gate.begin(now),"volume zero suppresses decoder creation");
        check(!gate.begin(now+60_000_000_000L),"muted source never retries");
        gate.volume(true); check(gate.begin(now),"restoration enables one attempt");
        check(!gate.begin(now+1),"duplicate recovery bounded");
        check(gate.begin(now+10_000_000_000L),"failed attempt can retry after backoff");
        check(gate.begin(now+20_000_000_000L),"third bounded attempt");
        check(!gate.begin(now+60_000_000_000L),"persistent failure exhausts budget");
        gate.volume(false); gate.volume(true);
        check(gate.begin(now+61_000_000_000L),"new slider cycle rearms exhausted nonterminal loss");
        gate.terminal(); gate.volume(false); gate.volume(true);
        check(!gate.begin(now+100_000_000_000L),"genuine EOF cannot be resurrected by slider");
        check(new OriginalRecovery().begin(now),"new occurrence has independent recovery budget");
        check(!OriginalRecovery.audible(0,1),"MASTER zero gates recovery");
        check(!OriginalRecovery.audible(1,0),"RECORDS zero gates recovery");
        check(OriginalRecovery.audible(.5f,.5f),"positive effective category gain");
        check(OriginalRecovery.eligible(true,true,true,true,4,true),"near tracked active source is eligible after reconnect");
        check(!OriginalRecovery.eligible(false,true,true,true,4,true),"eject forbids recovery");
        check(!OriginalRecovery.eligible(true,false,true,true,4,true),"Speaker subscription alone never creates original");
        check(!OriginalRecovery.eligible(true,true,false,true,4,true),"chunk unload forbids preparation");
        check(!OriginalRecovery.eligible(true,true,true,false,4,true),"dimension change rejects source");
        check(!OriginalRecovery.eligible(true,true,true,true,500*500,true),"remote-only listener cannot recover source");
        check(!OriginalRecovery.eligible(true,true,true,true,4,false),"stale lease rejected");
        var first=new Identity("pos",1,"creator",0,0); var next=new Identity("pos",2,"creator",0,0);
        check(!CanonicalAlignment.accepts(first,100,next,50),"same-disc previous occurrence cannot recover");
        check(!CanonicalAlignment.accepts(new Identity("pos",2,"pigstep",0,0),100,next,50),"same-generation media mismatch rejected");
        var prep=new CanonicalAlignment.Preparation(first); prep.cancel();
        check(!prep.current(first),"stale original token cancellation never becomes current again");
        check(new NativeDiscClock(600,now,0).target(48000,now)==1_440_000,"native reconnect prepares current thirty seconds");
        check(new CanonicalAlignment.Clock(882000,44100,false,now,0).target(now+5_000_000_000L)==1102500,
                "Album restore advances existing canonical clock during mute");
        System.out.println("Original recovery checks: "+checks+" PASS");
    }
}
