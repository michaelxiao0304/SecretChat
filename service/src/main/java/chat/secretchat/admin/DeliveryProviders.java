package chat.secretchat.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Component
class DeliveryProviders {
    private static final Logger log = LoggerFactory.getLogger(DeliveryProviders.class);
    enum PushResult { DELIVERED, INVALID_TOKEN, RETRY, UNCONFIGURED }
    private final Environment env;
    private final ProviderFailures failures;
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI emailEndpoint, pushEndpoint;
    private final ApnsSigner signer;

    @Autowired DeliveryProviders(Environment env,ProviderFailures failures,ObjectMapper json,Clock clock) {
        this(env,failures,json,HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(4)).build(),URI.create("https://api.resend.com/emails"),
                URI.create(env.getProperty("SECRETCHAT_APNS_SANDBOX",Boolean.class,false)
                    ? "https://api.sandbox.push.apple.com/3/device/" : "https://api.push.apple.com/3/device/"),
                ApnsSigner.configured(env,clock));
    }
    DeliveryProviders(Environment env,ProviderFailures failures,ObjectMapper json,HttpClient client,
                      URI emailEndpoint,URI pushEndpoint,ApnsSigner signer) {
        this.env=env; this.failures=failures; this.json=json; this.client=client;
        this.emailEndpoint=emailEndpoint; this.pushEndpoint=pushEndpoint; this.signer=signer;
    }
    Map<String,Object> status() {
        boolean unverified=env.getProperty("secretchat.registration.unverified",Boolean.class,false);
        return Map.of("registrationMode", unverified ? "INVITE_ONLY_UNVERIFIED_EMAIL":"VERIFIED_EMAIL",
                "email",summary("email",emailReady()),"push",summary("push",pushReady()));
    }
    private Map<String,Object> summary(String name,boolean configured) {
        return Map.of("configured",configured,"failures24h",failures.recent(name));
    }
    private boolean emailReady(){return !setting("SECRETCHAT_MAIL_FROM").isBlank() && !setting("SECRETCHAT_MAIL_API_KEY").isBlank();}
    private boolean pushReady(){return signer!=null && !setting("SECRETCHAT_APNS_TOPIC").isBlank();}
    private String setting(String key){return env.getProperty(key,"");}

    void sendEmail(String email,String code) {
        if(!emailReady()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email delivery is not configured");
        var data=Map.of("from",setting("SECRETCHAT_MAIL_FROM"),"to",List.of(email),
                "subject","SecretChat 邮箱验证码","text","你的 SecretChat 验证码是 "+code+"。请勿转发。");
        var request=post(emailEndpoint,data).header("Authorization","Bearer "+setting("SECRETCHAT_MAIL_API_KEY")).build();
        var response=send(request);
        if(response!=null && response.statusCode()/100==2) return;
        recordFailure("email", response, "http");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email provider unavailable");
    }

    PushResult sendPush(String token,String reference) {
        if(!pushReady()) return PushResult.UNCONFIGURED;
        if(!token.matches("[a-fA-F0-9]{32,512}")) return PushResult.INVALID_TOKEN;
        var aps=Map.of("alert",Map.of("title","SecretChat","body","你有一条新消息"),"mutable-content",1);
        var request=post(pushEndpoint.resolve(token),Map.of("aps",aps,"ref",reference))
                .header("authorization","bearer "+signer.token()).header("apns-topic",setting("SECRETCHAT_APNS_TOPIC"))
                .header("apns-push-type","alert").header("apns-priority","10").header("apns-expiration","0")
                .header("apns-collapse-id",reference).build();
        var response=send(request);
        if(response!=null && response.statusCode()==200) return PushResult.DELIVERED;
        var disposition = classifyPushFailure(response);
        recordFailure("push", response, disposition == PushResult.INVALID_TOKEN ? "invalid_token" : "http");
        return disposition;
    }

    private void recordFailure(String provider, HttpResponse<String> response, String reason) {
        if (response == null) log.warn("event=provider_failure provider={} reason=transport", provider);
        else log.warn("event=provider_failure provider={} reason={} status={}", provider, reason, response.statusCode());
        failures.record(provider);
    }

    private PushResult classifyPushFailure(HttpResponse<String> response) {
        if (response == null) return PushResult.RETRY;
        boolean invalid = response.statusCode() == 410 || response.statusCode() == 400 && badToken(response.body());
        return invalid ? PushResult.INVALID_TOKEN : PushResult.RETRY;
    }

    private boolean badToken(String body) {
        try {return "BadDeviceToken".equals(json.readTree(body).path("reason").asString(""));}
        catch(RuntimeException e){return false;}
    }
    private HttpRequest.Builder post(URI uri,Object payload) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)));
    }
    private HttpResponse<String> send(HttpRequest request) {
        try {return client.send(request,HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException e){Thread.currentThread().interrupt(); return null;}
        catch(IOException e){return null;}
    }
}
