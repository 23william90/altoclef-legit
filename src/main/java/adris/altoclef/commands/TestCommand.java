package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.args.StringArg;

public class TestCommand extends Command {

    public TestCommand() {
        super("test", "Generic command for testing",
                new StringArg("extra", "")
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String arg = parser.get(String.class);
        mod.log("AltoClef 26.3 test command executed with arg: " + arg);
        finish();
    }
}