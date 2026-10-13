package com.nexora.hotel.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RFC mexicano (persona física de 13 caracteres o moral de 12), con fecha real. {@code null} o vacío
 * es válido. Se compara en mayúsculas.
 */
@Documented
@Constraint(validatedBy = RfcValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Rfc {

    String message() default "El RFC no es válido";

    /** Restringe a un tipo de contribuyente; {@link Kind#ANY} acepta ambos. */
    Kind kind() default Kind.ANY;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    enum Kind { ANY, PERSONA_FISICA, PERSONA_MORAL }
}
