package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class CoordsCommand extends Command {
    public CoordsCommand() {
        super("coords", "Get the bot's current coordinates");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        if (mod.getPlayer() != null) {
            mod.log("CURRENT COORDINATES: " + mod.getPlayer().blockPosition().toShortString());
        } else {
            mod.log("Player not available.");
        }
        finish();
    }
}
