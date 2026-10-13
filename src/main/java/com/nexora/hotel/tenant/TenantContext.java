package com.nexora.hotel.tenant;

import java.util.function.Supplier;

/**
 * Hotel (tenant) de la operación en curso, por hilo. Lo fija {@link TenantFilter} al inicio de cada
 * request y lo leen Hibernate ({@link TenantIdentifierResolver}, que filtra TODAS las consultas de
 * entidades {@link TenantEntity} por {@code hotel_id}) y {@link TenantGuard}.
 * <ul>
 *   <li>{@link #ROOT}: sin filtro. Solo para el SUPER_ADMIN, tareas programadas que recorren hoteles
 *       y búsquedas internas por token secreto (refresh, recuperación, webhook).</li>
 *   <li>{@link #NONE}: no se sabe el hotel (request público sin {@code X-Hotel}): no ve ningún dato.</li>
 * </ul>
 * Patrón tomado de Citas-Backend ({@code tenant/TenantContext}).
 */
public final class TenantContext {

    public static final long ROOT = 0L;
    public static final long NONE = -1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Hotel actual, {@link #ROOT} o {@link #NONE} (nunca null). */
    public static long get() {
        Long value = CURRENT.get();
        return value == null ? NONE : value;
    }

    public static void set(long tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isRoot() {
        return get() == ROOT;
    }

    /** Id del hotel actual, o null si es ROOT/NONE. */
    public static Long hotelIdOrNull() {
        long value = get();
        return value > 0 ? value : null;
    }

    /** Id del hotel actual; si no hay uno, error de negocio. */
    public static long requireHotelId() {
        Long id = hotelIdOrNull();
        if (id == null) {
            throw new MissingHotelException();
        }
        return id;
    }

    /**
     * Ejecuta {@code work} con otro tenant y restaura el anterior. Hibernate fija el tenant al ABRIR
     * la sesión, así que solo surte efecto en transacciones que empiecen dentro de {@code work}.
     */
    public static <T> T callAs(long tenant, Supplier<T> work) {
        Long previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(long tenant, Runnable work) {
        callAs(tenant, () -> {
            work.run();
            return null;
        });
    }
}
