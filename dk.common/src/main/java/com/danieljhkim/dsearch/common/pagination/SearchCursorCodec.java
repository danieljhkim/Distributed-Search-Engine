package com.danieljhkim.dsearch.common.pagination;

import com.danieljhkim.dsearch.common.exception.InvalidCursorException;
import com.danieljhkim.dsearch.proto.common.SearchCursorPayload;
import com.danieljhkim.dsearch.proto.common.SortValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a resume point into an opaque, signed, versioned string and back.
 *
 * <p>Wire format is {@code v1.<base64url(payload)>.<base64url(hmac)>}. The payload is a
 * {@link SearchCursorPayload}; the MAC is HMAC-SHA256 over the encoded payload text. Clients get
 * one blob with no stable internal structure, which keeps the resume point a server-side
 * implementation detail we can change without breaking them.
 *
 * <p>The signature exists so a modified cursor fails loudly instead of steering the traversal.
 * Verification is done before parsing, so malformed protobuf never reaches the decoder.
 *
 * <p><b>Key distribution.</b> A gateway load-balances across query nodes, so page two of a
 * traversal is usually served by a different process than page one. Every query node must
 * therefore share {@code pagination.cursorSigningKey}. When it is unset, each process generates
 * its own random key and logs a warning: single-node development keeps working, and in a real
 * cluster the failure is an explicit rejected cursor rather than silent cross-node breakage.
 */
public final class SearchCursorCodec {

    public static final int CURRENT_VERSION = 1;

    private static final Logger LOGGER = Logger.getLogger(SearchCursorCodec.class.getName());
    private static final String PREFIX = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec signingKey;

    public SearchCursorCodec(String configuredKey) {
        this.signingKey = new SecretKeySpec(resolveKey(configuredKey), HMAC_ALGORITHM);
    }

    private static byte[] resolveKey(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            return configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        LOGGER.warning("pagination.cursorSigningKey is not configured; generated a process-local cursor signing key. "
                + "Cursors will not resume across query-node instances or restarts until a shared key is set.");
        return generated;
    }

    /** Builds and signs the cursor that resumes immediately after {@code lastSortValues}. */
    public String encode(
            byte[] requestFingerprint, long indexGeneration, List<SortValue> lastSortValues, long totalHits) {
        SearchCursorPayload payload = SearchCursorPayload.newBuilder()
                .setVersion(CURRENT_VERSION)
                .setRequestFingerprint(ByteString.copyFrom(requestFingerprint))
                .setIndexGeneration(indexGeneration)
                .addAllSortValues(lastSortValues == null ? List.of() : lastSortValues)
                .setTotalHits(totalHits)
                .build();
        String encodedPayload = ENCODER.encodeToString(payload.toByteArray());
        return PREFIX + '.' + encodedPayload + '.' + ENCODER.encodeToString(sign(encodedPayload));
    }

    /**
     * Verifies and parses a cursor for the request presenting it.
     *
     * @param expectedFingerprint fingerprint of the request now being served
     * @param currentGeneration alias generation now serving the partition
     * @throws InvalidCursorException when the cursor is malformed, tampered with, from a format
     *     this build does not implement, or bound to a different request or index generation
     */
    public SearchCursorPayload decode(String cursor, byte[] expectedFingerprint, long currentGeneration) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException(InvalidCursorException.Reason.MALFORMED, "Cursor must not be blank");
        }
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.MALFORMED, "Cursor is not a well-formed pagination cursor");
        }
        if (!PREFIX.equals(parts[0])) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.UNSUPPORTED_VERSION,
                    "Unsupported cursor format '" + parts[0] + "'; restart the traversal without a cursor");
        }

        byte[] payloadBytes;
        byte[] providedMac;
        try {
            payloadBytes = DECODER.decode(parts[1]);
            providedMac = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.MALFORMED, "Cursor is not valid base64url content");
        }

        // Constant-time, and before parsing: an attacker-supplied payload never reaches protobuf.
        if (!MessageDigest.isEqual(sign(parts[1]), providedMac)) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.TAMPERED,
                    "Cursor signature does not match; the cursor was altered or was issued by a query node "
                            + "using a different signing key");
        }

        SearchCursorPayload payload;
        try {
            payload = SearchCursorPayload.parseFrom(payloadBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.MALFORMED, "Cursor payload could not be decoded");
        }

        if (payload.getVersion() != CURRENT_VERSION) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.UNSUPPORTED_VERSION,
                    "Unsupported cursor version " + payload.getVersion() + "; restart the traversal without a cursor");
        }
        if (!MessageDigest.isEqual(payload.getRequestFingerprint().toByteArray(), expectedFingerprint)) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.REQUEST_CHANGED,
                    "Cursor does not match this request; the query, filters, sort, page size, or index schema "
                            + "changed since the cursor was issued. Restart the traversal without a cursor.");
        }
        if (payload.getIndexGeneration() != currentGeneration) {
            throw new InvalidCursorException(
                    InvalidCursorException.Reason.INDEX_CHANGED,
                    "Cursor was issued against index generation " + payload.getIndexGeneration() + " but generation "
                            + currentGeneration
                            + " is now serving this partition. Restart the traversal without a cursor.");
        }
        return payload;
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required to sign pagination cursors", e);
        }
    }
}
