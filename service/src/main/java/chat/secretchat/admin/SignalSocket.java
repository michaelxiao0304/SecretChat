package chat.secretchat.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
class SignalSocket extends TextWebSocketHandler {
    private static final int READY = 1, AVAILABLE = 2, OUTCOME = 4;
    private static final long RECHECK_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final ConcurrentHashMap<WebSocketSession, Peer> peers = new ConcurrentHashMap<>();
    private final DirectoryService directory;
    private final TransactionTemplate tx;

    private static final class Peer {
        final DeviceIdentity device;
        final AtomicInteger pending = new AtomicInteger(READY);
        long lastCheck;
        Peer(DeviceIdentity device) { this.device = device; }
    }

    SignalSocket(DirectoryService directory, TransactionTemplate tx) {
        this.directory = directory;
        this.tx = tx;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        peers.put(session, new Peer((DeviceIdentity)session.getAttributes().get(SignalSocketConfiguration.IDENTITY)));
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { peers.remove(session); }
    @Override public void handleTransportError(WebSocketSession session, Throwable error) { close(session, CloseStatus.SERVER_ERROR); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) { close(session, CloseStatus.POLICY_VIOLATION); }

    @TransactionalEventListener
    void signal(MailboxService.Signal event) {
        int flag = switch (event.kind()) {
            case AVAILABLE -> AVAILABLE;
            case OUTCOME -> OUTCOME;
        };
        peers.forEach((session, peer) -> {
            if (peer.device.deviceId().equals(event.deviceId())) peer.pending.getAndUpdate(bits -> bits | flag);
        });
    }

    @TransactionalEventListener
    void retired(DeviceRetired event) {
        peers.forEach((session, peer) -> {
            if (peer.device.deviceId().equals(event.deviceId())) close(session, CloseStatus.POLICY_VIOLATION);
        });
    }

    // Coalesce repeated hints per connected device. One writer performs all socket writes outside request transactions.
    @Scheduled(fixedDelay=250)
    void flush() {
        long now = System.nanoTime();
        peers.forEach((session, peer) -> {
            int pending = peer.pending.getAndSet(0);
            if (pending == 0 && now - peer.lastCheck < RECHECK_NANOS) return;
            try {
                tx.executeWithoutResult(s -> {
                    directory.lockActive(peer.device);
                    if ((pending & READY) != 0) send(session, "ready");
                    if ((pending & AVAILABLE) != 0) send(session, "available");
                    if ((pending & OUTCOME) != 0) send(session, "outcome");
                });
                peer.lastCheck = now;
            } catch (ResponseStatusException revoked) { close(session, CloseStatus.POLICY_VIOLATION); }
            catch (RuntimeException failure) { close(session, CloseStatus.SERVER_ERROR); }
        });
    }

    private static void send(WebSocketSession session, String kind) {
        try { session.sendMessage(new TextMessage("{\"type\":\"" + kind + "\"}")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        peers.remove(session);
        try { session.close(status); }
        catch (IOException ignored) { }
    }
}
