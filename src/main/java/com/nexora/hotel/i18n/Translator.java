package com.nexora.hotel.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traducción de los textos del servidor al inglés, estilo gettext: el texto en español es la clave
 * ({@code i18n/en.tsv}). Las frases armadas con valores ("Saldo pendiente de $1,500") se registran como plantilla
 * con marcadores {0}, {1}… y se reconocen con una expresión regular.
 *
 * <p>El idioma lo decide el encabezado {@code Accept-Language} que envía la aplicación. Sin encabezado (correos
 * programados, webhooks, integraciones) se responde en español, el idioma original del sistema.
 */
@Component
public class Translator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static volatile Translator instance;

    private final Map<String, String> exact = new HashMap<>();
    private final List<Template> templates = new ArrayList<>();

    private record Template(Pattern pattern, List<Integer> order, String english, int literalLength) {
    }

    public Translator() {
        load("i18n/en.tsv");
        instance = this;
    }

    private void load(String resource) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new ClassPathResource(resource).getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String es = unescape(line.substring(0, tab));
                String en = unescape(line.substring(tab + 1));
                if (PLACEHOLDER.matcher(es).find()) {
                    templates.add(compile(es, en));
                } else {
                    exact.put(es, en);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + resource, e);
        }
        // Las plantillas más específicas (más texto fijo) se prueban primero.
        templates.sort(Comparator.comparingInt(Template::literalLength).reversed());
    }

    private static Template compile(String es, String en) {
        StringBuilder regex = new StringBuilder();
        List<Integer> order = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(es);
        int last = 0;
        int literal = 0;
        while (m.find()) {
            String text = es.substring(last, m.start());
            literal += text.length();
            regex.append(Pattern.quote(text)).append("(.+?)");
            order.add(Integer.parseInt(m.group(1)));
            last = m.end();
        }
        String tail = es.substring(last);
        literal += tail.length();
        regex.append(Pattern.quote(tail));
        return new Template(Pattern.compile(regex.toString(), Pattern.DOTALL), order, en, literal);
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    /** ¿La petición en curso pidió inglés? (primer idioma de Accept-Language). */
    public static boolean english() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        return english(attrs.getRequest());
    }

    public static boolean english(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        return header != null && header.trim().toLowerCase(Locale.ROOT).startsWith("en");
    }

    /** Locale para formatear números y fechas según el idioma de la petición. */
    public static Locale locale() {
        return english() ? Locale.US : Locale.forLanguageTag("es-MX");
    }

    /** Traduce si la petición es en inglés (exacto y plantillas). Para uso fuera de Spring (filtros, utilidades). */
    public static String tr(String text) {
        Translator t = instance;
        return t == null || !english() ? text : t.toEnglish(text, true);
    }

    /** Igual que {@link #tr(String)} pero con valores: tr("Hola {0}", nombre) — traduce la frase completa. */
    public static String tr(String template, Object... args) {
        String text = template;
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return tr(text);
    }

    /**
     * @param templates si se permiten plantillas. Solo en campos que el servidor redacta (mensajes, avisos,
     *                  etiquetas); el contenido capturado por usuarios solo se compara de forma exacta.
     */
    public String toEnglish(String text, boolean templates) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String hit = exact.get(text);
        if (hit != null) {
            return hit;
        }
        if (!templates) {
            return text;
        }
        for (Template t : this.templates) {
            Matcher m = t.pattern().matcher(text);
            if (m.matches()) {
                String out = t.english();
                for (int g = 1; g <= m.groupCount(); g++) {
                    String value = m.group(g);
                    out = out.replace("{" + t.order().get(g - 1) + "}", exact.getOrDefault(value, value));
                }
                return out;
            }
        }
        return text;
    }

    /** Claves registradas (español), para las pruebas de cobertura. */
    public Set<String> keys() {
        Set<String> all = new java.util.HashSet<>(exact.keySet());
        templates.forEach(t -> all.add(t.pattern().pattern()));
        return Collections.unmodifiableSet(all);
    }

    public boolean knows(String spanish) {
        return exact.containsKey(spanish) || templates.stream().anyMatch(t -> t.pattern().matcher(spanish).matches());
    }
}
