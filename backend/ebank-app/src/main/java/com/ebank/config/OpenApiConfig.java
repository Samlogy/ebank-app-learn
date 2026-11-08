package com.ebank.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI ebankOpenApi() {
        return new OpenAPI().info(new Info()
                .title("EBANK API")
                .description("Modular monolith banking API")
                .version("v1"));
    }
}
