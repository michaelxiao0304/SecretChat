package chat.secretchat.admin;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
class SignalSocketConfiguration implements WebSocketConfigurer, HandshakeInterceptor {
    static final String IDENTITY = "deviceIdentity";
    private final DirectoryService directory;
    private final SignalSocket socket;

    SignalSocketConfiguration(DirectoryService directory, SignalSocket socket) {
        this.directory = directory;
        this.socket = socket;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws").addInterceptors(this);
    }

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                             WebSocketHandler handler, Map<String, Object> attributes) {
        if (request.getURI().getRawQuery() != null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        try {
            attributes.put(IDENTITY, directory.requireDevice(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)));
            return true;
        } catch (ResponseStatusException error) {
            response.setStatusCode(error.getStatusCode());
            return false;
        }
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler handler, Exception failure) {}
}
