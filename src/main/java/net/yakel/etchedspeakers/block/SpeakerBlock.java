package net.yakel.etchedspeakers.block;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.item.LinkingToolItem;
import net.yakel.etchedspeakers.menu.SpeakerSettingsMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

public final class SpeakerBlock extends BaseEntityBlock {
    public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public SpeakerBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof LinkingToolItem || player.getOffhandItem().getItem() instanceof LinkingToolItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isShiftKeyDown()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        return openSettings(level, pos, player, hit).consumesAction()
                ? ItemInteractionResult.sidedSuccess(level.isClientSide) : ItemInteractionResult.FAIL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.getMainHandItem().getItem() instanceof LinkingToolItem
                || player.getOffhandItem().getItem() instanceof LinkingToolItem) return InteractionResult.PASS;
        return openSettings(level, pos, player, hit);
    }

    private InteractionResult openSettings(Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)
                || !SpeakerSettingsMenu.canEdit(player, speaker, hit.getDirection())) return InteractionResult.FAIL;
        player.openMenu(new SimpleMenuProvider((id, inventory, opener) ->
                new SpeakerSettingsMenu(id, inventory, speaker, hit.getDirection()),
                Component.translatable("screen.etchedspeakers.settings")), buffer -> {
                    buffer.writeFloat(speaker.getVolume());
                    buffer.writeFloat(speaker.getAudibleRange());
                });
        return InteractionResult.CONSUME;
    }
}
