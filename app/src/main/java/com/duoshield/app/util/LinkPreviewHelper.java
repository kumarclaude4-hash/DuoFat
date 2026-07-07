package com.duoshield.app.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinkPreviewHelper {

    private static final Pattern URL_PATTERN = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)", Pattern.CASE_INSENSITIVE);

    public static String extractFirstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public static boolean containsUrl(String text) {
        return extractFirstUrl(text) != null;
    }
}
