package adris.altoclef;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class Debug {

    public static void logInternal(String message) {
        System.out.println("ALTO CLEF: " + message);
    }

    public static void logInternal(String format, Object... args) {
        logInternal(String.format(format, args));
    }

    private static String getLogPrefix() {
        AltoClef altoClef = AltoClef.getInstance();
        if (altoClef != null && altoClef.getModSettings() != null) {
            return altoClef.getModSettings().getChatLogPrefix();
        }
        return "[Alto Clef] ";
    }

    public static void logMessage(String message, boolean prefix) {
        try {
            if (Minecraft.getInstance() != null && Minecraft.getInstance().player != null) {
                if (prefix) {
                    message = "\u00A72\u00A7l\u00A7o" + getLogPrefix() + "\u00A7r " + message;
                }
                final String finalMsg = message;
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.sendSystemMessage(Component.literal(finalMsg));
                    }
                });
            } else {
                logInternal(message);
            }
        } catch (Throwable t) {
            logInternal(message);
        }
    }

    public static void logMessage(String message) {
        logMessage(message, true);
    }

    public static void logMessage(String format, Object... args) {
        logMessage(String.format(format, args));
    }

    public static void logWarning(String message) {
        System.out.println("ALTO CLEF: WARNING: " + message);
        logMessage("\u00A7c" + message + "\u00A7r", true);
    }

    public static void logError(String message) {
        System.err.println("ALTO CLEF: ERROR: " + message);
        logMessage("\u00A74" + message + "\u00A7r", true);
    }
}
