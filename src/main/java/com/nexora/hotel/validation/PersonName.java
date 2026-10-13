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

/**
 * Nombre o apellido de una persona real. Acepta acentos, ñ, diéresis y alfabetos no latinos; rechaza
 * dígitos y símbolos. No limita a ASCII (requisito del prompt). {@code null} es válido.
 */
@Documented
@Pattern(regexp = ValidationPatterns.PERSON_NAME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonName {

    String message() default "Solo se permiten letras, espacios, apóstrofos, guiones y puntos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
