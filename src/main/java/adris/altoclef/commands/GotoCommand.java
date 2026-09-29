package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.movement.GotoTask;

public class GotoCommand extends Command {

    public GotoCommand() throws CommandException {
        super("goto", "Tell bot to travel to a set of coordinates or target", new StringArg("target"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String target = parser.get(String.class);
        mod.runUserTask(new GotoTask(target), this::finish);
    }
}
