package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.*;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ReportKeyTest {
    @Test void deviceReceivesTheCanonicalFingerprintAndCurrentWrappingLength() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        byte[] spki = pair.generateKeyPair().getPublic().getEncoded();
        var encoded = Base64.getEncoder().encodeToString(spki);
        var fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
        var key = new ReportKey(encoded);
        assertThat(key.recipient()).isEqualTo(new ReportKey.Recipient(1, fingerprint, encoded));
        assertThat(key.wrappedKeyLength(fingerprint)).isEqualTo(256);
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> key.wrappedKeyLength("retired"))
            .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void absentAndInvalidKeysFailClosed() throws Exception {
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> new ReportKey("").recipient())
            .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> new ReportKey("invalid base64")).isInstanceOf(IllegalArgumentException.class);
        var small = KeyPairGenerator.getInstance("RSA");
        small.initialize(1024);
        var spki = Base64.getEncoder().encodeToString(small.generateKeyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> new ReportKey(spki)).isInstanceOf(IllegalArgumentException.class);
    }
}
