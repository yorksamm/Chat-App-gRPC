package edu.stevens.cs522.chat.web.client;

import java.util.UUID;

import edu.stevens.cs522.chat.web.request.ChatServiceRequest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/*
 * This interceptor adds app-specific headers to every message sent to gRPC server.
 *
 * https://github.com/grpc/grpc-java/tree/master/examples/src/main/java/io/grpc/examples/header
 */
public class HeaderInterceptor implements ClientInterceptor {

    public static final String APPLICATION_ID = "X-App-Id";

    public static final String CHAT_NAME = "X-Chat-Name";

    protected static final Metadata.Key<String> APPLICATION_ID_KEY = makeKey(APPLICATION_ID);

    protected static final Metadata.Key<String> CHAT_NAME_KEY = makeKey(CHAT_NAME);

    protected String chatName;

    protected UUID appId;

    public HeaderInterceptor(ChatServiceRequest request) {
        this.chatName = request.chatName;
        this.appId = request.appId;
    }

    private static Metadata.Key<String> makeKey(String headerKey) {
        return Metadata.Key.of(headerKey, Metadata.ASCII_STRING_MARSHALLER);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                /* put custom headers */
                headers.put(APPLICATION_ID_KEY, appId.toString());
                // TODO add chat name header
                headers.put(CHAT_NAME_KEY, chatName);

                super.start(responseListener, headers);
            }
        };
    }
}
