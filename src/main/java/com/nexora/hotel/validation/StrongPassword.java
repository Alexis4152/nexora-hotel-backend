package com.nexora.hotel.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Política mínima de contraseña: 10 a 72 caracteres (72 es el límite real de BCrypt), con mayúscula,
 * minúscula y dígito. El frontend aplica la misma regla y muestra un medidor de fortaleza.
 */
@Documented
@Size(min = 10, max = 72)
@Pattern(regexp = ValidationPatterns.PASSWORD)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "La contraseña debe tener entre 10 y 72 caracteres, con mayúscula, minúscula y número";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
