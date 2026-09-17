package net.yakel.etchedspeakers.component;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable item component; delegates both wire and disk formats to Minecraft's GlobalPos. */
public record SelectedSource(ResourceKey<Level> dimension, BlockPos pos) {
    public static final Codec<SelectedSource> CODEC = GlobalPos.CODEC.xmap(
            value -> new SelectedSource(value.dimension(), value.pos()), SelectedSource::asGlobalPos);
    public static final StreamCodec<ByteBuf, SelectedSource> STREAM_CODEC = GlobalPos.STREAM_CODEC.map(
            value -> new SelectedSource(value.dimension(), value.pos()), SelectedSource::asGlobalPos);

    public SelectedSource {
        Objects.requireNonNull(dimension, "dimension");
        pos = Objects.requireNonNull(pos, "pos").immutable();
    }

    private GlobalPos asGlobalPos() {
        return GlobalPos.of(dimension, pos);
    }
}
