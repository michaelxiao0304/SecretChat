package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "secretchat.admin.password=test-only", "secretchat.mailbox.initial-delay-ms=3600000",
    "spring.datasource.url=${SECRETCHAT_TEST_DB_URL:jdbc:h2:mem:mailbox_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
    "spring.datasource.username=${SECRETCHAT_TEST_DB_USERNAME:sa}", "spring.datasource.password=${SECRETCHAT_TEST_DB_PASSWORD:}"})
@ActiveProfiles("local")
class MailboxHttpTest {
    static final String API = "/api/mailbox";
    static final long NOW = 1000000L;
    @LocalServerPort int port;
    @Autowired DirectoryService directory;
    @Autowired ChallengeService challenges;
    @MockitoBean DeliveryProviders providers;
    @MockitoBean Clock clock;
    @Autowired JdbcTemplate db;
    @Autowired MailboxMaintenance maintenance;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    final HttpClient client = HttpClient.newHttpClient();
    final DeviceIdentity alice = new DeviceIdentity(id(), id());
    final DeviceIdentity bob = new DeviceIdentity(id(), id());
    final String aliceToken = DirectorySecrets.token(), bobToken = DirectorySecrets.token();
    final AtomicLong time = new AtomicLong(NOW);

    @BeforeEach void setup() {
        IsolatedDatabase.check(db);
        when(clock.millis()).thenAnswer(inv -> time.get());
        createDevice(alice, aliceToken);
        createDevice(bob, bobToken);
        var accounts = java.util.stream.Stream.of(alice.accountId(), bob.accountId()).sorted().toList();
        db.update("INSERT INTO sc_directory.contacts(first_id,second_id) VALUES (?,?)", accounts.get(0), accounts.get(1));
    }

    private void createDevice(DeviceIdentity d, String token) {
        db.update("INSERT INTO sc_directory.accounts (account_id,email) VALUES (?,?)", d.accountId(), d.accountId() + "@test.invalid");
        db.update("""
            INSERT INTO sc_directory.devices(device_id,account_id,token_hash,identity_key,signed_prekey) VALUES (?,?,?,?,?)
            """, d.deviceId(), d.accountId(), DirectorySecrets.digest(token), new byte[]{1}, new byte[]{2});
    }

    @AfterEach void cleanup() {
        tx.executeWithoutResult(s -> {
            for (DeviceIdentity device : List.of(alice, bob)) {
                events.publishEvent(new DeviceRetired(device.accountId(), device.deviceId()));
                db.update("DELETE FROM sc_directory.accounts WHERE account_id=?", device.accountId());
                db.update("DELETE FROM sc_ledger.revocations WHERE device_id=?", device.deviceId());
            }
        });
    }

