package com.nexora.hotel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Envoltura de todas las respuestas de la API: {@code {success, message, data, errors, requestId}}
 * (contrato de Citas-Backend ampliado). {@code errors} trae los errores por campo para mostrarlos
 * debajo de cada input; {@code requestId} permite localizar el error en los logs sin exponer detalles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    private Map<String, String> errors;
    private String requestId;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return ApiResponse.<T>builder().success(true).data(data).message(message).build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder().success(false).message(message).requestId(MDC.get("requestId")).build();
    }

    public static <T> ApiResponse<T> error(String message, Map<String, String> errors) {
        return ApiResponse.<T>builder().success(false).message(message).errors(errors)
                .requestId(MDC.get("requestId")).build();
    }
}
