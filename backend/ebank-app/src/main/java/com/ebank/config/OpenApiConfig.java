package com.ebank.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String BEARER = "bearer";

    static {
        // Streamed bodies (the ledger CSV) are documented as text, not as an empty object.
        SpringDocUtils.getConfig().replaceWithSchema(StreamingResponseBody.class, new StringSchema());
    }

    @Bean
    OpenAPI ebankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EBANK API")
                        .description("Modular monolith banking API")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme(BEARER)
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
