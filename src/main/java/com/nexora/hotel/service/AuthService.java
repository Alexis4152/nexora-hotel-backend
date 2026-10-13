package com.nexora.hotel.service;

import com.nexora.hotel.dto.request.ChangePasswordRequest;
import com.nexora.hotel.dto.request.LoginRequest;
import com.nexora.hotel.dto.request.ResetPasswordRequest;
import com.nexora.hotel.dto.response.SessionResponse;
import com.nexora.hotel.dto.response.SessionUser;

/**
 * Autenticación de personal, huéspedes y SUPER_ADMIN. El hotel lo determina el contexto de la request
 * ({@code TenantContext}): desde {@code /h/<slug>} se busca la cuenta en ese hotel; en la raíz, solo
 * cuentas de plataforma.
 */
public interface AuthService {

    /** Sesión emitida: el access token va al cuerpo; el refresh token, a la cookie httpOnly. */
    record LoginResult(SessionResponse session, String refreshToken) {
    }

    LoginResult login(LoginRequest request);

    /** @param expectedHotelId hotel de la cookie (null = plataforma); la cuenta debe pertenecer a él */
    LoginResult refresh(String refreshToken, Long expectedHotelId);

    void logout(String refreshToken);

    SessionUser currentSession();

    /** Siempre responde igual exista o no la cuenta (anti-enumeración). */
    void forgotPassword(String email);

    void resetPassword(ResetPasswordRequest request);

    LoginResult changePassword(ChangePasswordRequest request);
}
