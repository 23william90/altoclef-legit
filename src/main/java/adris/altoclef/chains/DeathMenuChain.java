package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;

public class DeathMenuChain extends TaskChain {

    private long deathScreenSeen = 0;
    private boolean handlingDeath = false;

    public DeathMenuChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return Float.NEGATIVE_INFINITY;

        if (mc.gui != null && mc.gui.screen() instanceof DeathScreen) {
            return 110.0f;
        }

        LocalPlayer player = mc.player;
        if (player != null && !player.isAlive()) {
            return 110.0f;
        }

        handlingDeath = false;
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    protected void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!handlingDeath) {
            handlingDeath = true;
            deathScreenSeen = System.currentTimeMillis();
            Debug.logWarning("Player died! Handling auto-respawn and recovering tasks...");
        }

        // Wait ~500ms on death screen before respawning for server sync
        if (System.currentTimeMillis() - deathScreenSeen > 500) {
            try {
                player.respawn();
                if (mc.gui != null) {
                    mc.gui.setScreen(null);
                }
                Debug.logMessage("Respawned successfully.");
            } catch (Throwable t) {
                t.printStackTrace();
            }
            handlingDeath = false;
        }
    }

    @Override
    protected void onStop() {
        handlingDeath = false;
    }

    @Override
    public void onInterrupt(TaskChain other) {
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Death & Auto Respawn";
    }
}
