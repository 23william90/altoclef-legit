package adris.altoclef;

import java.util.function.Consumer;

public class Settings {

    private boolean legitMovement = false;
    private float legitRotationSpeed = 8.0f;
    private String commandPrefix = "@";
    private String chatLogPrefix = "[Alto Clef]";

    public boolean isLegitMovement() {
        return legitMovement;
    }

    public void setLegitMovement(boolean legitMovement) {
        this.legitMovement = legitMovement;
        if (AltoClef.getInstance() != null) {
            AltoClef.getInstance().applyLegitMovementSettings(legitMovement);
        }
    }

    public float getLegitRotationSpeed() {
        return legitRotationSpeed;
    }

    public void setLegitRotationSpeed(float legitRotationSpeed) {
        this.legitRotationSpeed = legitRotationSpeed;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public void setCommandPrefix(String commandPrefix) {
        this.commandPrefix = commandPrefix;
    }

    public String getChatLogPrefix() {
        return chatLogPrefix;
    }

    public void setChatLogPrefix(String chatLogPrefix) {
        this.chatLogPrefix = chatLogPrefix;
    }

    public boolean shouldHideAllWarningLogs() {
        return false;
    }

    public boolean shouldShowTimer() {
        return true;
    }

    public boolean shouldRunIdleCommandWhenNotActive() {
        return false;
    }

    public String getIdleCommand() {
        return "";
    }

    public boolean failedToLoad() {
        return false;
    }

    public static void load(Consumer<Settings> callback) {
        Settings settings = new Settings();
        if (callback != null) {
            callback.accept(settings);
        }
    }
}
