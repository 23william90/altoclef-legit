package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class StatusCommand extends Command {
    public StatusCommand() {
        super("status", "Get status of currently executing command/bot");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("AltoClef 26.3 Legit Edition is active.");
        mod.log("Legit movement: " + (mod.getModSettings().isLegitMovement() ? "ENABLED" : "DISABLED"));
        finish();
    }
}