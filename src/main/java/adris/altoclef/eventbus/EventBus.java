package adris.altoclef.eventbus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings({"rawtypes", "unchecked"})
public class EventBus {

    private record Pair<L, R>(L left, R right) {}

    private static final HashMap<Class, List<Subscription>> topics = new HashMap<>();
    private static final List<Pair<Class, Subscription>> toAdd = new ArrayList<>();
    private static boolean lock;

    public static <T> void publish(T event) {
        Class type = event.getClass();

        for (Pair<Class, Subscription> toAddPair : toAdd) {
            subscribeInternal(toAddPair.left(), toAddPair.right());
        }
        toAdd.clear();

        if (topics.containsKey(type)) {
            List<Subscription> subscribers = topics.get(type);
            List<Subscription> toDelete = new ArrayList<>();

            lock = true;
            for (Subscription subRaw : subscribers) {
                Subscription<T> sub = (Subscription<T>) subRaw;
                sub.accept(event);
                if (sub.shouldDelete()) {
                    toDelete.add(sub);
                }
            }
            lock = false;

            for (Subscription sub : toDelete) {
                unsubscribe(sub);
            }
        }
    }

    public static <T> Subscription<T> subscribe(Class<T> type, Consumer<T> action) {
        Subscription<T> sub = new Subscription<>(action);
        if (lock) {
            toAdd.add(new Pair<>(type, sub));
        } else {
            subscribeInternal(type, sub);
        }
        return sub;
    }

    private static <T> void subscribeInternal(Class<T> type, Subscription<T> sub) {
        if (!topics.containsKey(type)) {
            topics.put(type, new ArrayList<>());
        }
        topics.get(type).add(sub);
    }

    public static <T> void unsubscribe(Subscription<T> sub) {
        for (List<Subscription> list : topics.values()) {
            list.remove(sub);
        }
    }
}
