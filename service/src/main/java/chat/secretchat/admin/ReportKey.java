package chat.secretchat.admin;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class ReportKey {
    record Recipient(int version, String keyId, String publicKey) {}
    private final Recipient recipient;
    private final int modulusBytes;

    ReportKey(@Value("${secretchat.report.public-key:}") String encoded) throws GeneralSecurityException {
        if (encoded.isBlank()) {
            recipient = null;
            modulusBytes = 0;
            return;
        }
        byte[] der = Base64.getDecoder().decode(encoded);
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        int bits = key.getModulus().bitLength();
        if (bits != 2048 && bits != 3072 && bits != 4096) {
            throw new IllegalArgumentException("Report RSA modulus must be 2048, 3072 or 4096 bits");
        }
        modulusBytes = bits / 8;
        String keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        recipient = new Recipient(1, keyId, encoded);
    }

    Recipient recipient() {
        if (recipient == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return recipient;
    }

    int wrappedKeyLength(String keyId) {
        if (!recipient().keyId().equals(keyId)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return modulusBytes;
    }
}
