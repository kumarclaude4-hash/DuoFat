package com.duoshield.app.notifications;

/**
 * Tracks the exact conversation currently visible to the user.
 *
 * <p>This is intentionally not an app-foreground flag: the conversation list and
 * every other screen leave the active conversation empty, so incoming messages
 * still produce notifications there. The owner token prevents an older Activity's
 * onPause callback from clearing a newer chat that has already resumed.
 */
public final class NotificationVisibility {
    private static final Object LOCK = new Object();
    private static String activeConversationId;
    private static Object activeOwner;

    private NotificationVisibility() { }

    public static void enterConversation(String conversationId, Object owner) {
        if (conversationId == null || conversationId.trim().isEmpty() || owner == null) return;
        synchronized (LOCK) {
            activeConversationId = conversationId;
            activeOwner = owner;
        }
    }

    public static void leaveConversation(String conversationId, Object owner) {
        synchronized (LOCK) {
            if (activeOwner == owner
                    && (conversationId == null || conversationId.equals(activeConversationId))) {
                activeConversationId = null;
                activeOwner = null;
            }
        }
    }

    public static boolean isConversationOpen(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) return false;
        synchronized (LOCK) {
            return conversationId.equals(activeConversationId);
        }
    }
}
