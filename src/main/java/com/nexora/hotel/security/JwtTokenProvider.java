package com.nexora.hotel.security;

import com.nexora.hotel.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Access tokens JWT (HMAC-SHA256), de vida corta. Solo llevan el id del usuario: roles y permisos se
 * resuelven en cada request desde la BD (con caché), así un cambio de permisos aplica de inmediato y
 * no hay que esperar a que el token expire.
 */
@Component
public class JwtTokenProvider {

    private static final String ISSUER = "nexora-hotel";
    private static final String TYPE_CLAIM = "typ";
    private static final String ACCESS = "access";

    private final Key key;
    private final AppProperties props;
    private final Clock clock;

    public JwtTokenProvider(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long userId, Long hotelId) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .setIssuer(ISSUER)
                .setSubject(String.valueOf(userId))
                .claim(TYPE_CLAIM, ACCESS)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(props.jwt().accessTtl())));
        if (hotelId != null) {
            builder.claim("hid", hotelId);
        }
        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    /** Id del usuario si el token es válido, vigente, emitido por nosotros y de tipo access. */
    public Optional<Long> parseUserId(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(ISSUER)
                    .setClock(() -> Date.from(clock.instant()))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            if (!ACCESS.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(Long.valueOf(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long accessTtlSeconds() {
        return props.jwt().accessTtl().toSeconds();
    }
}
