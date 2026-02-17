package edu.stevens.cs522.chat.web;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import edu.stevens.cs522.chat.entities.Chatroom;
import edu.stevens.cs522.chat.entities.Message;
import edu.stevens.cs522.chat.entities.Peer;
import edu.stevens.cs522.chat.entities.TimestampConverter;
import edu.stevens.cs522.chat.web.client.HeaderInterceptor;
import edu.stevens.cs522.chat.web.grpc.ChatServiceGrpc;
import edu.stevens.cs522.chat.web.grpc.ChatServiceGrpc.ChatServiceBlockingStub;
import edu.stevens.cs522.chat.web.grpc.ChatServiceGrpc.ChatServiceStub;
import edu.stevens.cs522.chat.web.grpc.DownloadItem;
import edu.stevens.cs522.chat.web.grpc.Location;
import edu.stevens.cs522.chat.web.grpc.RegistrationRequest;
import edu.stevens.cs522.chat.web.grpc.SyncRequest;
import edu.stevens.cs522.chat.web.grpc.UploadItem;
import edu.stevens.cs522.chat.web.request.ChatServiceRequest;
import edu.stevens.cs522.chat.web.request.ChatServiceResponse;
import edu.stevens.cs522.chat.web.request.ErrorResponse;
import edu.stevens.cs522.chat.web.request.RegisterRequest;
import edu.stevens.cs522.chat.web.request.SynchronizeRequest;
import edu.stevens.cs522.chat.settings.Settings;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.android.AndroidChannelBuilder;
import io.grpc.stub.StreamObserver;


/**
 * Created by dduggan.
 */

public class RestMethod {

    private static final String TAG = RestMethod.class.getCanonicalName();

    private static final boolean DEBUG = true;

    public static final String CHARSET = "UTF-8";



    /*
     * HTTP Request headers
     */
    public final static String CONTENT_TYPE = "CONTENT-TYPE";

    public final static String USER_AGENT = "USER-AGENT";
    private ChatServiceBlockingStub blockingStub;

    protected final Context context;

    /*
     * This is the underlying channel for sending requests to the server.
     */
    protected ManagedChannel channel;


    public RestMethod(Context context) {
        this.context = context.getApplicationContext();
    }

    /*
     * Create a retrofit client stub around an OkHttp client.
     */

    protected Channel getChannel(Uri serverUri, ChatServiceRequest request) {
        if (channel == null || channel.isShutdown() || channel.isTerminated()) {
            String host = serverUri.getHost();
            int port = serverUri.getPort();
            ClientInterceptor interceptor = new HeaderInterceptor(request);

            // https://github.com/grpc/grpc-java/blob/master/documentation/android-channel-builder.md
            // TODO create the channel
            channel = AndroidChannelBuilder.forAddress(host, port).context(context).intercept(interceptor).usePlaintext().idleTimeout(1, TimeUnit.MINUTES).build();

        }
        return channel;
    }
    protected ChatServiceBlockingStub createClient(Uri serverUri, ChatServiceRequest request) {
        /*
         * Create a blocking client stub (for registration).
         */
        return ChatServiceGrpc.newBlockingStub(getChannel(serverUri, request));
    }

    protected ChatServiceStub createStreamingClient(Uri serverUri, ChatServiceRequest request) {
        /*
         * Create a streaming client stub (for synchronization).
         */
        return ChatServiceGrpc.newStub(getChannel(serverUri, request));
    }

    /*
     * Construct an error response from an exception thrown in a Web service call.
     */
    public static ErrorResponse getErrorResponse(Throwable t) {
        Status status = Status.fromThrowable(t);
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.responseCode = status.getCode().value();
        errorResponse.responseMessage = status.getDescription();
        errorResponse.errorMessage = t.getMessage();
        return errorResponse;
    }

    public ChatServiceResponse perform(RegisterRequest request) {
        try {
            Log.d(TAG, String.format("Performing Web service call for registration: chat name=%s, app id=%s....", request.chatName, request.appId));

            Location location = Location.newBuilder().setLongitude(request.longitude).setLatitude(request.latitude).build();
            RegistrationRequest registration = RegistrationRequest.newBuilder().setLocation(location).build();

            // TODO execute the blocking Web service call
            blockingStub = createClient(request.chatServer, request);
            blockingStub.register(registration);

            Log.d(TAG, "Registration request succeeded!");
            return request.getResponse();

        } catch (Exception e) {
            ErrorResponse response = getErrorResponse(e);
            Log.e(TAG, "Registration: Web service error, status code = "+ response.responseCode, e);
            return response;
        }
    }

    public interface UploadObserver {
        public void onSync(long lastSequenceNumber, Double longitude, Double latitude);
        public void onChatroom(Chatroom chatroom);
        public void onMessage(Message message);
        public void onCompleted();
        public void onError(Throwable t);
    }

    public interface DownloadObserver {
        public void onChatroom(Chatroom chatroom);
        public void onPeer(Peer peer);
        public void onMessage(Message message);
        public void onCompleted();
        public void onError(Throwable t);
    }

