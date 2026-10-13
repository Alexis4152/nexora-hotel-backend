package com.nexora.hotel.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens opacos (refresh, recuperación de contraseña, acceso de invitado) y contraseñas temporales.
 * El valor en claro solo existe en el cliente; en BD se guarda {@link #sha256(String)}.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    // Sin caracteres ambiguos (0/O, 1/l/I) para que el dueño del hotel pueda dictarla por teléfono.
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "@#$%&*!?";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;

    private SecureTokens() {
    }

    /** 256 bits aleatorios en Base64 URL-safe. */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Contraseña temporal de 14 caracteres que cumple la política (mayúscula, minúscula, dígito, símbolo). */
    public static String temporaryPassword() {
        char[] chars = new char[14];
        chars[0] = pick(UPPER);
        chars[1] = pick(LOWER);
        chars[2] = pick(DIGITS);
        chars[3] = pick(SYMBOLS);
        for (int i = 4; i < chars.length; i++) {
            chars[i] = pick(ALL);
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
