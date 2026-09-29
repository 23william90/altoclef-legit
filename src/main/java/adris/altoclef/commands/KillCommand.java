package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.entity.KillTargetTask;

public class KillCommand extends Command {

    public KillCommand() throws CommandException {
        super("kill", "Hunt down and eliminate any player or mob", new StringArg("target"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String target = parser.get(String.class);
        mod.log("Eliminating target: " + target);
        mod.runUserTask(new KillTargetTask(target), this::finish);
    }
}
