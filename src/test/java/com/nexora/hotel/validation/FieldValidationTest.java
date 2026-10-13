package com.nexora.hotel.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Reglas de campo compartidas (docs prompt §18). El frontend replica estas mismas reglas. */
class FieldValidationTest {

    private static Validator validator;

    record Phone(@MxPhone String value) {}
    record Name(@PersonName String value) {}
    record Taxpayer(@Rfc String value) {}
    record Company(@Rfc(kind = Rfc.Kind.PERSONA_MORAL) String value) {}
    record Postal(@MxPostalCode String value) {}
    record Color(@HexColor String value) {}

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static boolean valid(Object bean) {
        return validator.validate(bean).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5512345678", "9981234567"})
    void acceptsTenDigitMexicanPhones(String phone) {
        assertThat(valid(new Phone(phone))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"551234567", "55123456789", "55-1234-567", "+525512345678", "55 1234 5678", "abcdefghij"})
    void rejectsAnythingButTenDigits(String phone) {
        assertThat(valid(new Phone(phone))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"José", "Ñúñez", "María José", "O'Connor", "Ana-Lucía", "Zoë", "李", "Jr.", "Mª"})
    void acceptsRealNamesWithAccentsAndNonLatinLetters(String name) {
        assertThat(valid(new Name(name))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Juan3", "<script>", "Ana  María", " Ana", "Ana ", "Robert'); DROP TABLE", "@nita", ""})
    void rejectsDigitsSymbolsAndBadSpacing(String name) {
        assertThat(valid(new Name(name))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GODE561231GR8", "PEMJ800229AB1", "ABC010101AB1", "ÑAÑ8001014A5", "A&B990101XY9", "gode561231gr8"})
    void acceptsWellFormedRfcWithRealDates(String rfc) {
        assertThat(valid(new Taxpayer(rfc))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GODE561331GR8", "GODE560230GR8", "GODE5612GR8", "GODE561231GR", "1ODE561231GR8"})
    void rejectsMalformedRfcOrImpossibleDates(String rfc) {
        assertThat(valid(new Taxpayer(rfc))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GODE561231GR8"})
    void companyRfcMustHaveTwelveCharacters(String personaFisica) {
        assertThat(valid(new Company(personaFisica))).isFalse();
        assertThat(valid(new Company("ABC010101AB1"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "123456", "A1234", "12 345"})
    void rejectsInvalidPostalCodes(String cp) {
        assertThat(valid(new Postal(cp))).isFalse();
        assertThat(valid(new Postal("06600"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#fff", "155dea", "#155deaff", "red", "#15 5de", "url(javascript:alert(1))", "#gggggg"})
    void colorsMustBeStrictHex(String color) {
        assertThat(valid(new Color(color))).isFalse();
        assertThat(valid(new Color("#155DEA"))).isTrue();
    }
}