    @Test void ackRequiresRecipientAndRepeatedSendKeepsReceiptAndOriginalExpiry() throws Exception {
        String body = envelope(id(), NOW + 5000);
        var accepted = send(body);
        assertThat(accepted.statusCode()).isEqualTo(202);
        String ref = reference(accepted);
        assertThat(get("alice", "/envelopes/" + ref).statusCode()).isEqualTo(404);
        var fetched = get("bob", "/envelopes/" + ref);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).contains(alice.accountId(), alice.deviceId(), "AQID");
        assertThat(ack("alice", ref).statusCode()).isEqualTo(404);
        assertThat(get("bob", "/outcomes/" + ref).statusCode()).isEqualTo(404);
        assertThat(ack("bob", ref).statusCode()).isEqualTo(204);
        assertThat(ack("bob", ref).statusCode()).isEqualTo(204);
        assertThat(send(body).body()).isEqualTo(accepted.body());
        assertThat(send(body.replace("AQID", "AwIB")).statusCode()).isEqualTo(409);
        assertThat(send(body.replace("1005000", "1006000")).statusCode()).isEqualTo(409);
        assertThat(get("bob", "/envelopes/" + ref).statusCode()).isEqualTo(404);
        assertThat(get("alice", "/outcomes/" + ref).body()).contains("received");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM queued_envelope WHERE id=?", Integer.class, ref)).isZero();
    }

    @Test void pagesKeepOriginalBoundaryThroughIndependentAcknowledgements() throws Exception {
        String first = reference(send(envelope(id(), NOW + 5000)));
        String second = reference(send(envelope(id(), NOW + 5000)));
        String third = reference(send(envelope(id(), NOW + 5000)));
        var page = get("bob", "/envelopes?limit=1");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(references(page)).containsExactly(first);
        long through = JsonPath.<Number>read(page.body(), "$.through").longValue();
        long next = JsonPath.<Number>read(page.body(), "$.nextAfter").longValue();
        String later = reference(send(envelope(id(), NOW + 5000)));
        assertThat(ack("bob", second).statusCode()).isEqualTo(204);
        assertThat(references(get("bob", "/envelopes?after=" + next + "&through=" + through))).containsExactly(third);
        assertThat(references(get("bob", "/envelopes?after=" + through))).containsExactly(later);
    }

    @Test void simultaneousRetryReturnsOneReference() {
        String body = envelope(id(), NOW + 5000);
        var a = CompletableFuture.supplyAsync(() -> uncheckedSend(body));
        var b = CompletableFuture.supplyAsync(() -> uncheckedSend(body));
        assertThat(reference(a.join())).isEqualTo(reference(b.join()));
    }

    @Test void blockingAndExpiryProduceSamePublicOutcomeWithoutDeliveringFilteredCiphertext() throws Exception {
        var normal = reference(send(envelope(id(), NOW + 100)));
        String body = envelope(id(), NOW + 100);
        directory.block(bob, alice.accountId(), true);
        var filtered = reference(send(body));
        directory.block(bob, alice.accountId(), false);
        assertThat(reference(send(body))).isEqualTo(filtered);
        assertThat(references(get("bob", "/envelopes"))).containsExactly(normal);
        assertThat(get("alice", "/outcomes/" + filtered).body()).contains("accepted");
        time.addAndGet(100);
        assertThat(get("bob", "/envelopes/" + normal).statusCode()).isEqualTo(404);
        assertThat(ack("bob", normal).statusCode()).isEqualTo(410);
        maintenance.expire();
        for (String ref : List.of(normal, filtered)) {
            assertThat(get("alice", "/outcomes/" + ref).body()).contains("expired");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM queued_envelope WHERE id=?", Integer.class, ref)).isZero();
        }
        assertThat(send(envelope(id(), time.get())).statusCode()).isEqualTo(400);
        assertThat(send(envelope(id(), time.get() + 604800001)).statusCode()).isEqualTo(400);
    }

    @Test void invalidPushTokenIsRemovedAndClosingSenderClearsDelivery() throws Exception {
        String token = "fa".repeat(32);
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(204);
        String first = reference(send(envelope(id(), NOW + 5000)));
        when(providers.sendPush(anyString(), anyString())).thenReturn(DeliveryProviders.PushResult.INVALID_TOKEN);
        maintenance.push();
        verify(providers).sendPush(token, first);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mailbox_push_binding WHERE device_id=?", Integer.class, bob.deviceId())).isZero();
        directory.close(alice);
        assertThat(get("bob", "/envelopes/" + first).statusCode()).isEqualTo(404);
        assertThat(get("alice", "/outcomes/" + first).statusCode()).isEqualTo(401);
    }

    @Test void blockSetAfterEnqueueSuppressesOutstandingPush() throws Exception {
        String token = "fb".repeat(32);
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(204);
        reference(send(envelope(id(), NOW + 5000)));
        directory.block(bob, alice.accountId(), true);
        maintenance.push();
        directory.block(bob, alice.accountId(), false);
        maintenance.push();
        org.mockito.Mockito.verify(providers, org.mockito.Mockito.never()).sendPush(anyString(), anyString());
    }

    @Test void socketAuthenticatesViaHeadersAndStopsAfterRevocation() throws Exception {
        var uri = URI.create("ws://127.0.0.1:" + port + "/ws");
        var alerts = new SocketAlerts();
        Throwable denied = org.assertj.core.api.Assertions.catchThrowable(() -> client.newWebSocketBuilder()
            .buildAsync(uri, alerts).join());
        assertThat(denied).hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
        var ws = client.newWebSocketBuilder().header("Authorization", "Bearer " + bobToken).buildAsync(uri, alerts).join();
        try {
            assertThat(alerts.take()).contains("ready");
            reference(send(envelope(id(), NOW + 5000)));
            assertThat(alerts.take()).contains("available");
            directory.close(bob);
            assertThat(alerts.take()).isEqualTo("closed");
        } finally { ws.abort(); }
    }

    @Test void recoveryRemovesOldQueueAndBindingAndRequiresExplicitNewDeviceTarget() throws Exception {
        String bound = "fd".repeat(32);
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"" + bound + "\"}").statusCode()).isEqualTo(204);
        String original = envelope(id(), NOW + 5000);
        String ref = reference(send(original));
        String email = bob.accountId() + "@test.invalid";
        var ticket = challenges.issue(email, "recover", "127.0.0.1");
        var code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(providers).sendEmail(org.mockito.ArgumentMatchers.eq(email), code.capture());
        var replacement = directory.recover(new DirectoryService.AccountRequest(null, ticket.challengeId(), code.getValue(), null, "Aw==", "BA==", null));
        assertThat(get("bob", "/envelopes").statusCode()).isEqualTo(401);
        assertThat(get(replacement.token(), "/envelopes/" + ref).statusCode()).isEqualTo(404);
        assertThat(get("alice", "/outcomes/" + ref).statusCode()).isEqualTo(404);
        assertThat(send(original).statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mailbox_push_binding WHERE device_id=?", Integer.class, bob.deviceId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM queued_envelope WHERE id=?", Integer.class, ref)).isZero();
    }

    @Test void onlineSenderGetsExpirySignalAndCanReconcileAfterDisconnect() throws Exception {
        var alerts = new SocketAlerts();
        var ws = client.newWebSocketBuilder().header("Authorization", "Bearer " + aliceToken)
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), alerts).join();
        try {
            assertThat(alerts.take()).contains("ready");
            String body = envelope(id(), NOW + 100);
            String ref = reference(send(body));
            time.addAndGet(100);
            maintenance.expire();
            assertThat(alerts.take()).contains("outcome");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            assertThat(get("alice", "/outcomes/" + ref).body()).contains("expired");
            assertThat(reference(send(body))).isEqualTo(ref);
            time.addAndGet(MailboxService.MAX_TTL);
            maintenance.expire();
            assertThat(get("alice", "/outcomes/" + ref).statusCode()).isEqualTo(404);
        } finally { ws.abort(); }
    }

    @Test void retryUsesRotatedBindingAndStopsAfterFiveFailures() throws Exception {
        String initial = "af".repeat(32), rotated = "ef".repeat(32);
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"" + initial + "\"}").statusCode()).isEqualTo(204);
        String ref = reference(send(envelope(id(), NOW + MailboxService.MAX_TTL)));
        when(providers.sendPush(anyString(), anyString())).thenReturn(DeliveryProviders.PushResult.RETRY);
        maintenance.push();
        maintenance.push();
        verify(providers).sendPush(initial, ref);
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"" + rotated + "\"}").statusCode()).isEqualTo(204);
        for (int i = 0; i < 6; i++) {
            time.addAndGet(65000);
            maintenance.push();
        }
        verify(providers, org.mockito.Mockito.times(4)).sendPush(rotated, ref);
        assertThat(get("alice", "/outcomes/" + ref).body()).contains("accepted");
        assertThat(call("DELETE", "bob", "/push-token", "").statusCode()).isEqualTo(204);
    }

    @Test void failedProviderAttemptBacksOffAndStaleRecipientCannotKeepAnOutboxItemLive() throws Exception {
        String token = "cd".repeat(32);
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(204);
        String first = reference(send(envelope(id(), NOW + 120000)));
        when(providers.sendPush(token, first)).thenThrow(new IllegalStateException("provider failed"))
            .thenReturn(DeliveryProviders.PushResult.DELIVERED);
        maintenance.push();
        maintenance.push();
        verify(providers).sendPush(token, first);
        time.addAndGet(30000);
        maintenance.push();
        verify(providers, org.mockito.Mockito.times(2)).sendPush(token, first);
        String second = reference(send(envelope(id(), NOW + 120000)));
        db.update("DELETE FROM sc_directory.devices WHERE device_id=?", bob.deviceId());
        maintenance.push();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mailbox_delivery WHERE envelope_ref=? AND push_due_ms IS NOT NULL",
            Integer.class, second)).isZero();
        org.mockito.Mockito.verify(providers, org.mockito.Mockito.never()).sendPush(token, second);
    }

    @Test void rejectsMalformedSubmissionsAndPaginationBounds() throws Exception {
        String body = envelope(id(), NOW + MailboxService.MAX_TTL);
        assertThat(send(body).statusCode()).isEqualTo(202);
        for (String invalid : List.of(body.replace("AQID", ""), body.replace("AQID", "...."),
                body.replace("AQID", "A".repeat(87500)), body.replace(bob.deviceId(), "1-1-1-1-1"),
                body.replace(bob.accountId(), alice.accountId()), body.replace("{", "{\"senderDeviceId\":\"spoofed\","))) {
            assertThat(send(invalid).statusCode()).isEqualTo(400);
        }
        for (String params : List.of("after=-1", "after=2&through=1", "limit=0", "limit=101")) {
            assertThat(get("bob", "/envelopes?" + params).statusCode()).isEqualTo(400);
        }
        assertThat(call("PUT", "bob", "/push-token", "{\"token\":\"invalid\"}").statusCode()).isEqualTo(400);
        assertThat(get("invalid", "/envelopes").statusCode()).isEqualTo(401);
    }

    static class SocketAlerts implements WebSocket.Listener {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            received.add(data.toString()); ws.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { received.add("closed"); return null; }
        String take() throws InterruptedException { return received.poll(5, TimeUnit.SECONDS); }
    }

    static String id() { return UUID.randomUUID().toString(); }
    String envelope(String message, long expires) {
        return "{\"recipientAccountId\":\"" + bob.accountId() + "\",\"recipientDeviceId\":\"" + bob.deviceId()
            + "\",\"messageId\":\"" + message + "\",\"expiresAtMs\":" + expires + ",\"ciphertext\":\"AQID\"}";
    }
    HttpResponse<String> uncheckedSend(String body) {
        try { return send(body); } catch (Exception e) { throw new AssertionError(e); }
    }
    static String reference(HttpResponse<String> sent) {
        assertThat(sent.statusCode()).isEqualTo(202);
        return JsonPath.read(sent.body(), "$.reference");
    }
    static List<String> references(HttpResponse<String> page) { return JsonPath.read(page.body(), "$.envelopes[*].reference"); }
    HttpResponse<String> send(String body) throws Exception { return call("POST", "alice", "/envelopes", body); }
    HttpResponse<String> ack(String token, String ref) throws Exception { return call("POST", token, "/envelopes/" + ref + "/ack", ""); }
    HttpResponse<String> get(String token, String path) throws Exception { return call("GET", token, path, ""); }
    HttpResponse<String> call(String method, String token, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + API + path))
            .header("Authorization", "Bearer " + (switch (token) { case "alice" -> aliceToken; case "bob" -> bobToken; default -> token; })).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
