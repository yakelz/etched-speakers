package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.NativeDiscClock;
import net.yakel.etchedspeakers.source.model.RemoteTimeline;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment;

final class NativeDiscChecks {
    private static int checks;
    static void run() {
        long now=5_000_000_000L;
        var clock=new NativeDiscClock(600,now,0);
        check(clock.target(48000,now)==1_440_000,"30-second native join is nonzero at actual 48 kHz");
        check(clock.target(44100,now)==1_323_000,"actual 44.1 kHz format converts independently");
        check(clock.target(48000,now+1_000_000_000L)==1_488_000,"projection between server snapshots");
        check(clock.target(48000,now+25_000_000L)==1_441_200,"sub-tick pre-roll follows projected target");
        check(new NativeDiscClock(600,now,2).target(48000,now)==1_444_800,"delivery delay applied once");
        check(new NativeDiscClock(600,now,-5).target(48000,now)==1_440_000,"negative delay is bounded");
        check(new NativeDiscClock(600,now,999).target(48000,now)==1_536_000,"delay is bounded at 40 ticks");
        check(clock.target(48000,now-1)==1_440_000,"negative wall delta cannot rewind");
        check(clock.target(0,now)==-1,"unknown rate is not guessed");
        check(clock.target(8000,now)==240_000,"supported low rate");
        check(clock.target(192000,now)==5_760_000,"supported high rate");
        check(clock.target(192001,now)==-1,"unsupported rate rejected");
        check(!NativeDiscClock.validTicks(-1),"absent cursor is distinct from zero");
        check(!NativeDiscClock.validTicks(RemoteTimeline.MAX_SECONDS*20+1),"unbounded cursor rejected");
        check(new NativeDiscClock(0,now,0).target(48000,now)==0,"real new insertion starts at zero");
        check(new NativeDiscClock(600,now,20,true).target(48000,now+2_000_000_000L)==1_440_000,
                "unticking server source does not invent elapsed playback");
        check(new NativeDiscClock(RemoteTimeline.MAX_SECONDS*20,now,40).target(48000,now+1_000_000_000L)
                ==RemoteTimeline.MAX_SECONDS*48000,"target bounded without overflow");
        check(!RemoteTimeline.acceptsSnapshot(1,999,2,5),"previous-disc generation rejected");
        check(!RemoteTimeline.acceptsSnapshot(2,4,2,5),"older snapshot rejected");
        check(RemoteTimeline.acceptsSnapshot(3,1,2,5),"new insertion accepted even after clock reset");
        check(new CanonicalAlignment.Clock(44100,44100,false,now,0).target(now+1_000_000_000L)==88200,
                "Album clock retains PCM-based projection");
        System.out.println("Native disc timeline checks: "+checks+" PASS");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
}
