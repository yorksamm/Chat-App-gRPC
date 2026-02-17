package edu.stevens.cs522.chat.web;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import edu.stevens.cs522.chat.R;
import edu.stevens.cs522.chat.databases.ChatDatabase;
import edu.stevens.cs522.chat.entities.Chatroom;
import edu.stevens.cs522.chat.entities.Message;
import edu.stevens.cs522.chat.entities.Peer;
import edu.stevens.cs522.chat.location.CurrentLocation;
import edu.stevens.cs522.chat.web.RestMethod.DownloadObserver;
import edu.stevens.cs522.chat.web.RestMethod.UploadObserver;
import edu.stevens.cs522.chat.web.request.ChatServiceRequest;
import edu.stevens.cs522.chat.web.request.ChatServiceResponse;
import edu.stevens.cs522.chat.web.request.DummyResponse;
import edu.stevens.cs522.chat.web.request.ErrorResponse;
import edu.stevens.cs522.chat.web.request.PostMessageRequest;
import edu.stevens.cs522.chat.web.request.RegisterRequest;
import edu.stevens.cs522.chat.web.request.RegisterResponse;
import edu.stevens.cs522.chat.web.request.SynchronizeRequest;
import edu.stevens.cs522.chat.settings.Settings;
import io.grpc.Status;

/**
 * Created by dduggan.
 */

public class RequestProcessor {

    private static final String TAG = RequestProcessor.class.getCanonicalName();

    private final Context context;

    private final CurrentLocation location;

    private final RestMethod restMethod;

    private final ChatDatabase chatDatabase;

    private RequestProcessor(Context context) {
        this.context = context.getApplicationContext();

        this.location = new CurrentLocation(context);

        this.restMethod = new RestMethod(context);

        this.chatDatabase = ChatDatabase.getInstance(context);
    }

    public static RequestProcessor getInstance(Context context) {
        return new RequestProcessor(context);
    }

