package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.entity.RefreshToken;
import com.nexora.hotel.enums.AuditResult;
import com.nexora.hotel.exception.InvalidSessionException;
import com.nexora.hotel.repository.RefreshTokenRepository;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh tokens opacos con rotación en cada uso (patrón de Citas) y detección de reutilización:
 * si llega un token ya rotado fuera del margen de gracia, alguien tiene una copia de la cookie y se
 * revoca toda la familia (todas las sesiones derivadas de ese login). Se usa siempre en ROOT.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /** Margen para dos pestañas que refrescan casi al mismo tiempo con la misma cookie: no es robo. */
    private static final Duration REUSE_GRACE = Duration.ofSeconds(20);
    private static final String EXPIRED = "Tu sesión expiró. Inicia sesión de nuevo.";

    public record Rotation(Long userId, String rawToken) {
    }

    private final RefreshTokenRepository repository;
    private final AppProperties props;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional
    public String issue(Long userId) {
        return issue(userId, UUID.randomUUID()).raw();
    }

    @Transactional(noRollbackFor = InvalidSessionException.class)
    public Rotation rotate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidSessionException(EXPIRED);
        }
        Instant now = clock.instant();
        RefreshToken current = repository.findByHashForUpdate(SecureTokens.sha256(raw))
                .orElseThrow(() -> new InvalidSessionException(EXPIRED));

        if (current.getRevokedAt() != null) {
            boolean withinGrace = current.getReplacedById() != null
                    && current.getRevokedAt().plus(REUSE_GRACE).isAfter(now);
            if (current.getReplacedById() != null && !withinGrace) {
                repository.revokeFamily(current.getFamilyId(), now);
                auditService.recordIndependent(AuditEntry.of("REFRESH_TOKEN_REUSE", AuditResult.DENIED)
                        .entityType("User").entityId(current.getUserId()).actorUserId(current.getUserId())
                        .detail("Se reutilizó un token ya rotado; se cerraron las sesiones de esa familia").build());
            }
            throw new InvalidSessionException(EXPIRED);
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new InvalidSessionException(EXPIRED);
        }

        Issued next = issue(current.getUserId(), current.getFamilyId());
        current.setRevokedAt(now);
        current.setReplacedById(next.entity().getId());
        return new Rotation(current.getUserId(), next.raw());
    }

    @Transactional
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        repository.findByTokenHash(SecureTokens.sha256(raw)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(clock.instant());
            }
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        repository.revokeAllForUser(userId, clock.instant());
    }

    private record Issued(RefreshToken entity, String raw) {
    }

    private Issued issue(Long userId, UUID familyId) {
        String raw = SecureTokens.randomToken();
        RefreshToken token = repository.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(SecureTokens.sha256(raw))
                .familyId(familyId)
                .expiresAt(clock.instant().plus(props.jwt().refreshTtl()))
                .ip(SecurityUtils.clientIp())
                .userAgent(SecurityUtils.userAgent())
                .build());
        return new Issued(token, raw);
    }

    /** Diario: borra tokens vencidos hace más de un día (los revocados recientes se conservan para detectar reúso). */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }
}
