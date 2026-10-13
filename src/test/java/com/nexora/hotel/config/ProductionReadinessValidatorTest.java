package com.nexora.hotel.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El perfil prod no debe arrancar con configuración insegura. */
class ProductionReadinessValidatorTest {

    private static final String STRONG_JWT = "q7Vt9zX2mR4kP8wL1nB6cY3hJ5sD0fG2aQ9eT7uI4oP1";
    private static final String STRONG_KEY = "Zx8Cv6Bn4Mm2Lk0Jh9Gf7Ds5Aq3We1Rt8Yu6Io4Pa2Sd";

    private static AppProperties props(String jwt, String key, boolean secureCookie, String storageDir, String origin) {
        return new AppProperties("https://hotel.nexora.mx", "https://api.nexora.mx",
                new AppProperties.Cors(List.of(origin)),
                new AppProperties.Jwt(jwt, Duration.ofMinutes(20), Duration.ofHours(10)),
                new AppProperties.Cookie(secureCookie, "None"),
                new AppProperties.Encryption(key),
                new AppProperties.Storage(storageDir, 8_000_000),
                new AppProperties.Security(true, 5, Duration.ofMinutes(15), Duration.ofMinutes(30)),
                new AppProperties.Bootstrap(null, null, false, null),
                new AppProperties.Mail(null, 587, null, null, "no-reply@nexora.mx"),
                new AppProperties.Payments("https://sandbox-api.openpay.mx", "https://api.openpay.mx", Duration.ofSeconds(20)));
    }

    private static String absoluteDir() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void acceptsSecureConfiguration() {
        var validator = new ProductionReadinessValidator(
                props(STRONG_JWT, STRONG_KEY, true, absoluteDir(), "https://hotel.nexora.mx"));
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsPlaceholderSecrets() {
        var validator = new ProductionReadinessValidator(
                props("solo-para-desarrollo-local-nunca-usar", STRONG_KEY, true, absoluteDir(), "https://hotel.nexora.mx"));
        assertThatThrownBy(validator::validate).hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void rejectsSameSecretForJwtAndEncryption() {
        var validator = new ProductionReadinessValidator(
                props(STRONG_JWT, STRONG_JWT, true, absoluteDir(), "https://hotel.nexora.mx"));
        assertThatThrownBy(validator::validate).hasMessageContaining("distintos");
    }

    @Test
    void rejectsInsecureCookieRelativeStorageAndLocalOrigins() {
        var validator = new ProductionReadinessValidator(
                props(STRONG_JWT, STRONG_KEY, false, "uploads", "http://localhost:5180"));
        assertThatThrownBy(validator::validate)
                .hasMessageContaining("APP_COOKIE_SECURE")
                .hasMessageContaining("APP_STORAGE_DIR")
                .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS");
    }
}
