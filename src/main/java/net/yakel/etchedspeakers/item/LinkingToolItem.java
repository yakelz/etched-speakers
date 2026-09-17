package net.yakel.etchedspeakers.item;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.component.SelectedSource;
import net.yakel.etchedspeakers.registry.ModDataComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

public final class LinkingToolItem extends Item {
    public static final int MAX_LINK_DISTANCE = 256;

    public LinkingToolItem(Properties properties) {
        super(properties);
    }

    // NeoForge calls this before block interaction, including Album Jukebox's menu/ejection behavior.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        if (player == null || !stack.is(this) || !AudioSourceResolver.isChunkLoaded(level, pos)) {
            return InteractionResult.PASS;
        }

        SpeakerBlockEntity speaker = level.getBlockEntity(pos) instanceof SpeakerBlockEntity entity ? entity : null;
        var sourceType = AudioSourceResolver.getSourceType(level, pos);
        if (speaker == null && sourceType.isEmpty()) {
            return InteractionResult.PASS; // Leave unrelated block interactions alone.
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS; // Animation only; no predicted writes to item or BE.
        }
        if (player.isSpectator() || !player.canInteractWithBlock(pos, 0.0)
                || !level.mayInteract(player, pos)
                || !player.mayUseItemAt(pos.relative(context.getClickedFace()), context.getClickedFace(), stack)) {
            message(player, "interaction_denied");
            return InteractionResult.FAIL;
        }

        if (speaker != null) {
            if (player.isShiftKeyDown()) {
                speaker.clearSource();
                message(player, "speaker_unlinked");
            } else {
                linkSpeaker(player, level, stack, speaker);
            }
        } else {
            stack.set(ModDataComponents.SELECTED_SOURCE.get(), new SelectedSource(level.dimension(), pos));
            player.displayClientMessage(Component.translatable(sourceType.orElseThrow().selectionMessage()), true);
        }
        return InteractionResult.CONSUME; // No record ejection, menu opening, or offhand fallback.
    }

    private static void linkSpeaker(Player player, Level level, ItemStack stack, SpeakerBlockEntity speaker) {
        SelectedSource selected = stack.get(ModDataComponents.SELECTED_SOURCE.get());
        if (selected == null) {
            if (speaker.isLinked()) {
                BlockPos pos = speaker.getSourcePos().orElseThrow();
                message(player, "speaker_source", speaker.getSourceDimension().orElseThrow().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ());
            } else {
                message(player, "no_source_selected");
            }
            return;
        }
        if (!selected.dimension().equals(level.dimension())) {
            message(player, "another_dimension");
            return;
        }
        if (selected.pos().distSqr(speaker.getBlockPos()) > (double) MAX_LINK_DISTANCE * MAX_LINK_DISTANCE) {
            message(player, "source_too_far");
            return;
        }
        if (!AudioSourceResolver.isChunkLoaded(level, selected.pos())) {
            message(player, "source_chunk_unloaded");
            return;
        }
        if (!AudioSourceResolver.isSupportedSource(level, selected.pos())) {
            message(player, "source_missing");
            return;
        }

        speaker.setSource(selected.dimension(), selected.pos());
        message(player, "speaker_linked", selected.pos().getX(), selected.pos().getY(), selected.pos().getZ());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // use() may also be the fallback for a block click. Clear only while actually targeting air.
        if (!player.isShiftKeyDown() || player.isSpectator()
                || getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE).getType() != HitResult.Type.MISS) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            stack.remove(ModDataComponents.SELECTED_SOURCE.get());
            message(player, "selection_cleared");
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        SelectedSource selected = stack.get(ModDataComponents.SELECTED_SOURCE.get());
        if (selected == null) {
            tooltip.add(Component.translatable("message.etchedspeakers.no_source_selected").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.etchedspeakers.source", selected.dimension().location().toString())
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.etchedspeakers.position",
                    selected.pos().getX(), selected.pos().getY(), selected.pos().getZ()).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.etchedspeakers.linking_tool.use").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.etchedspeakers.linking_tool.clear").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void message(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.etchedspeakers." + key, args), true);
    }
}
