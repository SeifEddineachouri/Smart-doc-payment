package com.example.smartdoc.payment.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class StripeSignatureVerifier {
    private static final HexFormat HEX = HexFormat.of();
    private final Clock clock;

    public StripeSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    public void verify(byte[] payload, String signatureHeader, String secret, int toleranceSeconds) {
        SignatureParts parts = parse(signatureHeader);
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - parts.timestamp()) > toleranceSeconds) {
            throw new StripeSignatureVerificationException("Stripe signature timestamp is outside the allowed tolerance");
        }

        String expected = hmacSha256(secret, parts.timestamp() + "." + new String(payload, StandardCharsets.UTF_8));
        boolean matches = parts.signatures().stream().anyMatch(signature -> MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8)));
        if (!matches) {
            throw new StripeSignatureVerificationException("Stripe signature does not match payload");
        }
    }

    public static String buildSignatureHeader(byte[] payload, String secret, Instant timestamp) {
        String signature = hmacSha256(secret, timestamp.getEpochSecond() + "." + new String(payload, StandardCharsets.UTF_8));
        return "t=" + timestamp.getEpochSecond() + ",v1=" + signature;
    }

    private static SignatureParts parse(String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new StripeSignatureVerificationException("Stripe signature header is missing");
        }
        Map<String, List<String>> values = new java.util.HashMap<>();
        for (String part : signatureHeader.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length == 2 && !keyValue[0].isBlank() && !keyValue[1].isBlank()) {
                values.computeIfAbsent(keyValue[0], ignored -> new ArrayList<>()).add(keyValue[1]);
            }
        }
        if (!values.containsKey("t") || !values.containsKey("v1")) {
            throw new StripeSignatureVerificationException("Stripe signature header is missing required parts");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(values.get("t").get(0));
        } catch (NumberFormatException ex) {
            throw new StripeSignatureVerificationException("Stripe signature timestamp is invalid");
        }
        return new SignatureParts(timestamp, values.get("v1"));
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute HMAC", ex);
        }
    }

    private record SignatureParts(long timestamp, List<String> signatures) {}
}


