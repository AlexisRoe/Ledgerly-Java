package com.ledgerly.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the checked-in OpenAPI file in Swagger UI at /swagger-ui/index.html, with /docs as the short address that
 * redirects there. The raw file is at /swagger-ui/openapi.yaml. Local only: other profiles never register these
 * handlers (API8), so there /docs is as unknown, and as protected, as any other path.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class SwaggerUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/docs", "/swagger-ui/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations(
                        "classpath:/swagger/",
                        "classpath:/api/",
                        "classpath:/META-INF/resources/webjars/swagger-ui/5.34.1/");
    }
}
