package chat.secretchat.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

final class ApnsSigner {
    private final String keyId, team;
    private final PrivateKey privateKey;
    private final Clock clock;
    private String cached;
    private long issued;
    private static final ObjectMapper JSON = new ObjectMapper();

    ApnsSigner(String keyId,String team,PrivateKey privateKey,Clock clock) {
        this.keyId=keyId; this.team=team; this.privateKey=privateKey; this.clock=clock;
    }

    static ApnsSigner configured(Environment env, Clock clock) {
        String path=env.getProperty("SECRETCHAT_APNS_KEY_FILE","");
        String key=env.getProperty("SECRETCHAT_APNS_KEY_ID","");
        String team=env.getProperty("SECRETCHAT_APNS_TEAM_ID","");
        if(path.isBlank() || key.isBlank() || team.isBlank()) return null;
        try {
            String pem=Files.readString(Path.of(path)).replace("-----BEGIN PRIVATE KEY-----","")
                    .replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
            var secret=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return new ApnsSigner(key,team,secret,clock);
        } catch(Exception e) {throw new IllegalStateException("Cannot load configured APNs signing key",e);}
    }

    synchronized String token() {
        long now=clock.instant().getEpochSecond();
        if(cached!=null && now>=issued && now-issued<2700) return cached;
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String header=encoder.encodeToString(JSON.writeValueAsBytes(Map.of("alg","ES256","kid",keyId)));
        String claims=encoder.encodeToString(JSON.writeValueAsBytes(Map.of("iss",team,"iat",now)));
        String data=header+"."+claims;
        try {
            var signature=Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.US_ASCII));
            cached=data+"."+encoder.encodeToString(signature.sign()); issued=now;
            return cached;
        } catch(GeneralSecurityException e) {throw new IllegalStateException("Cannot sign APNs token",e);}
    }
}
