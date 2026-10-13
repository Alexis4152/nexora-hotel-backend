package com.nexora.hotel.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Teléfono nacional de México: exactamente 10 dígitos. {@code null} es válido (combinar con @NotBlank si es obligatorio). */
@Documented
@Pattern(regexp = ValidationPatterns.MX_PHONE)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MxPhone {

    String message() default "El teléfono debe tener exactamente 10 dígitos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
