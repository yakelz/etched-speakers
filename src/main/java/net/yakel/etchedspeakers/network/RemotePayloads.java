package net.yakel.etchedspeakers.network;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.remote.RemoteSessions;

/** Control only. No PCM. Bounded wire fields; semantic validation belongs to the owning thread. */
public final class RemotePayloads {
    public static Consumer<Snapshot> clientReceiver = ignored -> {};
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(EtchedSpeakers.MOD_ID, name));
    }
    public record Report(GlobalPos source, String media, int slot, int index, long localId,
                         long frame, int rate, boolean paused, boolean eof) implements CustomPacketPayload {
        public static final Type<Report> TYPE = RemotePayloads.type("playback_report");
        public static final StreamCodec<FriendlyByteBuf, Report> CODEC = new StreamCodec<>() {
            public Report decode(FriendlyByteBuf b) { return new Report(GlobalPos.STREAM_CODEC.decode(b), b.readUtf(160),
                    b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readBoolean(), b.readBoolean()); }
            public void encode(FriendlyByteBuf b, Report p) { GlobalPos.STREAM_CODEC.encode(b,p.source); b.writeUtf(p.media,160);
                b.writeVarInt(p.slot); b.writeVarInt(p.index); b.writeVarLong(p.localId); b.writeVarLong(p.frame);
                b.writeVarInt(p.rate); b.writeBoolean(p.paused); b.writeBoolean(p.eof); }
        };
        public Type<Report> type() { return TYPE; }
    }
    /** Ready (token=0), assigned progress/EOF (token>0). Identity bound to subscription and decoder. */
    public record RemoteReport(UUID epoch, GlobalPos source, long generation, String media, long master,
            long token, long frame, int rate, boolean eof) implements CustomPacketPayload {
        public static final Type<RemoteReport> TYPE=RemotePayloads.type("remote_observer_report");
        public static final StreamCodec<FriendlyByteBuf,RemoteReport> CODEC=new StreamCodec<>() {
            public RemoteReport decode(FriendlyByteBuf b) { return new RemoteReport(b.readUUID(),GlobalPos.STREAM_CODEC.decode(b),
                    b.readVarLong(),b.readUtf(160),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarInt(),b.readBoolean()); }
            public void encode(FriendlyByteBuf b, RemoteReport p) { b.writeUUID(p.epoch); GlobalPos.STREAM_CODEC.encode(b,p.source);
                b.writeVarLong(p.generation); b.writeUtf(p.media,160); b.writeVarLong(p.master); b.writeVarLong(p.token);
                b.writeVarLong(p.frame); b.writeVarInt(p.rate); b.writeBoolean(p.eof); }
        };
        public Type<RemoteReport> type() { return TYPE; }
    }
    public record Interest(UUID epoch, ResourceLocation dimension, List<BlockPos> speakers) implements CustomPacketPayload {
        public Interest { speakers = List.copyOf(speakers); }
        public static final Type<Interest> TYPE = RemotePayloads.type("speaker_interest");
        public static final StreamCodec<FriendlyByteBuf, Interest> CODEC = new StreamCodec<>() {
            public Interest decode(FriendlyByteBuf b) {
                UUID epoch=b.readUUID(); var dim=b.readResourceLocation(); int n=b.readVarInt();
                if(n<0 || n>32) throw new IllegalArgumentException("Invalid interest count");
                var positions=new java.util.ArrayList<BlockPos>(n); for(int i=0;i<n;i++) positions.add(b.readBlockPos());
                return new Interest(epoch,dim,positions);
            }
            public void encode(FriendlyByteBuf b, Interest p) { b.writeUUID(p.epoch); b.writeResourceLocation(p.dimension);
                b.writeVarInt(p.speakers.size()); p.speakers.forEach(b::writeBlockPos); }
        };
        public Type<Interest> type() { return TYPE; }
    }
    public record Snapshot(UUID epoch, GlobalPos source, long generation, String media, String location,
            int slot, int index, long frame, int rate, long serverTick, boolean active, boolean paused, String reason,
            long observerToken, boolean remoteOwned, boolean localSource)
            implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = RemotePayloads.type("remote_snapshot");
        public static final StreamCodec<FriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            public Snapshot decode(FriendlyByteBuf b) { return new Snapshot(b.readUUID(),GlobalPos.STREAM_CODEC.decode(b),
                    b.readVarLong(),b.readUtf(160),b.readUtf(8192),b.readVarInt(),b.readVarInt(),b.readVarLong(),
                    b.readVarInt(),b.readVarLong(),b.readBoolean(),b.readBoolean(),b.readUtf(64),b.readVarLong(),b.readBoolean(),b.readBoolean()); }
            public void encode(FriendlyByteBuf b, Snapshot p) { b.writeUUID(p.epoch); GlobalPos.STREAM_CODEC.encode(b,p.source);
                b.writeVarLong(p.generation); b.writeUtf(p.media,160); b.writeUtf(p.location,8192);
                b.writeVarInt(p.slot); b.writeVarInt(p.index); b.writeVarLong(p.frame); b.writeVarInt(p.rate);
                b.writeVarLong(p.serverTick); b.writeBoolean(p.active); b.writeBoolean(p.paused); b.writeUtf(p.reason,64);
                b.writeVarLong(p.observerToken); b.writeBoolean(p.remoteOwned); b.writeBoolean(p.localSource); }
        };
        public Type<Snapshot> type() { return TYPE; }
        @Override public String toString() { return "RemoteSnapshot[source="+source+",generation="+generation+",media="+media+"]"; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r=event.registrar("remote-3-local-canonical");
        r.playToServer(RemoteReport.TYPE, RemoteReport.CODEC, (p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player) RemoteSessions.remoteReport(player,p);
        }));
        r.playToServer(Report.TYPE, Report.CODEC, (p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player) RemoteSessions.report(player,p);
        }));
        r.playToServer(Interest.TYPE, Interest.CODEC, (p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player) RemoteSessions.interest(player,p);
        }));
        r.playToClient(Snapshot.TYPE, Snapshot.CODEC, (p,c)->c.enqueueWork(()->clientReceiver.accept(p)));
    }
}
