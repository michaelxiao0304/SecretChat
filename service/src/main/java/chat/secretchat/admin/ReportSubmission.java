package chat.secretchat.admin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

record ReportSubmission(String id, String reportedAccountId, String keyId, byte[] wrappedKey, byte[] iv, byte[] ciphertext) {
    static final int MAX_CIPHERTEXT = 49152;
    private static final int MAX_BODY = 72 * 1024;
    private static final Set<String> FIELDS = Set.of("id","reportedAccountId","version","keyId","wrappedKey","iv","ciphertext");

    static ReportSubmission read(InputStream stream, ObjectMapper mapper, ReportKey recipient) throws IOException {
        byte[] body = stream.readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        Map<String,Object> fields;
        try {
            fields = mapper.readerFor(new TypeReference<Map<String,Object>>() {})
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (JacksonException e) {
            throw invalid();
        }
        if (fields == null || !fields.keySet().equals(FIELDS) || !Integer.valueOf(1).equals(fields.get("version"))) throw invalid();
        String keyId = string(fields.get("keyId"));
        int wrapSize = recipient.wrappedKeyLength(keyId);
        return new ReportSubmission(uuid(fields.get("id")), uuid(fields.get("reportedAccountId")), keyId,
            bytes(fields.get("wrappedKey"), wrapSize, wrapSize), bytes(fields.get("iv"), 12, 12),
            bytes(fields.get("ciphertext"), 17, MAX_CIPHERTEXT));
    }

    private static String string(Object value) {
        if (value instanceof String s) return s;
        throw invalid();
    }

    static String uuid(Object value) {
        String id = string(value);
        try {
            if (UUID.fromString(id).toString().equals(id)) return id;
        } catch (IllegalArgumentException ignored) {}
        throw invalid();
    }

    private static byte[] bytes(Object value, int min, int max) {
        String encoded = string(value);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length >= min && decoded.length <= max && Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {}
        throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
