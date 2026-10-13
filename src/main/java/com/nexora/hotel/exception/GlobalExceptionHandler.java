package com.nexora.hotel.exception;

import com.nexora.hotel.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduce excepciones a respuestas {@link ApiResponse} con el código HTTP correcto. Nunca expone
 * mensajes internos (SQL, clases, stack traces): esos solo van al log junto con el {@code requestId}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // SQLState de PostgreSQL relevantes para traducir errores de integridad a mensajes de negocio.
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String PROTECTED_RECORD = "42501";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.error("Revisa los campos marcados", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            errors.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
        });
        return ResponseEntity.badRequest().body(ApiResponse.error("Revisa los campos marcados", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("La solicitud no es válida"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Recurso no encontrado"));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    /** Deadlock o espera de bloqueo agotada entre operaciones simultáneas: el cliente puede reintentar. */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockFailure(org.springframework.dao.PessimisticLockingFailureException ex) {
        log.warn("Conflicto de bloqueo entre operaciones simultáneas: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "Otra operación estaba modificando lo mismo en este momento. Intenta de nuevo."));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "Otra persona modificó este registro mientras lo editabas. Recarga e intenta de nuevo."));
    }

    /**
     * Última línea de defensa: restricciones de la base (unicidad por hotel, EXCLUDE de fechas, FK)
     * que saltan cuando dos operaciones concurrentes pasaron las validaciones del servicio a la vez.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = sqlState(ex);
        String message = switch (sqlState == null ? "" : sqlState) {
            case EXCLUSION_VIOLATION -> "La habitación ya no está disponible para esas fechas";
            case UNIQUE_VIOLATION -> "Ya existe un registro con esos datos";
            case FOREIGN_KEY_VIOLATION -> "El registro está relacionado con otros datos y no se puede completar la operación";
            case CHECK_VIOLATION -> "Alguno de los datos no es válido";
            default -> null;
        };
        if (message == null) {
            log.error("Violación de integridad no clasificada", ex);
            message = "No se pudo guardar la información";
        }
        HttpStatus status = CHECK_VIOLATION.equals(sqlState) ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }

    /** Mismo mensaje exista o no la cuenta, esté bloqueada o inactiva: no se revela nada (anti-enumeración). */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(
                "Correo o contraseña incorrectos. Tras varios intentos fallidos la cuenta se bloquea unos minutos."));
    }

    @ExceptionHandler(InvalidSessionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidSession(InvalidSessionException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("No autenticado"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("No tienes permiso para realizar esta acción"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.error("Método no permitido"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error("El archivo es demasiado grande"));
    }

    /**
     * Las tablas financieras (pagos, caja, cargos) se protegen con triggers que responden 42501: si algo intenta
     * borrar o alterar un registro cerrado, se informa como conflicto y no como error interno.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex) {
        if (PROTECTED_RECORD.equals(sqlState(ex))) {
            log.warn("Intento de modificar un registro protegido: {}", ex.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                    "Ese registro ya está cerrado y no se puede modificar. Las correcciones se hacen con un movimiento nuevo."));
        }
        return handleGeneric(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Error no controlado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Ocurrió un error inesperado. Intenta de nuevo más tarde."));
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
