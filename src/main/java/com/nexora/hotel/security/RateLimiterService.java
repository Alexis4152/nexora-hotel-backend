package com.nexora.hotel.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token bucket en memoria por clave (regla + IP), patrón de DemoPV/Citas. Suficiente para una sola
 * instancia; con varias habría que respaldarlo en Redis (bucket4j-redis) sin tocar el interceptor.
 * Las claves sin uso por más de una hora se descartan para que la memoria no crezca sin límite.
 */
@Component
public class RateLimiterService {

    private record Entry(Bucket bucket, long lastSeenNanos) {
    }

    private final ConcurrentMap<String, Entry> buckets = new ConcurrentHashMap<>();

    /** @return 0 si se permite; si no, segundos a esperar. */
    public long tryConsume(String key, int capacity, Duration window) {
        Entry entry = buckets.compute(key, (k, existing) -> new Entry(
                existing != null ? existing.bucket() : newBucket(capacity, window), System.nanoTime()));
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? 0 : Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
    }

    private static Bucket newBucket(int capacity, Duration window) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build();
    }

    @Scheduled(fixedRate = 600_000)
    void evictIdle() {
        long cutoff = System.nanoTime() - Duration.ofHours(1).toNanos();
        buckets.entrySet().removeIf(e -> e.getValue().lastSeenNanos() < cutoff);
    }
}
