package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class DeliveryProvidersTest {
    final ObjectMapper json = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_000),ZoneOffset.UTC);
    final ProviderFailures failures = mock(ProviderFailures.class);
    final AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>();
    HttpServer server;
    int code=200;
    String result="{}";
    final Map<String,String> pushHeaders=new HashMap<>();
    URI endpoint;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", ex->{
            body.set(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            for(String name:List.of("apns-topic","apns-expiration","apns-push-type","apns-priority","apns-collapse-id"))
                pushHeaders.put(name,ex.getRequestHeaders().getFirst(name));
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code,bytes.length);
            try(var out=ex.getResponseBody()){out.write(bytes);}
        });
        server.start();
        endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
    }
    @AfterEach void stop(){server.stop(0);}
    DeliveryProviders provider(MockEnvironment env, ApnsSigner signer) {
        return new DeliveryProviders(env, failures, json, HttpClient.newHttpClient(), endpoint, endpoint, signer);
    }
    @Test void disabledGatewaysCannotPretendToDeliver(){
        var p=provider(new MockEnvironment(),null);
        assertThatThrownBy(()->p.sendEmail("someone@example.org","123456")).isInstanceOf(ResponseStatusException.class);
        assertThat(p.sendPush("aa","ref")).isEqualTo(DeliveryProviders.PushResult.UNCONFIGURED);
        assertThat(body).hasValue(null);
        assertThat(p.status()).containsEntry("registrationMode","VERIFIED_EMAIL");
    }
    @Test void mailUsesProviderAuthenticationAndCountsFailure(){
        var env=new MockEnvironment().withProperty("SECRETCHAT_MAIL_API_KEY","test-key").withProperty("SECRETCHAT_MAIL_FROM","sender@example.org");
        var p=provider(env,null);
        p.sendEmail("test@example.org","654321");
        assertThat(auth).hasValue("Bearer test-key");
        assertThat(json.readTree(body.get()).get("to").get(0).asString()).isEqualTo("test@example.org");
        assertThat(json.readTree(body.get()).get("text").asString()).contains("654321");
        code=429;
        assertThatThrownBy(()->p.sendEmail("test@example.org","654321")).isInstanceOf(ResponseStatusException.class);
        verify(failures).record("email");
    }
    @Test void pushUsesVerifiableEs256TokenAndOnlyOpaqueReference() throws Exception {
        var gen=KeyPairGenerator.getInstance("EC"); gen.initialize(new ECGenParameterSpec("secp256r1"));
        var keys=gen.generateKeyPair();
        var signer=new ApnsSigner("key","team",keys.getPrivate(),clock);
        var p=provider(new MockEnvironment().withProperty("SECRETCHAT_APNS_TOPIC","chat.example"),signer);
        assertThat(p.sendPush("a".repeat(64),"opaque-reference")).isEqualTo(DeliveryProviders.PushResult.DELIVERED);
        var payload=json.readTree(body.get());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("aps","ref");
        assertThat(payload.get("ref").asString()).isEqualTo("opaque-reference");
        assertThat(payload.get("aps").get("mutable-content").asInt()).isOne();
        assertThat(pushHeaders).containsEntry("apns-topic","chat.example").containsEntry("apns-expiration","0")
            .containsEntry("apns-push-type","alert").containsEntry("apns-priority","10")
            .containsEntry("apns-collapse-id","opaque-reference");
        String[] parts=auth.get().substring(7).split("\\.");
        var verifier=Signature.getInstance("SHA256withECDSAinP1363Format"); verifier.initVerify(keys.getPublic());
        verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(signer.token()).isEqualTo(auth.get().substring(7));
        code=410; result="{\"reason\":\"Unregistered\"}";
        assertThat(p.sendPush("b".repeat(64),"r")).isEqualTo(DeliveryProviders.PushResult.INVALID_TOKEN);
        code=503;
        assertThat(p.sendPush("b".repeat(64),"r")).isEqualTo(DeliveryProviders.PushResult.RETRY);
        code=400; result="{\"reason\":\"BadDeviceToken\"}";
        assertThat(p.sendPush("b".repeat(64),"r")).isEqualTo(DeliveryProviders.PushResult.INVALID_TOKEN);
        result="upstream error";
        assertThat(p.sendPush("b".repeat(64),"r")).isEqualTo(DeliveryProviders.PushResult.RETRY);
        assertThat(p.sendPush("short","r")).isEqualTo(DeliveryProviders.PushResult.INVALID_TOKEN);
        verify(failures,times(4)).record("push");
    }

    @Test void signerLoadsConfiguredKeyAndRefreshesAfter45Minutes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var keys=KeyPairGenerator.getInstance("EC");
        keys.initialize(new ECGenParameterSpec("secp256r1"));
        var key=keys.generateKeyPair().getPrivate();
        var file=temp.resolve("fixture.p8");
        java.nio.file.Files.writeString(file,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(key.getEncoded())+"\n-----END PRIVATE KEY-----\n");
        var env=new MockEnvironment().withProperty("SECRETCHAT_APNS_KEY_FILE",file.toString())
            .withProperty("SECRETCHAT_APNS_KEY_ID","key").withProperty("SECRETCHAT_APNS_TEAM_ID","team");
        Clock times=mock(Clock.class);
        when(times.instant()).thenReturn(Instant.EPOCH,Instant.ofEpochSecond(2699),Instant.ofEpochSecond(2700),Instant.EPOCH);
        var loaded=ApnsSigner.configured(env,times);
        String first=loaded.token();
        assertThat(loaded.token()).isEqualTo(first);
        String renewed=loaded.token();
        assertThat(renewed).isNotEqualTo(first);
        assertThat(loaded.token()).isNotEqualTo(renewed);
        java.nio.file.Files.writeString(file,"invalid");
        assertThatThrownBy(()->ApnsSigner.configured(env,times)).isInstanceOf(IllegalStateException.class);
    }
}
