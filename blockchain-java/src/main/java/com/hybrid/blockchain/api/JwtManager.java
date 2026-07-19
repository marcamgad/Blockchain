package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Config;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and validates JWTs for IoT device / operator authentication.
 *
 * <p>Security model (addresses prior hardcoded-secret and missing-claim gaps):
 * <ul>
 *   <li>The HS256 signing secret is loaded from the {@code JWT_SECRET} environment
 *       variable / system property. In a production profile an unset secret is fatal;
 *       outside production a fixed development secret is used and a warning is logged,
 *       so the secret is never a value baked into the shipped artifact.</li>
 *   <li>Tokens carry {@code iss} (issuer), {@code sub} (device/operator id),
 *       {@code role}, {@code jti} (unique id, enabling revocation), {@code iat} and
 *       {@code exp}. The issuer is verified on every parse.</li>
 *   <li>A {@code jti} deny-list provides server-side revocation before natural
 *       expiry. It is process-local; a multi-node deployment must back this with a
 *       shared store (Redis/DB) — see {@link #revoke(String)}.</li>
 *   <li>Validation distinguishes failure reasons via {@link ValidationResult} so
 *       callers/audit logs can tell an expired token (normal) from a bad-signature
 *       token (attack indicator).</li>
 * </ul>
 */
public class JwtManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtManager.class);

    /** Development-only fallback secret. Used only outside a production profile. */
    private static final String DEV_SECRET =
            "dev-only-insecure-jwt-secret-change-me-via-JWT_SECRET-env-variable";

    private static final String ISSUER = "hybridchain";
    private static final String ROLE_CLAIM = "role";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_DEVICE = "DEVICE";
    private static final long DEFAULT_EXPIRATION_MS = 24 * 60 * 60 * 1000L; // 24h

    private final SecretKey key;

    /** Revoked token ids (jti). Process-local; see class docs for multi-node caveat. */
    private final Map<String, Long> revokedJti = new ConcurrentHashMap<>();

    /** Outcome of validating a token, so callers can react to the reason. */
    public enum ValidationResult { VALID, EXPIRED, BAD_SIGNATURE, MALFORMED, REVOKED }

    public JwtManager() {
        this(resolveSecret());
    }

    /** Test/advanced constructor allowing an explicit secret (e.g. per-tenant keys). */
    public JwtManager(String secret) {
        // Normalise any-length secret to a 256-bit HMAC key so HS256's key-length
        // requirement holds regardless of how the operator provisioned the secret.
        this.key = Keys.hmacShaKeyFor(sha256(secret));
    }

    private static String resolveSecret() {
        String s = System.getProperty("JWT_SECRET");
        if (s == null || s.isEmpty()) s = System.getenv("JWT_SECRET");
        if (s != null && !s.isEmpty()) return s;

        if (Config.isProductionProfile()) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set in production; refusing to start with a default key.");
        }
        log.warn("JWT_SECRET not set — using insecure development secret. "
                + "Set JWT_SECRET for any non-local deployment.");
        return DEV_SECRET;
    }

    // ── Issuing ──────────────────────────────────────────────────────────────

    /** Issue a device-role token with the default 24h lifetime. */
    public String issueToken(String subject) {
        return issueToken(subject, ROLE_DEVICE);
    }

    /** Issue a token for {@code subject} with an explicit role and default lifetime. */
    public String issueToken(String subject, String role) {
        Date now = new Date();
        return issueToken(subject, role, new Date(now.getTime() + DEFAULT_EXPIRATION_MS));
    }

    /**
     * Issue a token with an explicit expiry. A past {@code expiry} yields an
     * already-expired token (useful for tests and short-lived grants).
     */
    public String issueToken(String subject, String role, Date expiry) {
        Date now = new Date();
        return Jwts.builder()
                .setIssuer(ISSUER)
                .setSubject(subject)
                .claim(ROLE_CLAIM, role == null ? ROLE_DEVICE : role)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key)
                .compact();
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /** True when the token is present, correctly signed, unexpired and not revoked. */
    public boolean validateToken(String token) {
        return validateDetailed(token) == ValidationResult.VALID;
    }

    /** True when the token is valid <em>and</em> its subject equals {@code subject}. */
    public boolean validateToken(String token, String subject) {
        Optional<Claims> claims = parse(token);
        return claims.isPresent()
                && !isRevoked(claims.get())
                && subject != null
                && subject.equals(claims.get().getSubject());
    }

    /** Full validation outcome, for audit logging and threat scoring. */
    public ValidationResult validateDetailed(String token) {
        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .requireIssuer(ISSUER)
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            if (isRevoked(jws.getBody())) return ValidationResult.REVOKED;
            return ValidationResult.VALID;
        } catch (ExpiredJwtException e) {
            return ValidationResult.EXPIRED;
        } catch (SignatureException e) {
            return ValidationResult.BAD_SIGNATURE;
        } catch (JwtException | IllegalArgumentException e) {
            return ValidationResult.MALFORMED;
        }
    }

    /** True only for a valid token whose role claim is ADMIN. */
    public boolean isAdmin(String token) {
        return getRole(token).map(ROLE_ADMIN::equals).orElse(false);
    }

    public Optional<String> getRole(String token) {
        return parse(token).map(c -> c.get(ROLE_CLAIM, String.class));
    }

    // ── Revocation ───────────────────────────────────────────────────────────

    /** Revoke a token by its jti so it is rejected before natural expiry. */
    public void revoke(String token) {
        parse(token).ifPresent(c -> {
            if (c.getId() != null) {
                long exp = c.getExpiration() != null ? c.getExpiration().getTime() : Long.MAX_VALUE;
                revokedJti.put(c.getId(), exp);
            }
        });
        purgeExpiredRevocations();
    }

    private boolean isRevoked(Claims claims) {
        return claims.getId() != null && revokedJti.containsKey(claims.getId());
    }

    private void purgeExpiredRevocations() {
        long now = System.currentTimeMillis();
        revokedJti.entrySet().removeIf(e -> e.getValue() < now);
    }

    // ── Subject accessors ────────────────────────────────────────────────────

    /** @deprecated use {@link #getSubject(String)}. */
    @Deprecated
    public String getDeviceId(String token) {
        return getSubject(token);
    }

    public String getSubject(String token) {
        return getSubjectOptional(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid JWT token"));
    }

    public Optional<String> getSubjectOptional(String token) {
        return parse(token).map(Claims::getSubject);
    }

    /** @deprecated use {@link #getSubjectOptional(String)}. */
    @Deprecated
    public Optional<String> getDeviceIdOptional(String token) {
        return getSubjectOptional(token);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parserBuilder()
                    .requireIssuer(ISSUER)
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