    public UploadObserver perform(SynchronizeRequest request, final DownloadObserver downloadObserver) {

        /*
         * The response consumer wraps the streaming response from the server.
         */
        StreamObserver<DownloadItem> responseConsumer = new StreamObserver<DownloadItem>() {
            @Override
            public void onNext(DownloadItem item) {
                if (item.hasChatroom()) {
                    downloadObserver.onChatroom(intern(item.getChatroom()));
                } else if (item.hasPeer()) {
                    downloadObserver.onPeer(intern(item.getPeer()));
                } else if (item.hasMessage()) {
                    // TODO upsert the message (may be one of our own with updated seq number)
                    downloadObserver.onMessage(intern(item.getMessage()));

                }
            }

            @Override
            public void onError(Throwable t) {
                downloadObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                downloadObserver.onCompleted();
            }
        };

        //StreamObserver<UploadItem> requestProducer = null;
        // TODO get and invoke a streaming client stub
        ChatServiceStub stub = createStreamingClient(Settings.getServerUri(context), request);
        StreamObserver<UploadItem> requestProducer = stub.sync(responseConsumer);

        /*
         * Wrap the request producer in an upload observer.
         */
        return new UploadObserver() {

            @Override
            public void onSync(long lastSequenceNumber, Double longitude, Double latitude) {
                // TODO
                Location location = Location.newBuilder().setLongitude(longitude).setLatitude(latitude).build();
                SyncRequest request = SyncRequest.newBuilder().setLocation(location).setVersion(lastSequenceNumber).build();
                requestProducer.onNext(UploadItem.newBuilder().setRequest(request).build());

            }

            @Override
            public void onChatroom(Chatroom chatroom) {
                requestProducer.onNext(UploadItem.newBuilder().setChatroom(extern(chatroom)).build());
            }

            @Override
            public void onMessage(Message message) {
                // TODO
                requestProducer.onNext(UploadItem.newBuilder().setMessage(extern(message)).build());

            }

            @Override
            public void onCompleted() {
                requestProducer.onCompleted();
            }

            @Override
            public void onError(Throwable t) {
                requestProducer.onError(t);
            }
        };
    }

    /**
     * Converters between entity types and protobuf types
     */

    protected static Chatroom intern(edu.stevens.cs522.chat.web.grpc.Chatroom c) {
        Chatroom chatroom = new Chatroom();
        chatroom.name = c.getName();
        return chatroom;
    }

    protected static edu.stevens.cs522.chat.web.grpc.Chatroom extern(Chatroom chatroom) {
        return edu.stevens.cs522.chat.web.grpc.Chatroom.newBuilder()
                .setName(chatroom.name)
                .build();
    }

    protected static Peer intern(edu.stevens.cs522.chat.web.grpc.Peer p) {
        Peer peer = new Peer();
        peer.name = p.getName();
        peer.timestamp = TimestampConverter.deserialize(p.getTimestamp());
        peer.longitude = p.getLongitude();
        peer.latitude = p.getLatitude();
        return peer;
    }

    protected static edu.stevens.cs522.chat.web.grpc.Peer extern(Peer peer) {
        return edu.stevens.cs522.chat.web.grpc.Peer.newBuilder()
                .setName(peer.name)
                .setTimestamp(TimestampConverter.serialize(peer.timestamp))
                .setLongitude(peer.longitude)
                .setLatitude(peer.latitude)
                .build();
    }

    protected static Message intern(edu.stevens.cs522.chat.web.grpc.Message p) {
        Message message = new Message();
        message.id = p.getId();
        message.chatroom = p.getChatroom();
        message.messageText = p.getMessageText();
        message.seqNum = p.getSeqNum();
        message.appID = UUID.fromString(p.getAppID());
        message.timestamp = TimestampConverter.deserialize(p.getTimestamp());
        message.longitude = p.getLongitude();
        message.latitude = p.getLatitude();
        message.sender = p.getSender();
        return message;
    }

    protected static edu.stevens.cs522.chat.web.grpc.Message extern(Message message) {
        return edu.stevens.cs522.chat.web.grpc.Message.newBuilder()
                .setId(message.id)
                .setChatroom(message.chatroom)
                .setMessageText(message.messageText)
                .setSeqNum(message.seqNum)
                .setAppID(message.appID.toString())
                .setTimestamp(TimestampConverter.serialize(message.timestamp))
                .setLongitude(message.longitude)
                .setLatitude(message.latitude)
                .setSender(message.sender)
                .build();
    }

    /**
     * Build and return a user-agent string that can identify this application to remote servers. Contains the package
     * name and version code.
     */
    protected static String buildUserAgent(Context context) {
        String versionName = "unknown";
        long versionCode = 0;

        try {
            final PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            versionName = info.versionName;
            versionCode = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        return context.getPackageName() + "/" + versionName + " (" + versionCode + ") (gzip)";
    }

}
