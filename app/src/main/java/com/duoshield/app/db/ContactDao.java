package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.duoshield.app.models.Contact;
import java.util.List;

@Dao
public interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Contact contact);

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    List<Contact> getAll();

    @Query("SELECT * FROM contacts WHERE uid = :uid LIMIT 1")
    Contact getByUid(String uid);

    @Query("DELETE FROM contacts WHERE uid = :uid")
    void deleteByUid(String uid);

    @Query("DELETE FROM contacts")
    void deleteAll();
}
