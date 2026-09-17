package net.yakel.etchedspeakers.blockentity;

import net.yakel.etchedspeakers.registry.ModBlockEntities;
import net.yakel.etchedspeakers.source.model.SpeakerSettings;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Stores a generic source address; never resolves it or loads its chunk. */
public final class SpeakerBlockEntity extends BlockEntity {
    private static final String SOURCE_TAG = "Source";
    private static final String DIMENSION_TAG = "Dimension";
    private static final String POS_TAG = "Pos";

    // One optional value keeps dimension and position present/absent together.
    @Nullable
    private GlobalPos source;
    private SpeakerSettings settings = SpeakerSettings.DEFAULT;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEAKER.get(), pos, state);
    }

    public boolean isLinked() {
        return source != null;
    }

    public SpeakerSettings getSettings() { return settings; }
    public float getVolume() { return settings.volume(); }
    public float getAudibleRange() { return settings.audibleRange(); }

    /** Server-authoritative; network permissions are checked by the bound settings menu. */
    public void setSettings(float volume, float audibleRange) {
        if (level != null && level.isClientSide) return;
        var next = new SpeakerSettings(volume, audibleRange);
        if (!settings.equals(next)) {
            settings = next;
            sourceChanged();
        }
    }

    /** Call on the logical server. Validation of the target belongs to the future linking tool. */
    public void setSource(ResourceKey<Level> dimension, BlockPos pos) {
        GlobalPos newSource = GlobalPos.of(Objects.requireNonNull(dimension), Objects.requireNonNull(pos).immutable());
        if (!newSource.equals(source)) {
            source = newSource;
            sourceChanged();
        }
    }

    /** Call on the logical server. Repeated clearing is a no-op. */
    public void clearSource() {
        if (source != null) {
            source = null;
            sourceChanged();
        }
    }

    public Optional<BlockPos> getSourcePos() {
        return Optional.ofNullable(source).map(GlobalPos::pos);
    }

    public Optional<ResourceKey<Level>> getSourceDimension() {
        return Optional.ofNullable(source).map(GlobalPos::dimension);
    }

    private void sourceChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag sourceTag = new CompoundTag();
        if (source != null) {
            sourceTag.putString(DIMENSION_TAG, source.dimension().location().toString());
            sourceTag.putLong(POS_TAG, source.pos().asLong());
        }
        // Keep the update tag nonempty even when unlinked: NeoForge skips empty data packets.
        tag.put(SOURCE_TAG, sourceTag);
        settings.toPersisted().forEach(tag::putFloat);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        var storedSettings = new HashMap<String, Float>();
        for (String key : new String[]{SpeakerSettings.VOLUME_TAG, SpeakerSettings.RANGE_TAG}) {
            if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) storedSettings.put(key, tag.getFloat(key));
        }
        settings = SpeakerSettings.fromPersisted(storedSettings);
        source = null; // Also clears a previous client link when receiving an unlinked update.
        CompoundTag sourceTag = tag.getCompound(SOURCE_TAG);
        if (sourceTag.contains(DIMENSION_TAG, Tag.TAG_STRING) && sourceTag.contains(POS_TAG, Tag.TAG_LONG)) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(sourceTag.getString(DIMENSION_TAG));
            if (dimensionId != null) {
                source = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimensionId),
                        BlockPos.of(sourceTag.getLong(POS_TAG)));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
