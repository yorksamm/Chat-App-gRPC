package edu.stevens.cs522.chat.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class Counter {

    @PrimaryKey
    public long id;

    public long lastSeqNum;
}
