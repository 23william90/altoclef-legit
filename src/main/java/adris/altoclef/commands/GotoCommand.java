package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

public class GotoCommand extends Command {

    public GotoCommand() throws CommandException {
        super("goto", "Tell bot to travel to a set of coordinates or target", new StringArg("target"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String target = parser.get(String.class);
        mod.log("Traveling to: " + target);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getCommandManager().execute("goto " + target);
            }
        } catch (Throwable ignored) {
        }
        finish();
    }
}
