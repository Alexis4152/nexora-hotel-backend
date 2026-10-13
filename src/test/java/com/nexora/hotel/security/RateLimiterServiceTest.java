package com.nexora.hotel.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    @Test
    void blocksAfterCapacityAndReportsWaitTime() {
        RateLimiterService limiter = new RateLimiterService();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("login|10.0.0.1", 3, Duration.ofMinutes(1))).isZero();
        }
        assertThat(limiter.tryConsume("login|10.0.0.1", 3, Duration.ofMinutes(1))).isPositive();
        assertThat(limiter.tryConsume("login|10.0.0.2", 3, Duration.ofMinutes(1))).as("otra IP, otro bucket").isZero();
    }

    @Test
    void temporaryPasswordsMeetThePolicy() {
        for (int i = 0; i < 200; i++) {
            String p = SecureTokens.temporaryPassword();
            assertThat(p).hasSize(14).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*").doesNotContain("0", "O", "l", "1", "I");
        }
    }
}
