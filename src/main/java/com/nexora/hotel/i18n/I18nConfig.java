package com.nexora.hotel.i18n;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.io.IOException;
import java.util.Set;

/**
 * Las respuestas JSON de la API salen en el idioma pedido. Se usa una COPIA del ObjectMapper solo para HTTP: la
 * auditoría, la base de datos y las llamadas a Openpay siguen serializando exactamente lo mismo que antes.
 */
@Configuration
public class I18nConfig {

    /** Campos que redacta el servidor: aquí se aceptan plantillas con valores ("Saldo pendiente de {0}"). */
    static final Set<String> SERVER_TEXT_FIELDS = Set.of("message", "errors", "warnings", "blockers", "description",
            "label", "title", "hint", "basis", "notes", "value", "reason", "series", "situation", "detail");

    @Bean
    public MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter(ObjectMapper objectMapper,
                                                                                   Translator translator) {
        ObjectMapper http = objectMapper.copy();
        SimpleModule module = new SimpleModule("nexora-i18n");
        module.addSerializer(String.class, new TranslatingStringSerializer(translator));
        http.registerModule(module);
        return new MappingJackson2HttpMessageConverter(http);
    }

    static final class TranslatingStringSerializer extends StdSerializer<String> {

        private final transient Translator translator;

        TranslatingStringSerializer(Translator translator) {
            super(String.class);
            this.translator = translator;
        }

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (!Translator.english()) {
                gen.writeString(value);
                return;
            }
            gen.writeString(translator.toEnglish(value, serverField(gen.getOutputContext())));
        }

        /** Nombre del campo (o del arreglo/mapa que contiene el valor) para decidir si admite plantillas. */
        private static boolean serverField(JsonStreamContext ctx) {
            if (ctx == null) {
                return true; // respuesta que es solo un texto
            }
            String name = ctx.inArray() ? (ctx.getParent() == null ? null : ctx.getParent().getCurrentName()) : ctx.getCurrentName();
            String parent = ctx.getParent() == null ? null : ctx.getParent().getCurrentName();
            return (name != null && SERVER_TEXT_FIELDS.contains(name)) || "errors".equals(parent);
        }
    }
}
