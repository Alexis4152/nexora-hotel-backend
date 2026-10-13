package com.nexora.hotel.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valida estructura y fecha del RFC. No consulta al SAT: comprueba longitud por tipo de contribuyente,
 * caracteres permitidos y que AAMMDD sea una fecha real (rechaza, por ejemplo, 990231).
 */
public class RfcValidator implements ConstraintValidator<Rfc, String> {

    private static final Pattern RFC = Pattern.compile(ValidationPatterns.RFC);

    private Rfc.Kind kind;

    @Override
    public void initialize(Rfc annotation) {
        this.kind = annotation.kind();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return isValidRfc(value, kind);
    }

    public static boolean isValidRfc(String value, Rfc.Kind kind) {
        String rfc = value.trim().toUpperCase(Locale.ROOT);
        Matcher m = RFC.matcher(rfc);
        if (!m.matches()) {
            return false;
        }
        int letters = m.group(1).length();
        if (kind == Rfc.Kind.PERSONA_MORAL && letters != 3 || kind == Rfc.Kind.PERSONA_FISICA && letters != 4) {
            return false;
        }
        int yy = Integer.parseInt(m.group(2));
        int mm = Integer.parseInt(m.group(3));
        int dd = Integer.parseInt(m.group(4));
        // El siglo no viene en el RFC; ambos siglos sirven para validar día/mes (29 de febrero
        // incluido: 2000 y 1900 difieren, por eso se acepta si es válido en cualquiera de los dos).
        return isDate(1900 + yy, mm, dd) || isDate(2000 + yy, mm, dd);
    }

    private static boolean isDate(int year, int month, int day) {
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
