package net.yakel.etchedspeakers.tests;

import java.util.*;
import net.yakel.etchedspeakers.source.model.*;

public final class RetentionChecks {
    private static int checks;
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
    public static void run() {
        UUID b=new UUID(0,1), c=new UUID(0,2), epoch=new UUID(1,1), otherEpoch=new UUID(1,2);
        var r=new ListenerRetention<String>();
        check(r.request("A",Set.of(b),0),"first validated listener requests ticket");
        r.ready("A");
        check(r.request("A",Set.of(b,c),10) && r.keys().size()==1 && r.get("A").listeners()==2,"second listener shares source");
        r.reconcile(Map.of("A",Set.of(c)),20);
        check(r.get("A").listeners()==1 && r.get("A").grace()<0,"one disconnect keeps ticket");
        r.reconcile(Map.of(),30);
        check(r.get("A").grace()==230 && !r.expired("A",229),"last disconnect starts exact 200 tick grace");
        r.reconcile(Map.of("A",Set.of(b)),200);
        check(r.get("A").grace()<0 && !r.expired("A",240),"return cancels grace");
        r.reconcile(Map.of(),250); r.reconcile(Map.of(),300);
        check(r.get("A").grace()==450 && r.expired("A",450),"repeated empty reconciliation cannot extend grace");
        check(r.release("A",false) && r.keys().isEmpty(),"grace release removes entry");
        check(!r.release("A",false),"ticket release idempotent");
        check(RemoteTimeline.expired(121,0,RemoteTimeline.INTEREST_LEASE),"stale subscription expires");
        check(!ListenerRetention.validSpeaker(true,true,false,true,1,1,16,false),"fake/unloaded Speaker denied before source lookup");
        check(!ListenerRetention.validSpeaker(true,true,true,false,1,1,16,false),"unlinked Speaker cannot request source");
        check(!ListenerRetention.validSpeaker(true,false,true,true,1,1,16,false),"cross-dimension request denied");
        check(!ListenerRetention.validSpeaker(false,true,true,true,1,1,16,false),"dead player denied");
        check(!ListenerRetention.validSpeaker(true,true,true,true,0,1,16,false),"muted Speaker cannot hold source");
        check(!ListenerRetention.validSpeaker(true,true,true,true,1,100000,16,false),"distant Speaker denied");
        check(!ListenerRetention.validSpeaker(true,true,true,true,1,44*44,16,false)
                && ListenerRetention.validSpeaker(true,true,true,true,1,44*44,16,true),"activation/deactivation hysteresis preserved");
        for(int i=0;i<32;i++) r.request("s"+i,Set.of(b),0);
        check(!r.request("overflow",Set.of(c),0) && r.keys().size()==32,"server-wide cap rejects without eviction");
        check(r.request("s0",Set.of(b,c),1),"existing source accepts listener at cap");
        r.release("s0",false);
        check(r.request("new",Set.of(c),2),"released capacity reusable");
        check(r.release("new",true) && !r.request("new",Set.of(c),3),"invalid BE releases and blocks repeated heartbeats");
        r.reconcile(Map.of(),4);
        check(r.request("new",Set.of(c),5),"a new visit can revalidate replaced source");
        check(r.expired("new",605),"unloaded source timeout bounded");
        r.clear();
        r.request("A",Set.of(b),0); r.request("A",Set.of(b),1); r.request("B",Set.of(b),1);
        check(r.keys().size()==2 && r.get("A").listeners()==1,"many Speakers deduplicate; different sources remain separate");
        r.clear(); check(r.keys().isEmpty(),"shutdown clears all runtime entries");
        check(new ListenerRetention<String>().keys().isEmpty(),"restart has no persisted tickets");

        var lease=new RemoteObserverLease();
        check(!lease.accepts(b,epoch,1,1,0),"client cannot self-elect");
        lease.ready(b,epoch,1,0); lease.ready(c,epoch,2,0);
        check(lease.elect(Set.of(b,c),0) && b.equals(lease.owner()),"server elects one ready candidate");
        long token=lease.tokenFor(b);
        check(lease.accepts(b,epoch,1,token,1) && lease.tokenFor(c)==0,"only assigned decoder has authority");
        check(!lease.accepts(c,epoch,2,token,1),"non-observer EOF rejected");
        check(!lease.accepts(b,otherEpoch,1,token,1),"stale world epoch rejected");
        check(!lease.accepts(b,epoch,99,token,1),"different master rejected");
        check(lease.elect(Set.of(c),10) && c.equals(lease.owner()),"observer disconnect elects ready replacement");
        check(!lease.accepts(b,epoch,1,token,10),"old observer token revoked");
        long secondToken=lease.tokenFor(c);
        lease.ready(c,epoch,3,20); lease.elect(Set.of(c),20);
        check(lease.tokenFor(c)>secondToken && !lease.accepts(c,epoch,2,secondToken,20),"recreated decoder requires fresh assignment");
        check(lease.elect(Set.of(c),121) && lease.owner()==null,"observer heartbeat timeout revokes authority");
        lease.ready(b,epoch,4,130); lease.elect(Set.of(b),130); token=lease.tokenFor(b);
        lease.clear(); lease.ready(b,epoch,4,130); lease.elect(Set.of(b),130);
        check(lease.tokenFor(b)>token,"generation clear never reuses tokens");

        var t=new RemoteTimeline(); t.observe("A",1,"media",441000,44100,false,0,1);
        t.advance(20);
        check(t.at(40)==529200,"server clock continues after local observer disappears");
        for(int tick=40;tick<=2400;tick+=20) t.advance(tick);
        check(t.at(2400)==5733000,"handoff projection continues beyond 60 second horizon");
        long expected=t.at(2400);
        check(t.remoteProgress(expected-100,44100,2400) && t.generation()==1,"remote observer anchors existing session without reset");
        check(!t.remoteProgress(0,44100,2400),"frame zero cannot rewind active track");
        check(!t.remoteProgress(Long.MAX_VALUE,44100,2400),"absurd frame/overflow rejected");
        check(!t.remoteProgress(-1,44100,2400),"negative frame rejected");
        check(!t.remoteProgress(expected,48000,2400),"sample rate mismatch rejected");
        check(!t.matches(0,"media") && !t.matches(1,"wrong"),"stale generation/media rejected before progress/EOF");
        t.bootstrap("media",2,2410);
        check(t.matches(2,"media") && !t.matches(1,"media") && t.at(9999)==0,"same URL next occurrence has fresh generation; pending rate does not invent time");
        check(!t.remoteProgress(999999,48000,2410),"cold session cannot invent historical cursor");
        check(t.remoteProgress(240,48000,2411) && t.at(2431)==48240,"cold bootstrap negotiates actual 48kHz PCM rate");
        t.stop(2431);
        check(!t.remoteProgress(50000,48000,2432) && t.at(3000)==48240,"last listener grace expiry stops permanent playback");
        t.bootstrap("next",3,3001);
        check(t.generation()==3 && t.at(3001)==0,"EOF next starts new generation, not old master reset");
        t.remoteProgress(0,44100,3001);
        check(t.at(3021)==44100,"new track accepts different actual sample rate");
        t.advance(3241);
        check(!t.acceptsRemoteCursor(44100,44100,3241,false) && t.acceptsRemoteCursor(44100,44100,3241,true),
                "EOF reached during handoff can end at real frame; normal progress cannot rewind by 11 seconds");
        check(!t.acceptsRemoteCursor(44100,44100,3421,true),"EOF handoff window remains bounded");
        check(!t.acceptsRemoteCursor(44100*20L,44100,3241,true),"EOF cannot jump arbitrarily ahead");
        check(t.remoteProgress(44100,44100,3241,true),"assigned handoff EOF anchors real end before next generation");
        System.out.println("Listener retention checks: "+checks+" PASS");
    }
}
