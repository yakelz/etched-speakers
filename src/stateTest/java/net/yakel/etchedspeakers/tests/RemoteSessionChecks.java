package net.yakel.etchedspeakers.tests;

import java.nio.ByteBuffer;
import java.util.*;
import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.*;

public final class RemoteSessionChecks {
    private static int checks;
    private static void check(boolean condition,String name) { if(!condition) throw new AssertionError(name); checks++; }
    public static void run() {
        var track=new TrackReference(TrackReference.Kind.URL,"https://example.invalid/a",Optional.empty(),0,0);
        var allowed=new SourcePlaybackState(Availability.AVAILABLE,Optional.of(SourceType.ALBUM_JUKEBOX),Playback.UNKNOWN,
                List.of(track),Optional.empty(),Optional.empty(),0,Reason.ALBUM_CLIENT_SEQUENCE);
        check(RemoteTimeline.sourceAllowed(allowed,track.mediaKey()),"Actual inventory media allowed without stale selection");
        check(!RemoteTimeline.sourceAllowed(allowed,"url:sha256:invented"),"Invalid track rejected");
        var missing=SourcePlaybackState.unavailable(Availability.SOURCE_MISSING,0);
        check(!RemoteTimeline.sourceAllowed(missing,track.mediaKey()),"Missing source rejected");
        check(!RemoteTimeline.sourceAllowed(SourcePlaybackState.unavailable(Availability.CHUNK_UNLOADED,0),track.mediaKey()),"Server unloaded source rejected");
        check(!RemoteTimeline.valid(-1,44100),"Negative cursor rejected");
        check(!RemoteTimeline.valid(Long.MAX_VALUE,44100),"Overflow cursor rejected");
        check(!RemoteTimeline.valid(0,1),"Invalid rate rejected");
        check(RemoteTimeline.project(44100,44100,20,false)==88200,"Project measured frame one second");
        check(RemoteTimeline.project(44100,44100,20,true)==44100,"Pause has no frame advance");
        check(RemoteTimeline.project(44100,44100,-20,false)==44100,"Stale clock cannot rewind");
        var t=new RemoteTimeline();
        check(t.observe("A",1,track.mediaKey(),44100*60L,44100,false,100,1).equals("CREATE"),"Bootstrap actual minute into track");
        check(t.at(120)==44100*61L,"Canonical advances from observer frame");
        check(t.observe("A",2,track.mediaKey(),0,44100,false,120,2).equals("CONTINUE"),"Same media frame zero rebind");
        check(t.generation()==1 && t.at(120)==44100*61L,"Rebind preserves timeline and generation");
        t.observe("A",2,track.mediaKey(),44100,44100,false,140,2);
        check(t.at(140)==44100*62L,"Rebound reports keep their offset");
        check(t.observe("B",7,track.mediaKey(),0,44100,false,145,2).equals("REJECTED"),"Sticky observer rejects competing clock");
        check(!t.eof("B",7,145),"Foreign observer EOF ignored");
        check(!t.eof("A",1,145),"Old master EOF ignored");
        check(t.eof("A",2,145),"Actual observer EOF terminal");
        check(t.observe("A",2,track.mediaKey(),0,44100,false,146,2).equals("REJECTED"),"Old terminal heartbeat cannot restart");
        check(t.observe("A",3,track.mediaKey(),0,44100,false,147,2).equals("CREATE"),"Same media after EOF is new generation");
        check(t.generation()==2 && t.at(147)==0,"Real replay can start zero");
        check(t.observe("A",4,"other",200,44100,false,148,3).equals("TRACK_CHANGE"),"Actual different media new generation");
        check(t.generation()==3,"Track change generation assigned");
        check(!RemoteTimeline.acceptsSnapshot(2,9999,3,150),"Stale generation rejected");
        check(!RemoteTimeline.acceptsSnapshot(3,149,3,150),"Out of order snapshot rejected");
        check(RemoteTimeline.acceptsSnapshot(4,151,3,150),"New snapshot accepted");
        var t2=new RemoteTimeline(); t2.observe("A",4,"other",0,44100,false,148,4);
        check(t.at(148)==200 && t2.at(148)==0,"Two sources same media stay independent");
        check(!RemoteTimeline.expired(120,0,120) && RemoteTimeline.expired(121,0,120),"Bounded subscription lease");
        check(SpeakerSelection.inRange(10*10,32,true),"Speaker relevance depends on its own distance");
        // A client chunk observation is not a server terminal event: no stop is applied to the canonical model.
        var clientMissing=SourcePlaybackState.unavailable(Availability.CHUNK_UNLOADED,149);
        check(!clientMissing.available() && t.active() && t.at(168)==44300,"Client source absence does not stop canonical audio");
        check(t.stop(169) && !t.active(),"Server unload/stop terminates session");
        check(!t.stop(170),"Cleanup idempotent");
        check(!t.active() && RemotePlaybackPolicy.keepLocal(true,true,false,true,75*75),
                "Integrated local playback survives canonical observer timeout beyond 64 blocks");
        check(RemotePlaybackPolicy.keepLocal(true,true,false,false,200*200),
                "Integrated surviving local master does not require a client source chunk");
        check(!RemotePlaybackPolicy.keepLocal(false,true,true,true,10*10),
                "Multiplayer remote latch cannot switch to a recreated local frame-zero master");
        check(!RemotePlaybackPolicy.keepLocal(false,false,false,false,200*200),
                "New remote client without any local master bootstraps remotely");
        check(RemotePlaybackPolicy.keepLocal(false,true,false,true,10*10),
                "Nearby multiplayer original keeps the local tee path");
        check(t2.canObserve("B",249),"Observer can be replaced after lease");
        var pcm=new PcmWindow(10,2); pcm.startAt(60000);
        check(pcm.startFrame()==60000 && pcm.endFrame()==60000,"Remote window starts at prepared frame without fake PCM");
        pcm.append(ByteBuffer.wrap(new byte[]{1,2,3,4}),1);
        var out=ByteBuffer.allocate(4); check(pcm.copy(60000,2,out)==2 && out.get(0)==1,"Current-frame copy at absolute origin");
        pcm.clear(); check(pcm.endFrame()==0 && pcm.startFrame()==0,"Origin cleanup");
        System.out.println("Remote session checks passed: "+checks);
    }
}
