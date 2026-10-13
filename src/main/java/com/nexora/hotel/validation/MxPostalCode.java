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

/** Código postal mexicano: 5 dígitos. {@code null} es válido. */
@Documented
@Pattern(regexp = ValidationPatterns.MX_POSTAL_CODE)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MxPostalCode {

    String message() default "El código postal debe tener 5 dígitos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
