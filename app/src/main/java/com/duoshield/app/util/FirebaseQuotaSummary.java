package com.duoshield.app.util;

import android.content.Context;

public class FirebaseQuotaSummary {

    public static String get(Context ctx) {
        FirebaseCostGuard g = FirebaseCostGuard.getInstance(ctx);
        return "Reads today: " + g.getReads() + " / 40,000\n"
             + "Writes today: " + g.getWrites() + " / 16,000\n"
             + "Deletes today: " + g.getDeletes() + " / 16,000";
    }
}
