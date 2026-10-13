package com.nexora.hotel.validation;

/**
 * Expresiones regulares compartidas por las anotaciones de validación y espejadas en el frontend
 * ({@code src/utils/validators.js}). Cambiar una aquí obliga a cambiarla allá.
 */
public final class ValidationPatterns {

    /** Celular/teléfono nacional de México: exactamente 10 dígitos. */
    public static final String MX_PHONE = "^\\d{10}$";

    /** Código de país E.164 sin el "+": 1 a 3 dígitos, sin cero inicial. */
    public static final String COUNTRY_CODE = "^[1-9]\\d{0,2}$";

    /** Teléfono internacional (sin código de país): 6 a 14 dígitos. */
    public static final String INTL_PHONE = "^\\d{6,14}$";

    /** Código postal mexicano: 5 dígitos. */
    public static final String MX_POSTAL_CODE = "^\\d{5}$";

    /**
     * Nombres y apellidos reales: letras de cualquier alfabeto (acentos, ñ, ü...), marcas combinantes,
     * espacios internos simples, apóstrofo, guion y punto. No permite dígitos ni símbolos.
     */
    public static final String PERSON_NAME = "^[\\p{L}\\p{M}]+(?:[ '’.\\-][\\p{L}\\p{M}]+)*\\.?$";

    /**
     * RFC del SAT. Persona moral: 3 letras + fecha AAMMDD + homoclave (12). Persona física: 4 letras +
     * fecha + homoclave (13). Acepta Ñ y &amp;. La fecha se valida aparte en {@link RfcValidator}.
     */
    public static final String RFC = "^([A-ZÑ&]{3,4})(\\d{2})(\\d{2})(\\d{2})([A-Z\\d]{2}[A\\d])$";

    /** Color hexadecimal de 6 dígitos con "#" (nunca CSS libre). */
    public static final String HEX_COLOR = "^#[0-9A-Fa-f]{6}$";

    /** Slug de hotel: minúsculas, dígitos y guiones, sin guion al inicio ni al final, 3 a 60. */
    public static final String SLUG = "^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$";

    /** Al menos una minúscula, una mayúscula y un dígito (la longitud se valida aparte). */
    public static final String PASSWORD = "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\d).+$";

    private ValidationPatterns() {
    }
}
