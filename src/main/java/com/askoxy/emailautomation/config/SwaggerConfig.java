package com.askoxy.emailautomation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI askOxyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AskOxy Email Automation API")
                        .description("API documentation for AskOxy Email Automation services")
                        .version("v1")
                        .contact(new Contact()
                                .name("AskOxy")
                                .email("support@askoxy.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://askoxy.com")));
    }
}
