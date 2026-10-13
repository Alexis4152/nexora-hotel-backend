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

/** Color {@code #RRGGBB}. Es la única forma de personalizar colores: nunca se acepta CSS libre. */
@Documented
@Pattern(regexp = ValidationPatterns.HEX_COLOR)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface HexColor {

    String message() default "El color debe tener el formato #RRGGBB";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
