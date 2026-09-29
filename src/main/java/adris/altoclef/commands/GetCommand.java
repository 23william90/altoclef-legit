package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

public class GetCommand extends Command {

    public GetCommand() throws CommandException {
        super("get", "Get an item or resource", new StringArg("item"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String item = parser.get(String.class);
        mod.log("Getting item/resource: " + item);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getCommandManager().execute("mine " + item);
            }
        } catch (Throwable ignored) {
        }
        finish();
    }
}