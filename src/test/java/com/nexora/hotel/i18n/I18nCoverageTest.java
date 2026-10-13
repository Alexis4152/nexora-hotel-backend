package com.nexora.hotel.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Todo mensaje fijo que el servidor muestra al usuario (errores de negocio, validaciones y avisos de éxito) debe
 * tener traducción en i18n/en.tsv. Si agregas un mensaje nuevo y esta prueba falla, agrégalo al archivo.
 */
class I18nCoverageTest {

    private static final String LITERAL = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final List<Pattern> USER_MESSAGES = List.of(
            Pattern.compile("(?:BusinessException|ConflictException|ResourceNotFoundException|AccessDeniedException)\\(\\s*"
                    + LITERAL + "\\s*\\)"),
            Pattern.compile("\\bmessage\\s*=\\s*" + LITERAL),
            Pattern.compile("ApiResponse\\.(?:ok|error)\\([^;]*?" + LITERAL + "\\s*\\)\\s*;"));

    private final Translator translator = new Translator();

    @Test
    void everyFixedUserMessageHasAnEnglishTranslation() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            // config/ valida variables de entorno al arrancar: lo lee quien despliega, nunca un usuario de la API.
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("config")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                for (Pattern p : USER_MESSAGES) {
                    Matcher m = p.matcher(src);
                    while (m.find()) {
                        String text = m.group(1).replace("\\n", "\n").replace("\\\"", "\"");
                        if (!translator.knows(text)) {
                            missing.add(file.getFileName() + ": " + text);
                        }
                    }
                }
            }
        }
        assertThat(missing).as("agrega estas frases a src/main/resources/i18n/en.tsv").isEmpty();
    }

    @Test
    void templatesKeepTheirValuesAndReorderThem() {
        assertThat(translator.toEnglish("Saldo pendiente de $1,500.00", true)).isEqualTo("Balance due of $1,500.00");
        assertThat(translator.toEnglish("La estancia debe ser de 1 a 30 noches", true)).isEqualTo("The stay must be 1 to 30 nights");
        // Los valores que también son textos conocidos se traducen dentro de la frase.
        assertThat(translator.toEnglish("Una reservación pendiente de pago no puede pasar a en estancia", true))
                .isEqualTo("A reservation that is awaiting payment can't change to in house");
        // Contenido libre: sin plantillas solo cambia si coincide exacto.
        assertThat(translator.toEnglish("Saldo pendiente de $1,500.00", false)).isEqualTo("Saldo pendiente de $1,500.00");
        assertThat(translator.toEnglish("Frase escrita por un huésped", true)).isEqualTo("Frase escrita por un huésped");
    }
}
