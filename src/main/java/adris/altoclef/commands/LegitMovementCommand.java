package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;

public class LegitMovementCommand extends Command {
    public LegitMovementCommand() throws CommandException {
        super("legit", "Toggle or configure legit movement mode (smooth looking, faces movement direction, no breaking through walls)",
                new StringArg("mode", "toggle", false));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        boolean enabled;
        if ("on".equalsIgnoreCase(mode) || "true".equalsIgnoreCase(mode) || "enable".equalsIgnoreCase(mode)) {
            enabled = true;
        } else if ("off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode) || "disable".equalsIgnoreCase(mode)) {
            enabled = false;
        } else {
            enabled = !mod.getModSettings().isLegitMovement();
        }

        mod.getModSettings().setLegitMovement(enabled);
        mod.log("Legit movement " + (enabled ? "ENABLED: bot will smoothly face movement heading, avoid snappy turns, and respect line-of-sight walls." : "DISABLED."));
        finish();
    }
}
