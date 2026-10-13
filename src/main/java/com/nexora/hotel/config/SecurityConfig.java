package com.nexora.hotel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.JwtAuthFilter;
import com.nexora.hotel.security.JwtTokenProvider;
import com.nexora.hotel.security.PermissionResolver;
import com.nexora.hotel.security.RestAccessDeniedHandler;
import com.nexora.hotel.security.RestAuthenticationEntryPoint;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * API stateless. CSRF deshabilitado porque la autorización viaja en el header {@code Authorization}
 * (un sitio ajeno no puede ponerlo); la única cookie es la del refresh token, limitada a
 * {@code /api/auth} y con {@code SameSite} (ver docs/01 §C.8).
 * <p>
 * La autorización fina está en los controllers con {@code @PreAuthorize}; aquí solo se separa lo
 * público de lo autenticado. El frontend nunca es la única capa de protección.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AppProperties props;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public TenantFilter tenantFilter(HotelDirectory hotelDirectory, ObjectMapper objectMapper) {
        return new TenantFilter(hotelDirectory, objectMapper);
    }

    /** El filtro de tenant corre DENTRO de la cadena de seguridad; se evita que Boot lo registre otra vez. */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter tenantFilter) {
        FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(tenantFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtTokenProvider tokenProvider, UserRepository userRepository,
                                       HotelDirectory hotelDirectory, PermissionResolver permissionResolver,
                                       ObjectMapper objectMapper) {
        return new JwtAuthFilter(tokenProvider, userRepository, hotelDirectory, permissionResolver, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter jwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, TenantFilter tenantFilter, JwtAuthFilter jwtAuthFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // El cierre de una conexión en vivo (SSE) vuelve a pasar por la cadena como despacho ASYNC, ya sin
                        // token: la petición original ya se autorizó y la respuesta ya está en curso.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/auth/me", "/api/auth/change-password").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/platform/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/account/**").authenticated()
                        // Personal de hotel: nunca el SUPER_ADMIN (opera en ROOT y vería todos los hoteles).
                        .requestMatchers("/api/admin/**").hasAuthority(JwtAuthFilter.HOTEL_STAFF)
                        .requestMatchers("/api/notifications/**").hasAuthority(JwtAuthFilter.HOTEL_STAFF)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, AuthorizationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins().stream().map(String::trim).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Hotel", "X-Requested-With", "X-Reservation-Token",
                "Idempotency-Key", "X-Request-Id"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "Content-Disposition"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
