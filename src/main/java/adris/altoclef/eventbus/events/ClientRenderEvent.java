package adris.altoclef.eventbus.events;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ClientRenderEvent {
    public final GuiGraphicsExtractor extractor;
    public final DeltaTracker deltaTracker;

    public ClientRenderEvent(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker) {
        this.extractor = extractor;
        this.deltaTracker = deltaTracker;
    }
}
