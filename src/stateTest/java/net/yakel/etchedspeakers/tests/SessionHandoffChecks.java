package net.yakel.etchedspeakers.tests;

import java.util.*;
import net.yakel.etchedspeakers.source.model.*;

/** Production lifecycle and observer models exercised through both directions of the handoff. */
final class SessionHandoffChecks {
    private static int checks;
    private static final UUID A=new UUID(0,1),B=new UUID(0,2),EPOCH=new UUID(1,1);
    private static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); checks++; }
    static void run() {
        var life=new ListenerRetention<String>(ListenerRetention.HANDOFF_GRACE);
        var timeline=new RemoteTimeline(); timeline.bootstrap("media",7,0); timeline.remoteProgress(0,44100,0);
        check(life.request("source",Set.of(B),Set.of(),0),"validated remote admits source"); life.ready("source");
        check(life.get("source").ticketNeeded(),"remote listener needs ticket");
        life.reconcile(Map.of(),Map.of("source",Set.of(A)),20); timeline.advance(20);
        check(life.get("source")!=null && !life.expired("source",20),"remote departure with local holder preserves session");
        check(!life.get("source").ticketNeeded(),"local holder releases redundant ticket without releasing entry");
        check(life.get("source").listeners()==0 && life.get("source").holders()==1,"local holder is not remote output interest");
        check(timeline.generation()==7 && timeline.at(20)==44100,"role change keeps generation and increasing clock");
        life.reconcile(Map.of(),Map.of("source",Set.of(A,B)),40);
        check(life.keys().size()==1 && life.get("source").holders()==2,"multiple holders share one source identity");
        life.reconcile(Map.of(),Map.of(),60);
        check(life.get("source").grace()==1260,"last local holder starts full 1200 tick grace");
        check(life.get("source").ticketNeeded(),"transit reacquires ticket");
        life.reconcile(Map.of(),Map.of(),80);
        check(life.get("source").grace()==1260,"maintenance never extends abandoned grace");
        life.reconcile(Map.of("source",Set.of(B)),Map.of(),1000); timeline.advance(1000);
        check(life.get("source").grace()==-1 && timeline.generation()==7,"source to transit to Speaker under 60s keeps generation");
        check(life.get("source").ticketNeeded() && life.get("source").listeners()==1,"returning Speaker holds ticket");
        life.reconcile(Map.of(),Map.of(),1020);
        check(life.get("source").grace()==2220,"remote loss starts a new bounded handoff");
        life.reconcile(Map.of(),Map.of("source",Set.of(B)),2000); timeline.advance(2000);
        check(life.get("source").grace()==-1 && timeline.generation()==7,"Speaker to transit to source under 60s keeps generation");
        check(!life.get("source").ticketNeeded(),"arrival at source drops transit ticket");
        life.reconcile(Map.of(),Map.of(),2020);
        check(!life.expired("source",3219),"still alive just before grace deadline");
        check(life.expired("source",3220),"expires at sixty seconds with no roles");
        check(life.release("source",false) && timeline.stop(3220),"expiry closes logical entry and canonical clock");
        check(life.get("source")==null,"expired source has no ticket demand");
        check(!life.release("source",false) && !timeline.stop(3221),"cleanup idempotent");
        check(!life.request("abandoned",Set.of(),Set.of(),3300),"no grace admission without validated interest");
        life.request("source",Set.of(B),Set.of(),3400); timeline.bootstrap("media",8,3400);
        check(timeline.generation()==8 && timeline.at(3400)==0,"later interest after expiry may cold bootstrap");
        life.clear(); check(life.keys().isEmpty(),"shutdown clears all roles and grace");
        for(int i=0;i<32;i++) { life.request("s"+i,Set.of(),Set.of(A),0); life.ready("s"+i); }
        check(life.keys().size()==32 && !life.request("overflow",Set.of(B),Set.of(),1),"32 identity cap includes local holders");
        life.reconcile(Map.of(),Map.of(),2);
        check(!life.request("overflow",Set.of(B),Set.of(),3),"transit entries still consume cap");
        check(life.keys().stream().allMatch(k->life.get(k).ticketNeeded()),"all transit entries remain bounded ticket demands");
        life.release("s0",false);
        check(life.request("overflow",Set.of(B),Set.of(),4),"release frees exactly one admission slot");

        var remote=new RemoteObserverLease(); var local=new RemoteObserverLease();
        timeline.bootstrap("media",9,0); timeline.remoteProgress(0,44100,0); timeline.advance(800);
        remote.ready(B,EPOCH,10,800); remote.elect(Set.of(B),800);
        check(remote.accepts(B,EPOCH,10,remote.tokenFor(B),800),"ready remote assigned");
        remote.elect(Set.of(),801);
        local.ready(A,EPOCH,11,801); local.elect(Set.of(A),801);
        check(remote.owner()==null && local.accepts(A,EPOCH,11,local.tokenFor(A),801),"aligned local can be assigned after remote leaves");
        long frame=timeline.at(801);
        check(timeline.remoteProgress(frame,44100,801) && timeline.generation()==9,"local authority handoff does not reset cursor/generation");
        check(!timeline.acceptsRemoteCursor(0,44100,801,false),"raw frame-zero recreation cannot anchor current timeline");
        check(!CanonicalAlignment.acceptsLocalObservation(true),"legacy unaligned report remains barred while canonical-owned");
        check(!timeline.matches(0,"media") && !timeline.matches(8,"media") && !timeline.matches(9,"other"),"local proof requires current generation and media");
        check(!local.accepts(A,new UUID(2,2),11,local.tokenFor(A),801),"wrong client epoch rejected");
        check(!local.accepts(A,EPOCH,12,local.tokenFor(A),801),"replaced local master cannot reuse lease");
        check(!local.accepts(A,EPOCH,11,0,801),"READY alone never authorizes local clock mutation");
        remote.ready(B,EPOCH,20,802); remote.elect(Set.of(B),802); local.elect(Set.of(),802);
        check(remote.owner().equals(B) && local.owner()==null && timeline.generation()==9,"remote resumes without two authorities or generation reset");
        timeline.advance(1800);
        check(timeline.acceptsHandoffEnd(44100*50L,44100,1800),"actual EOF crossed during transit can be accepted");
        check(!timeline.acceptsRemoteCursor(44100*50L,44100,1800,false),"wide terminal window never accepts ordinary backward progress");
        check(!timeline.acceptsHandoffEnd(0,48000,1800),"EOF format mismatch rejected");
        check(!timeline.acceptsHandoffEnd(44100*100L,44100,1800),"EOF cannot jump forward past tolerance");
        check(timeline.handoffEnd(44100*50L,44100,1800) && timeline.generation()==9,"assigned real EOF anchors end before normal next selection");
        timeline.bootstrap("next",10,1800);
        check(timeline.generation()==10 && timeline.at(1800)==0,"only actual track advance creates next generation");
        timeline.remoteProgress(0,44100,1800);
        // Production advances every server tick; a single projection deliberately caps large gaps.
        for (long tick=1820;tick<=4000;tick+=20) timeline.advance(tick);
        check(!timeline.acceptsHandoffEnd(0,44100,4000),"late terminal evidence remains bounded to ninety seconds");
        local.clear(); remote.clear();
        check(local.owner()==null && remote.owner()==null,"observer cleanup clears both roles");
        System.out.println("Session handoff checks: "+checks+" PASS");
    }
}
