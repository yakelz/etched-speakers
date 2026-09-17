package net.yakel.etchedspeakers.source.model;

public enum SourceType {
    VANILLA_JUKEBOX("message.etchedspeakers.source_selected_jukebox"),
    ALBUM_JUKEBOX("message.etchedspeakers.source_selected_album");

    private final String selectionMessage;

    SourceType(String selectionMessage) {
        this.selectionMessage = selectionMessage;
    }

    public String selectionMessage() {
        return selectionMessage;
    }
}
