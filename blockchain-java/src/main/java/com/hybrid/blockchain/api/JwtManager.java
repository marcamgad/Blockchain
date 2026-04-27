package com.hybrid.blockchain.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;

import java.util.Date;
import java.util.Optional;

public class JwtManager {

    private static final String SECRET = "ThisIsAVeryLongSecretKeyThatIsDefinitelyMoreThan256BitsAndSecureEnoughForJJWTRequirement1234567890!";
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24 hours

    public String issueToken(String deviceId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRATION_MS);

        return Jwts.builder()
                .setSubject(deviceId)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(SignatureAlgorithm.HS256, SECRET)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .setSigningKey(SECRET)
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateToken(String token, String deviceId) {
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(SECRET)
                    .parseClaimsJws(token)
                    .getBody();
            return deviceId.equals(claims.getSubject());
        } catch (ExpiredJwtException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract subject (user/device ID) from JWT token.
     * Used for authentication context setup.
     */
    public String getSubject(String token) {
        return getDeviceId(token);
    }

    public Optional<String> getSubjectOptional(String token) {
        return getDeviceIdOptional(token);
    }

    public String getDeviceId(String token) {
        return getDeviceIdOptional(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid JWT token"));
    }

    public Optional<String> getDeviceIdOptional(String token) {
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(SECRET)
                    .parseClaimsJws(token)
                    .getBody();
            return Optional.ofNullable(claims.getSubject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
