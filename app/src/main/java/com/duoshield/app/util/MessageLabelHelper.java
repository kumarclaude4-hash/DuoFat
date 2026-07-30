package com.duoshield.app.util;

import com.duoshield.app.models.Message;

/**
 * Produces a short, specific, human-readable label for a message that has no text
 * body (voice notes, photos, videos, contact cards, files). Used anywhere a message
 * needs a one-line summary — reply-preview strips, search results, PDF export — so
 * the user always sees what kind of content it actually is instead of a vague
 * placeholder like "[media]" or "Media".
 */
public final class MessageLabelHelper {
    private MessageLabelHelper() {}

    public static String describe(Message m) {
        if (m == null) return "Message";
        if (m.getText() != null && !m.getText().isEmpty()) {
            return m.getText();
        }
        String type = m.getMediaType();
        if (type == null) return "Message";
        switch (type) {
            case "voice":   return "🎤 Voice message";
            case "image":   return "📷 Photo";
            case "video":   return "🎬 Video";
            case "album":   return "🖼 Album";
            case "contact": return "📇 Contact card";
            case "file":    return "📄 File";
            default:        return "Message";
        }
    }

    /** Plain-text variant (no emoji) for contexts like PDF export where emoji glyphs
     * may not render in the export's font. */
    public static String describePlain(Message m) {
        if (m == null) return "Message";
        if (m.getText() != null && !m.getText().isEmpty()) {
            return m.getText();
        }
        String type = m.getMediaType();
        if (type == null) return "Message";
        switch (type) {
            case "voice":   return "[Voice message]";
            case "image":   return "[Photo]";
            case "video":   return "[Video]";
            case "album":   return "[Album]";
            case "contact": return "[Contact card]";
            case "file":    return "[File]";
            default:        return "[Message]";
        }
    }
}