    /**
     * We use the Visitor pattern to dispatch to the appropriate request processing.
     * This is also where we attach metadata to the request that is attached as
     * application-specific request headers to the HTTP request.
     * @param request
     * @return
     */
    public ChatServiceResponse process(ChatServiceRequest request) {
        request.appId = Settings.getAppId(context);
        if (request.chatName == null) {
            /*
             * chatName is only already set if this is a RegisterRequest
             */
            request.chatName = Settings.getChatName(context);
        }
        String packageName = context.getPackageName();
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(packageName, 0);
            request.version = pInfo.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unrecognized package name: "+packageName, e);
        }
        request.latitude = location.getLatitude();
        request.longitude = location.getLongitude();
        return request.process(this);
    }

    public ChatServiceResponse perform(RegisterRequest request) {

        Log.d(TAG, "Registering as " + request.chatName);
        ChatServiceResponse response = restMethod.perform(request);

        if (response instanceof RegisterResponse) {
            Log.d(TAG, "Registration successful!");
            /*
             * Add a record for this peer to the local database.
             */
            RegisterResponse registration = (RegisterResponse) response;

            final Peer peer = new Peer();
            peer.name = request.chatName;
            peer.timestamp = Instant.now();
            peer.latitude = request.latitude;
            peer.longitude = request.longitude;
            chatDatabase.peerDao().upsert(peer);

            // Initialize the chatrooms database with the default chatroom
            chatDatabase.chatroomDao().insert(new Chatroom(context.getString(R.string.default_chat_room)));

            // Initialize the sequence number (version counter) for synchronizing with the server
            chatDatabase.requestDao().initLastSequenceNumber();

            // TODO save the server URI and user name in settings
            Settings.saveChatName(context, peer.name);
            Settings.saveServerUri(context, request.chatServer);
        }
        return response;
    }

    public ChatServiceResponse perform(PostMessageRequest request) {

        Log.d(TAG, "Posting message: " + request.message.messageText);

        Log.d(TAG, "Adding the chatroom to the local database, if not already there.");
        chatDatabase.chatroomDao().insert(new Chatroom(request.message.chatroom));

        Log.d(TAG, "Inserting the message into the local database.");
        long id = -1;  // Local PK of the message in the DB
        // TODO insert the message into the local database
        id = chatDatabase.requestDao().insert(request.message);
        request.message.id = id;
        /*
         * We will just insert the message into the database, and rely on periodic
         * background synchronization to upload it asynchronously.
         */
        Log.d(TAG, "We will upload the message when we synchronize with the server later.");
        return request.getDummyResponse();
    }

    public static final long SYNC_TIMEOUT = 10; // seconds

    /**
     * For SYNC: perform a sync using a request manager.  These requests are
     * generated from an alarm that is scheduled at periodic intervals.
     */
    public ChatServiceResponse perform(SynchronizeRequest request) {

        if (!Settings.isRegistered(context)) {
            Log.d(TAG, "Background sync before registration will be skipped...");
            return new DummyResponse();
        }

        Log.d(TAG, "Performing synchronization request.");

        /*
         * Use this latch to wait until response from server has been completely processed.
         */
        CountDownLatch latch = new CountDownLatch(1);

        UUID myAppID = Settings.getAppId(context);

        /*
         * This is the callback for processing streaming downloads from the server.
         */
        DownloadObserver responseConsumer = new DownloadObserver() {
            @Override
            public void onChatroom(Chatroom chatroom) {
                chatDatabase.chatroomDao().insert(chatroom);
            }

            public void onPeer(Peer peer) {
                chatDatabase.peerDao().upsert(peer);
            }

            public void onMessage(Message message) {
                // TODO upsert the message (may be one of our own with a seq number updated by the server)
                chatDatabase.requestDao().upsert(myAppID, message);
                chatDatabase.requestDao().updateLastSequenceNumber(message.seqNum);
            }

            @Override
            public void onError(Throwable t) {
                /*
                 * An error reported by the server, so the download is terminated.
                 */
                Log.e(TAG, "Error while downloading data from server", t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                /*
                 * The server has signalled that downloading is now completed.
                 */
                Log.i(TAG, "Finished download from server");
                latch.countDown();
            }
        };

        /*
         * Connect to the server with the above callback for consuming its response.
         * The streaming call returns a listener to which we push uploads.
         */
        UploadObserver uploader = restMethod.perform(request, responseConsumer);

        try {
            /*
             * Start pushing uploads to the server via the observer we got back from the streaming call.
             * These uploadds will be processed by the server.  The downloads it pushes to us will be
             * processed by the listener we provided as the argument to the RPC.  The server will not
             * start pushing downloads until we have finished pushing uploads.
             */
            /*
             * The server needs the sequence number of the last message it downloaded to this device.
             * The server will download any messages it has "seen" since it last synced with this device.
             */
            long lastSequenceNumber = chatDatabase.requestDao().getLastSequenceNumber();
            uploader.onSync(lastSequenceNumber, request.longitude, request.latitude);

            /*
             * We upload a list of all our chatrooms to the server.
             */
            final List<Chatroom> chatrooms = chatDatabase.chatroomDao().getAllChatrooms();
            for (Chatroom chatroom : chatrooms) {
                uploader.onChatroom(chatroom);
            }

            /*
             * TODO Upload the messages that we have not yet uploaded to the server.
             */
            List<Message> messages = chatDatabase.requestDao().getUnsentMessages();
            for(Message message : messages){
                uploader.onMessage(message);
            }

            Log.i(TAG, "Finished uploading data to server");
            uploader.onCompleted();

            /*
             * Now wait for the download of the server response to complete (see download observer,
             * which will decrement the countdown latch when downloading is finished).
             */
            boolean completed = latch.await(SYNC_TIMEOUT, TimeUnit.SECONDS);

            if (completed) {
                return request.getResponse();
            } else {
                /*
                 * The countdown latch timed out.
                 */
                Log.e(TAG, "Countdown latch in request processing timed out!");
                ErrorResponse errorResponse = new ErrorResponse();
                Status status = Status.ABORTED;
                errorResponse.responseCode = status.getCode().value();
                errorResponse.responseMessage = status.getCode().name();
                errorResponse.errorMessage = status.getDescription();
                return errorResponse;
            }

        } catch (Exception e) {

            Log.e(TAG, "Exception while uploading data to server!", e);
            uploader.onError(e);
            return RestMethod.getErrorResponse(e);

        }

    }

}
