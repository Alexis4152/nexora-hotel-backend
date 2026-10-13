package com.nexora.hotel.service.impl;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.dto.request.ChangePasswordRequest;
import com.nexora.hotel.dto.request.LoginRequest;
import com.nexora.hotel.dto.request.ResetPasswordRequest;
import com.nexora.hotel.dto.response.HotelRef;
import com.nexora.hotel.dto.response.SessionResponse;
import com.nexora.hotel.dto.response.SessionUser;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.PasswordResetToken;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.enums.AuditResult;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.InvalidSessionException;
import com.nexora.hotel.notification.MailService;
import com.nexora.hotel.repository.PasswordResetTokenRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.JwtTokenProvider;
import com.nexora.hotel.security.PermissionResolver;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.service.AuthService;
import com.nexora.hotel.service.RefreshTokenService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * Hash señuelo con el mismo costo BCrypt: se compara contra él cuando el correo no existe, para que el
     * tiempo de respuesta no delate qué cuentas existen.
     */
    private volatile String dummyHash;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider tokenProvider;
    private final PermissionResolver permissionResolver;
    private final PasswordEncoder passwordEncoder;
    private final HotelDirectory hotelDirectory;
    private final AuditService auditService;
    private final MailService mailService;
    private final AppProperties props;
    private final Clock clock;

    // Sin @Transactional a propósito (lección de Citas): el contador de intentos fallidos debe quedar
    // guardado aunque después se lance la excepción de credenciales inválidas.
    @Override
    public LoginResult login(LoginRequest request) {
        String email = normalize(request.email());
        Instant now = clock.instant();
        Optional<User> found = findAccount(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash());
            auditService.recordIndependent(AuditEntry.of("LOGIN_FAILED", AuditResult.FAILED)
                    .actorEmail(email).detail("Cuenta inexistente").build());
            throw new BadCredentialsException("bad");
        }
        User user = found.get();

        if (user.isLocked(now)) {
            auditService.recordIndependent(loginEvent(user, "LOGIN_BLOCKED", AuditResult.DENIED, "Cuenta bloqueada temporalmente"));
            throw new BadCredentialsException("locked");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedAttempt(user, now);
            throw new BadCredentialsException("bad");
        }
        if (!user.isActive() || user.getDeletedAt() != null || !hotelIsActive(user)) {
            auditService.recordIndependent(loginEvent(user, "LOGIN_DENIED", AuditResult.DENIED, "Cuenta u hotel inactivo"));
            throw new BadCredentialsException("inactive");
        }
        if (user.getEmailVerifiedAt() == null) {
            auditService.recordIndependent(loginEvent(user, "LOGIN_DENIED", AuditResult.DENIED, "Correo sin verificar"));
            throw new BusinessException("Confirma tu correo para iniciar sesión. Revisa tu bandeja de entrada o pide un nuevo enlace.");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        User saved = userRepository.save(user);
        auditService.recordIndependent(loginEvent(saved, "LOGIN_SUCCESS", AuditResult.SUCCESS, null));

        String refresh = TenantContext.callAs(TenantContext.ROOT, () -> refreshTokenService.issue(saved.getId()));
        return new LoginResult(session(saved), refresh);
    }

    private void registerFailedAttempt(User user, Instant now) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        boolean lock = attempts >= props.security().maxFailedLogins();
        if (lock) {
            user.setLockedUntil(now.plus(props.security().lockDuration()));
            user.setFailedLoginAttempts(0);
        }
        userRepository.save(user);
        auditService.recordIndependent(loginEvent(user, lock ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", AuditResult.FAILED,
                lock ? "Bloqueada por intentos fallidos" : "Contraseña incorrecta"));
    }

    @Override
    // Sin rollback ante sesión inválida: si se detectó reutilización, la revocación de la familia debe quedar.
    @Transactional(noRollbackFor = InvalidSessionException.class)
    public LoginResult refresh(String refreshToken, Long expectedHotelId) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
        User user = userRepository.findById(rotation.userId())
                .filter(u -> u.isActive() && u.getDeletedAt() == null && hotelIsActive(u))
                .filter(u -> java.util.Objects.equals(u.getHotelId(), expectedHotelId))
                .orElseThrow(() -> new InvalidSessionException("Tu sesión expiró. Inicia sesión de nuevo."));
        return new LoginResult(session(user), rotation.rawToken());
    }

    @Override
    public void logout(String refreshToken) {
        TenantContext.runAs(TenantContext.ROOT, () -> refreshTokenService.revoke(refreshToken));
        SecurityUtils.currentPrincipal().ifPresent(p ->
                auditService.recordIndependent(AuditEntry.success("LOGOUT", "User", p.userId()).build()));
    }

    @Override
    @Transactional(readOnly = true)
    public SessionUser currentSession() {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        User user = TenantContext.callAs(TenantContext.ROOT, () -> userRepository.findById(principal.userId()))
                .orElseThrow(() -> new InvalidSessionException("Tu sesión expiró. Inicia sesión de nuevo."));
        return sessionUser(user, principal);
    }

    @Override
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();
        findAccount(email).filter(u -> u.isActive() && u.getDeletedAt() == null && hotelIsActive(u)).ifPresent(user -> {
            resetTokenRepository.invalidateAllForUser(user.getId(), now);
            String raw = SecureTokens.randomToken();
            resetTokenRepository.save(PasswordResetToken.builder()
                    .userId(user.getId())
                    .tokenHash(SecureTokens.sha256(raw))
                    .expiresAt(now.plus(props.security().resetTokenTtl()))
                    .build());
            boolean guest = user.getRoles().stream().allMatch(r -> com.nexora.hotel.entity.Role.GUEST.equals(r.getCode()));
            String link = frontendBase(user) + (guest ? "/cuenta/restablecer/" : "/restablecer/") + raw;
            sendResetEmail(user, link);
            auditService.record(AuditEntry.success("PASSWORD_RESET_REQUESTED", "User", user.getId())
                    .actorUserId(user.getId()).actorEmail(user.getEmail()).hotelId(user.getHotelId()).build());
        });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = clock.instant();
        PasswordResetToken token = resetTokenRepository.findByHashForUpdate(SecureTokens.sha256(request.token()))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new BusinessException("El enlace no es válido o ya expiró. Solicita uno nuevo."));
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException("El enlace no es válido o ya expiró. Solicita uno nuevo."));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        token.setUsedAt(now);
        refreshTokenService.revokeAllForUser(user.getId());
        auditService.record(AuditEntry.success("PASSWORD_RESET", "User", user.getId())
                .actorUserId(user.getId()).actorEmail(user.getEmail()).hotelId(user.getHotelId()).build());
    }

    @Override
    @Transactional
    public LoginResult changePassword(ChangePasswordRequest request) {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new InvalidSessionException("Tu sesión expiró. Inicia sesión de nuevo."));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("La contraseña actual no es correcta");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException("La nueva contraseña debe ser distinta de la actual");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        // Cierra las demás sesiones abiertas; esta continúa con un refresh token nuevo.
        refreshTokenService.revokeAllForUser(user.getId());
        String refresh = refreshTokenService.issue(user.getId());
        auditService.record(AuditEntry.success("PASSWORD_CHANGED", "User", user.getId()).hotelId(user.getHotelId()).build());
        return new LoginResult(session(user), refresh);
    }

    // ── Apoyo ─────────────────────────────────────────────────────────────────────────

    /** Cuenta del hotel del contexto, o de plataforma si se entra desde la raíz (el controller usa ROOT). */
    private Optional<User> findAccount(String email) {
        return TenantContext.hotelIdOrNull() != null
                ? userRepository.findByEmail(email)
                : userRepository.findByEmailAndHotelIdIsNull(email);
    }

    private boolean hotelIsActive(User user) {
        return user.getHotelId() == null
                || TenantContext.callAs(TenantContext.ROOT, () -> hotelDirectory.isActive(user.getHotelId()));
    }

    private SessionResponse session(User user) {
        AuthPrincipal principal = TenantContext.callAs(TenantContext.ROOT, () -> permissionResolver.principalFor(user));
        return new SessionResponse(tokenProvider.generateAccessToken(user.getId(), user.getHotelId()),
                tokenProvider.accessTtlSeconds(), sessionUser(user, principal));
    }

    private SessionUser sessionUser(User user, AuthPrincipal principal) {
        HotelRef hotel = null;
        if (user.getHotelId() != null) {
            Hotel h = TenantContext.callAs(TenantContext.ROOT, () -> hotelDirectory.get(user.getHotelId()));
            hotel = new HotelRef(h.getId(), h.getSlug(), h.getName(), h.getTimezone());
        }
        return new SessionUser(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getHotelId() == null, hotel, principal.roles().stream().sorted().toList(),
                principal.permissions().stream().sorted().toList(), user.isMustChangePassword());
    }

    private AuditEntry loginEvent(User user, String action, AuditResult result, String detail) {
        return AuditEntry.of(action, result).entityType("User").entityId(user.getId())
                .actorUserId(user.getId()).actorEmail(user.getEmail()).hotelId(user.getHotelId())
                .detail(detail).build();
    }

    private String frontendBase(User user) {
        String root = props.frontendUrl().replaceAll("/+$", "");
        if (user.getHotelId() == null) {
            return root;
        }
        Hotel hotel = TenantContext.callAs(TenantContext.ROOT, () -> hotelDirectory.get(user.getHotelId()));
        return root + "/h/" + hotel.getSlug();
    }

    private void sendResetEmail(User user, String link) {
        String name = HtmlUtils.htmlEscape(user.getFirstName());
        String minutes = String.valueOf(props.security().resetTokenTtl().toMinutes());
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#0f172a">
                  <h2 style="margin-bottom:8px">Restablece tu contraseña</h2>
                  <p>Hola %s, recibimos una solicitud para restablecer tu contraseña de Nexora Hotel.</p>
                  <p><a href="%s" style="display:inline-block;background:#0f766e;color:#fff;padding:12px 20px;
                     border-radius:10px;text-decoration:none">Crear nueva contraseña</a></p>
                  <p style="color:#64748b;font-size:13px">El enlace vence en %s minutos y solo funciona una vez.
                     Si no fuiste tú, ignora este correo.</p>
                </div>""".formatted(name, HtmlUtils.htmlEscape(link), minutes);
        mailService.send(user.getEmail(), "Restablece tu contraseña", html,
                "Enlace de restablecimiento (vence en " + minutes + " min): " + link);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode(SecureTokens.randomToken());
        }
        return dummyHash;
    }
}
