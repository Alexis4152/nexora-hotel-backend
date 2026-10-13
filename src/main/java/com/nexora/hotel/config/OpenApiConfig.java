package com.nexora.hotel.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentación OpenAPI en {@code /swagger-ui.html} (solo dev; el perfil prod la desactiva). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexoraOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nexora Hotel API")
                        .version("0.1.0")
                        .description("API multi-hotel. Endpoints públicos requieren el header X-Hotel con el slug del hotel; "
                                + "los autenticados usan el hotel del usuario."))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addParameters("hotel", new HeaderParameter()
                                .name("X-Hotel").description("Slug del hotel (endpoints públicos)")
                                .schema(new StringSchema())))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
