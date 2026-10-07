package ru.marketplace;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.marketplace.model.TokenResponse;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

@Service
public class Tokens {
    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final JdbcTemplate db;

    public Tokens(@Value("${marketplace.jwt-secret}") String secret,
                  @Value("${marketplace.access-ttl}") Duration accessTtl,
                  @Value("${marketplace.refresh-ttl}") Duration refreshTtl, JdbcTemplate db) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.db = db;
    }

    Claims parse(String value, String type) {
        Claims claims = Jwts.parser().verifyWith(key).requireIssuer("marketplace").require("type", type)
                .build().parseSignedClaims(value).getPayload();
        if (claims.getExpiration() == null || claims.getIssuedAt() == null || claims.getId() == null)
            throw new MalformedJwtException("Required claims missing");
        UUID.fromString(claims.getSubject());
        UUID.fromString(claims.getId());
        if (!java.util.Set.of("USER", "SELLER", "ADMIN").contains(claims.get("role", String.class)))
            throw new MalformedJwtException("Invalid role");
        return claims;
    }

    TokenResponse issue(UUID userId, String role) {
        Instant now = Instant.now();
        UUID refreshId = UUID.randomUUID();
        db.update("INSERT INTO refresh_tokens(id,user_id,expires_at) VALUES (?,?,?)", refreshId, userId, Timestamp.from(now.plus(refreshTtl)));
        return new TokenResponse().accessToken(jwt(userId, role, "access", UUID.randomUUID(), now, accessTtl))
                .refreshToken(jwt(userId, role, "refresh", refreshId, now, refreshTtl))
                .tokenType(TokenResponse.TokenTypeEnum.BEARER).expiresIn(Math.toIntExact(accessTtl.toSeconds()));
    }

    private String jwt(UUID id, String role, String type, UUID tokenId, Instant now, Duration ttl) {
        return Jwts.builder().issuer("marketplace").subject(id.toString()).id(tokenId.toString())
                .claim("role", role).claim("type", type).issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }
}
