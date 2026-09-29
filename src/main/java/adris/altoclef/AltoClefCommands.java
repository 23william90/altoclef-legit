package adris.altoclef;

import adris.altoclef.commands.*;
import adris.altoclef.commandsystem.exception.CommandException;

/**
 * Initializes altoclef's built in commands.
 */
public class AltoClefCommands {

    public static void init() throws CommandException {
        AltoClef.getCommandExecutor().registerNewCommand(
                new HelpCommand(),
                new LegitMovementCommand(),
                new GamerCommand(),
                new MarvionCommand(),
                new HeroCommand(),
                new GetCommand(),
                new GotoCommand(),
                new StopCommand(),
                new StatusCommand(),
                new CoordsCommand(),
                new ReloadSettingsCommand(),
                new PauseCommand(),
                new UnPauseCommand(),
                new TestCommand()
        );
    }
}
