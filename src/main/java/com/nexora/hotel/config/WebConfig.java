package com.nexora.hotel.config;

import com.nexora.hotel.security.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra el rate limiting (se apaga solo con {@code app.security.rate-limit-enabled=false}, en pruebas). */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (props.security().rateLimitEnabled()) {
            registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
        }
    }
}
