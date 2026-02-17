package edu.stevens.cs522.chat.databases;

import android.util.Log;
import android.view.PixelCopy;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;
import androidx.room.Upsert;

import java.util.List;
import java.util.UUID;

import edu.stevens.cs522.chat.entities.Chatroom;
import edu.stevens.cs522.chat.entities.Counter;
import edu.stevens.cs522.chat.entities.Message;
import edu.stevens.cs522.chat.entities.Peer;

@Dao
/**
 * These are synchronous operations used on a background thread for syncing messages with a server.
 */
public abstract class RequestDao {

    private static final String TAG = RequestDao.class.getCanonicalName();

    /**
     * Initial version counter
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract void persist(Counter counter);

    public void initLastSequenceNumber() {
        Counter counter = new Counter();
        counter.id = 1;
        counter.lastSeqNum = 0;
        persist(counter);
    }

    @Update
    protected abstract void update(Counter counter);

    public void updateLastSequenceNumber(long seqNum) {
        Counter counter = new Counter();
        counter.id = 1;
        counter.lastSeqNum = seqNum;
        update(counter);
    }

    /**
     * Get the last sequence number in the messages database.
     */
    @Query("SELECT lastSeqNum FROM Counter WHERE id = 1")
    public abstract long getLastSequenceNumber();

    /**
     * Get all unsent messages, identified by sequence number = 0.
     */
    @Query("SELECT * FROM Message WHERE seqNum = 0")
    public abstract List<Message> getUnsentMessages();

    /**
     * After we upload a message, the server responds with the sequence numbers of the message
     */
    @Query("UPDATE Message SET seqNum = :seqNum WHERE id = :id")
    public abstract void updateSeqNum(long id, long seqNum);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract long insert(Message message);

    @Transaction
    /**
     * Insert other peer's messages or update our own, with input from server.
     *
     * In updating the sequence number on each message (in case we lose network connection),
     * this assumes that the server downloads messages in increasing order by sequence number.
     */
    public void upsert(UUID appID, Message message) {

        if (appID.equals(message.appID)) {
            // One of our own messages returned from the server, update sequenceId
            updateSeqNum(message.id, message.seqNum);
        } else {
            // Another peer's message, with sequenceId set by server
            message.id = 0;  // We give it our own PK in our local messages database
            insert(message);
        }
        // TODO immediately update the last sequence number, in case the connection is broken.
        updateSeqNum(message.id, getLastSequenceNumber());

    }

}
