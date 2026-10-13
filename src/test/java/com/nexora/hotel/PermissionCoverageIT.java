package com.nexora.hotel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 10 · validación de permisos. Recorre TODOS los endpoints registrados (no una lista escrita a mano, que se
 * desactualiza): cada uno de administración y plataforma debe declarar @PreAuthorize, y un usuario cuyo rol solo
 * tiene un permiso irrelevante debe recibir 403 en cada uno.
 */
class PermissionCoverageIT extends OperationsTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private record Endpoint(HttpMethod method, String pattern, HandlerMethod handler) {
        boolean needsInput() {
            return Arrays.stream(handler.getMethodParameters()).anyMatch(Endpoint::required);
        }

        private static boolean required(MethodParameter p) {
            if (p.hasParameterAnnotation(RequestBody.class) || p.hasParameterAnnotation(RequestPart.class)) {
                return true;
            }
            RequestParam rp = p.getParameterAnnotation(RequestParam.class);
            return rp != null && rp.required() && ValueConstants.DEFAULT_NONE.equals(rp.defaultValue());
        }

        String preAuthorize() {
            PreAuthorize a = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (a == null) {
                a = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            return a == null ? null : a.value();
        }
    }

    private List<Endpoint> endpoints(String prefix) {
        List<Endpoint> list = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<String> patterns = e.getKey().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith(prefix)) {
                    continue;
                }
                e.getKey().getMethodsCondition().getMethods()
                        .forEach(m -> list.add(new Endpoint(HttpMethod.valueOf(m.name()), pattern, e.getValue())));
            }
        }
        return list;
    }

    @Test
    void everyAdminAndPlatformEndpointDeclaresAPermission() {
        List<Endpoint> all = new ArrayList<>(endpoints("/api/admin"));
        all.addAll(endpoints("/api/platform"));
        assertThat(all).as("se encontraron endpoints").hasSizeGreaterThan(80);
        List<String> unprotected = all.stream().filter(e -> e.preAuthorize() == null)
                .map(e -> e.method() + " " + e.pattern()).toList();
        assertThat(unprotected).as("endpoints sin @PreAuthorize").isEmpty();
        List<String> weak = all.stream().filter(e -> e.pattern().startsWith("/api/admin"))
                .filter(e -> !e.preAuthorize().matches(".*'[A-Z_]+:[A-Z_]+'.*"))
                .map(e -> e.method() + " " + e.pattern() + " → " + e.preAuthorize()).toList();
        assertThat(weak).as("todo endpoint de hotel exige un permiso concreto, no solo estar autenticado").isEmpty();
    }

    @Test
    void userWithoutPermissionsIsRejectedEverywhere() {
        Setup s = setup(1);
        String token = userWith(s, "AVAILABILITY:VIEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Hotel", s.slug());
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<String> leaks = new ArrayList<>();
        int checked = 0;
        for (Endpoint e : endpoints("/api/admin")) {
            if (e.preAuthorize().contains("AVAILABILITY:VIEW") || e.needsInput()) {
                continue; // con entrada obligatoria, Spring valida antes que el permiso: lo cubre la prueba estática
            }
            // Variables enum necesitan un valor válido; si no, la conversión falla (400) antes de evaluar el permiso.
            String path = e.pattern();
            for (MethodParameter p : e.handler().getMethodParameters()) {
                p.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
                var pv = p.getParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
                if (pv != null && p.getParameterType().isEnum()) {
                    String name = !pv.value().isEmpty() ? pv.value() : !pv.name().isEmpty() ? pv.name() : p.getParameterName();
                    path = path.replace("{" + name + "}", ((Enum<?>) p.getParameterType().getEnumConstants()[0]).name());
                }
            }
            path = path.replaceAll("\\{[^}]+}", "999999");
            int status = restTemplate.exchange(url(path), e.method(), new HttpEntity<>(null, headers), String.class)
                    .getStatusCode().value();
            checked++;
            if (status != 403) {
                leaks.add(e.method() + " " + path + " → " + status);
            }
        }
        assertThat(checked).as("endpoints probados en vivo").isGreaterThan(40);
        assertThat(leaks).as("respondieron algo distinto de 403").isEmpty();

        // El SUPER_ADMIN de plataforma tampoco entra al panel de un hotel, y el personal no entra a plataforma.
        assertThat(get("/api/platform/hotels", s.token(), s.slug()).status()).isEqualTo(403);
    }
}
