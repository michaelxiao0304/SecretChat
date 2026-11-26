package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class SignalSocketTest {
    @Test void abruptPeerDisconnectWithoutTransportCallbackIsVisibleOnce() {
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(SignalSocketConfiguration.IDENTITY, new DeviceIdentity("private", "private")));
        var socket = new SignalSocket(null, null);
        try (var logs = new DeliveryLogCapture(SignalSocket.class)) {
            socket.afterConnectionEstablished(session);
            socket.afterConnectionClosed(session, CloseStatus.NORMAL);
            socket.afterConnectionEstablished(session);
            var abnormal = new CloseStatus(1006, "private reason");
            socket.afterConnectionClosed(session, abnormal);
            socket.afterConnectionClosed(session, abnormal);
            assertThat(logs.messages()).containsExactly("event=ws_failure reason=abnormal_close code=1006");
        }
    }

    @Test void onlyUnexpectedSocketErrorsEmitFixedReasons() throws Exception {
        var device = new DeviceIdentity("private-account", "private-device");
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(SignalSocketConfiguration.IDENTITY, device));
        var directory = mock(DirectoryService.class);
        var transaction = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            Consumer<TransactionStatus> work = inv.getArgument(0);
            work.accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        var socket = new SignalSocket(directory, transaction);
        try (var logs = new DeliveryLogCapture(SignalSocket.class)) {
            socket.afterConnectionEstablished(session);
            socket.flush();
            socket.flush();
            assertThat(logs.messages()).isEmpty();
            socket.handleTransportError(session, new IOException("private URI or token"));
            socket.afterConnectionEstablished(session);
            doThrow(new IOException("private reference")).when(session).sendMessage(any());
            socket.flush();
            socket.afterConnectionEstablished(session);
            doThrow(new IllegalStateException("private SQL body")).when(directory).lockActive(device);
            socket.flush();
            assertThat(logs.messages()).containsExactly("event=ws_failure reason=transport", "event=ws_failure reason=send",
                "event=ws_failure reason=device_check");
        }
    }
}
