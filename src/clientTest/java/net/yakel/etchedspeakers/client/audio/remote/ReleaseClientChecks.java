package net.yakel.etchedspeakers.client.audio.remote;

import java.util.*;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.negotiation.*;
import net.neoforged.neoforge.network.registration.*;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.network.RemotePayloads;

/** Release-only fixture: reads actual registered channels and invokes NeoForge negotiation.
 * Never shipped; never changes registrations or opens a connection. */
final class ReleaseClientChecks {
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    @SuppressWarnings("unchecked") static void run() throws Exception {
        check(ModList.get().getModContainerById("etchedspeakers").orElseThrow().getModInfo().getVersion().toString().equals("1.1.0")
                && EtchedSpeakers.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("etchedspeakers-1.1.0.jar"),
                "Release metadata/classes must come from the final JAR");
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS"); field.setAccessible(true);
        var registry=(Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null);
        var ids=Set.of(RemotePayloads.Interest.TYPE.id(),RemotePayloads.Report.TYPE.id(),RemotePayloads.RemoteReport.TYPE.id(),RemotePayloads.Snapshot.TYPE.id());
        var channels=registry.get(ConnectionProtocol.PLAY).values().stream().filter(r->ids.contains(r.id())).map(NegotiableNetworkComponent::new).toList();
        check(channels.size()==4 && channels.stream().allMatch(c->c.version().equals("remote-7-local-original-recovery") && !c.optional()),"Actual remote channels keep the required protocol");
        check(NetworkComponentNegotiator.negotiate(channels,channels).success(),"Matching release protocol negotiates");
        var incompatible=channels.stream().map(c->new NegotiableNetworkComponent(c.id(),"deliberately-incompatible-fixture",c.flow(),false)).toList();
        check(!NetworkComponentNegotiator.negotiate(channels,incompatible).success(),"Incompatible protocol is rejected");
        check(!NetworkComponentNegotiator.negotiate(channels,List.of()).success(),"Missing required channels are rejected");
        EtchedSpeakers.LOGGER.info("[ES-RELEASE-TEST] PASS checks=5 final JAR metadata/classes and actual NeoForge protocol negotiation");
    }
}
