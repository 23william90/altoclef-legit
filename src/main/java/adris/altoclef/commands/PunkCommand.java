package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.entity.KillTargetTask;

public class PunkCommand extends Command {

    public PunkCommand() throws CommandException {
        super("punk", "Punk 'em - hunt down and eliminate target player", new StringArg("player"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);
        mod.log("Punking target player: " + playerName);
        mod.runUserTask(new KillTargetTask(playerName), this::finish);
    }
}
