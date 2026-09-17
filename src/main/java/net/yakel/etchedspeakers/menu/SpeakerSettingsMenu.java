package net.yakel.etchedspeakers.menu;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.registry.ModMenus;
import net.yakel.etchedspeakers.source.model.SpeakerSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** Server-bound editing session, without item slots. Safe on dedicated servers. */
public final class SpeakerSettingsMenu extends AbstractContainerMenu {
    private final SpeakerBlockEntity speaker; // Server instance; null in client menu.
    private final Direction face;
    private final ContainerData data;

    public SpeakerSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        super(ModMenus.SPEAKER_SETTINGS.get(), id);
        speaker = null;
        face = Direction.UP;
        data = new SimpleContainerData(2);
        var initial = new SpeakerSettings(extra.readFloat(), extra.readFloat());
        data.set(0, Math.round(initial.volume() * 100));
        data.set(1, Math.round(initial.audibleRange()));
        addDataSlots(data);
    }

    public SpeakerSettingsMenu(int id, Inventory inventory, SpeakerBlockEntity speaker, Direction face) {
        super(ModMenus.SPEAKER_SETTINGS.get(), id);
        this.speaker = speaker;
        this.face = face;
        data = new ContainerData() {
            public int get(int index) { return index == 0 ? Math.round(speaker.getVolume() * 100) : Math.round(speaker.getAudibleRange()); }
            public void set(int index, int value) {} // S2C view only; client packets cannot write through DataSlot.
            public int getCount() { return 2; }
        };
        addDataSlots(data);
    }

    public SpeakerSettings settings() { return new SpeakerSettings(data.get(0) / 100.0F, data.get(1)); }

    public static boolean canEdit(Player player, SpeakerBlockEntity speaker, Direction face) {
        if (player == null || !player.isAlive() || player.isSpectator() || speaker.isRemoved()
                || speaker.getLevel() != player.level()) return false;
        BlockPos pos = speaker.getBlockPos();
        var chunk = player.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) == speaker
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64
                && player.canInteractWithBlock(pos, 0.0)
                && player.level().mayInteract(player, pos)
                && player.mayUseItemAt(pos.relative(face), face, player.getMainHandItem());
    }

    public void apply(Player player, float volume, float range) {
        if (speaker != null && !player.level().isClientSide && canEdit(player, speaker, face)) {
            speaker.setSettings(volume, range); // Clamps again at the persistence boundary.
            broadcastChanges();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide || (speaker != null && canEdit(player, speaker, face));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
