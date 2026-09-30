package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.IntArg;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.resources.GetItemTask;

public class GetCommand extends Command {

    public GetCommand() throws CommandException {
        super("get", "Get an item or resource", new StringArg("item"), new IntArg("count", 1));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String item = parser.get(String.class);
        int count = 1;
        try {
            count = parser.get(Integer.class);
        } catch (Throwable ignored) {
        }
        mod.runUserTask(new GetItemTask(item, count), this::finish);
    }
}