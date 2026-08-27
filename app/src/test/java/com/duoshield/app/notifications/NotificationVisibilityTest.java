package com.duoshield.app.notifications;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationVisibilityTest {
    @Test
    public void onlyExactActiveConversationIsOpen() {
        Object owner = new Object();
        NotificationVisibility.enterConversation("chat-a", owner);

        assertTrue(NotificationVisibility.isConversationOpen("chat-a"));
        assertFalse(NotificationVisibility.isConversationOpen("chat-b"));
    }

    @Test
    public void newerChatCannotBeClearedByOlderOwner() {
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        NotificationVisibility.enterConversation("chat-a", firstOwner);
        NotificationVisibility.enterConversation("chat-b", secondOwner);

        NotificationVisibility.leaveConversation("chat-a", firstOwner);

        assertFalse(NotificationVisibility.isConversationOpen("chat-a"));
        assertTrue(NotificationVisibility.isConversationOpen("chat-b"));
        NotificationVisibility.leaveConversation("chat-b", secondOwner);
    }

    @Test
    public void leavingActiveChatClearsIt() {
        Object owner = new Object();
        NotificationVisibility.enterConversation("chat-a", owner);
        NotificationVisibility.leaveConversation("chat-a", owner);

        assertFalse(NotificationVisibility.isConversationOpen("chat-a"));
    }
}
