package net.yakel.etchedspeakers.client.gui;

import net.yakel.etchedspeakers.menu.SpeakerSettingsMenu;
import net.yakel.etchedspeakers.network.SpeakerSettingsPayload;
import net.yakel.etchedspeakers.source.model.SpeakerSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** No inventory slots, textures or per-frame network traffic. Changes commit on release/reset/close. */
public final class SpeakerSettingsScreen extends AbstractContainerScreen<SpeakerSettingsMenu> {
    private SettingSlider volume;
    private SettingSlider range;
    private SpeakerSettings lastSent;
    private SpeakerSettings lastObserved;
    private boolean dirty;

    public SpeakerSettingsScreen(SpeakerSettingsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 260;
        imageHeight = 168;
    }

    @Override
    protected void init() {
        var initial = volume == null ? menu.settings() : selectedSettings();
        super.init();
        lastObserved = menu.settings();
        if (lastSent == null) lastSent = initial;
        volume = addRenderableWidget(new SettingSlider(leftPos + 18, topPos + 44, 224, true, initial.volume()));
        range = addRenderableWidget(new SettingSlider(leftPos + 18, topPos + 82, 224, false, initial.audibleRange()));
        addRenderableWidget(Button.builder(Component.translatable("screen.etchedspeakers.reset"), button -> {
            volume.setDisplayValue(SpeakerSettings.DEFAULT_VOLUME);
            range.setDisplayValue(SpeakerSettings.DEFAULT_RANGE);
            dirty = true;
            commit();
        }).bounds(leftPos + 18, topPos + 124, 108, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(leftPos + 134, topPos + 124, 108, 20).build());
    }

    private SpeakerSettings selectedSettings() { return new SpeakerSettings(volume.settingValue(), range.settingValue()); }

    private void commit() {
        if (!dirty || volume == null || minecraft.player == null || minecraft.player.containerMenu != menu) return;
        var selected = selectedSettings();
        if (!selected.equals(lastSent)) {
            PacketDistributor.sendToServer(new SpeakerSettingsPayload(menu.containerId, selected.volume(), selected.audibleRange()));
            lastSent = selected;
        }
        dirty = false;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        var current = menu.settings();
        if (!dirty && !volume.dragging && !range.dragging && !current.equals(lastObserved)) {
            volume.setDisplayValue(current.volume());
            range.setDisplayValue(current.audibleRange());
            lastObserved = current;
            lastSent = current;
        }
    }

    @Override
    public void onClose() {
        commit(); // Also saves keyboard edits / a drag interrupted by Escape, before vanilla close packet.
        super.onClose();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (getFocused() instanceof SettingSlider slider && slider.dragging) {
            return slider.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // A release outside the widget must also commit the drag.
        if (getFocused() instanceof SettingSlider slider && slider.dragging && button == 0) {
            slider.onRelease(mouseX, mouseY);
            setDragging(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF89959B);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF252B30);
        graphics.fill(leftPos + 18, topPos + 31, leftPos + imageWidth - 18, topPos + 32, 0xFF56636C);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(font, title, imageWidth / 2, 14, 0xFFFFFF);
    }

    private final class SettingSlider extends AbstractSliderButton {
        private final boolean isVolume;
        private boolean dragging;

        SettingSlider(int x, int y, int width, boolean isVolume, float initial) {
            super(x, y, width, 20, Component.empty(), 0);
            this.isVolume = isVolume;
            setDisplayValue(initial);
        }

        float settingValue() {
            return isVolume ? Math.round(value * 200) / 100.0F : (float) Math.round(8 + value * 120);
        }

        void setDisplayValue(float setting) {
            value = isVolume ? setting / 2.0 : (setting - 8.0) / 120.0;
            updateMessage();
        }

        @Override protected void updateMessage() {
            setMessage(isVolume
                    ? Component.translatable("screen.etchedspeakers.volume").append(": " + Math.round(settingValue() * 100) + "%")
                    : Component.translatable("screen.etchedspeakers.range").append(": " + Math.round(settingValue()) + " ")
                            .append(Component.translatable("screen.etchedspeakers.blocks")));
        }

        @Override protected void applyValue() { dirty = true; }

        @Override public void onClick(double mouseX, double mouseY) {
            dragging = true;
            super.onClick(mouseX, mouseY);
        }

        @Override public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            dragging = false;
            commit();
        }

        @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
            commit();
            return super.keyReleased(keyCode, scanCode, modifiers);
        }
    }
}
