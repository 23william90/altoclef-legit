package adris.altoclef.eventbus.events;

/**
 * Whenever a chat or whisper appears in the client
 */
public class ChatMessageEvent {
    private final String message;
    private final String senderName;

    public ChatMessageEvent(String message, String senderName) {
        this.message = message;
        this.senderName = senderName;
    }

    public ChatMessageEvent(String message) {
        this(message, "");
    }

    public String messageContent() {
        return message;
    }

    public String senderName() {
        return senderName;
    }
}
