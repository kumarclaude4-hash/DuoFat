package com.duoshield.app.models;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "session_events")
public class SessionEvent {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "event_type")
    public String eventType;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "device_model")
    public String deviceModel;

    @ColumnInfo(name = "android_version")
    public String androidVersion;

    public SessionEvent(String eventType, long timestamp,
                        String deviceModel, String androidVersion) {
        this.eventType      = eventType;
        this.timestamp      = timestamp;
        this.deviceModel    = deviceModel;
        this.androidVersion = androidVersion;
    }
}
