package com.nexora.hotel.controller.admin;

import com.nexora.hotel.domain.PricingEngine;
import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.RateDtos.QuoteRequest;
import com.nexora.hotel.dto.catalog.RateDtos.RateRuleRequest;
import com.nexora.hotel.dto.catalog.RateDtos.RateRuleResponse;
import com.nexora.hotel.dto.catalog.RateDtos.SeasonRequest;
import com.nexora.hotel.dto.catalog.RateDtos.SeasonResponse;
import com.nexora.hotel.dto.catalog.RateDtos.ServiceRequest;
import com.nexora.hotel.dto.catalog.RateDtos.ServiceResponse;
import com.nexora.hotel.dto.catalog.RateDtos.TaxRequest;
import com.nexora.hotel.dto.catalog.RateDtos.TaxResponse;
import com.nexora.hotel.service.RateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Administración · Tarifas y servicios")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminRateController {

    private final RateService service;

    @GetMapping("/seasons")
    @PreAuthorize("hasAuthority('RATES:VIEW')")
    public ApiResponse<List<SeasonResponse>> seasons() {
        return ApiResponse.ok(service.seasons());
    }

    @PostMapping("/seasons")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<SeasonResponse> createSeason(@Valid @RequestBody SeasonRequest request) {
        return ApiResponse.ok(service.saveSeason(null, request), "Temporada creada");
    }

    @PutMapping("/seasons/{id}")
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<SeasonResponse> updateSeason(@PathVariable Long id, @Valid @RequestBody SeasonRequest request) {
        return ApiResponse.ok(service.saveSeason(id, request), "Temporada actualizada");
    }

    @DeleteMapping("/seasons/{id}")
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<Void> deleteSeason(@PathVariable Long id) {
        service.deleteSeason(id);
        return ApiResponse.ok(null, "Temporada eliminada");
    }

    @GetMapping("/rate-rules")
    @PreAuthorize("hasAuthority('RATES:VIEW')")
    public ApiResponse<List<RateRuleResponse>> rules() {
        return ApiResponse.ok(service.rules());
    }

    @PostMapping("/rate-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<RateRuleResponse> createRule(@Valid @RequestBody RateRuleRequest request) {
        return ApiResponse.ok(service.saveRule(null, request), "Regla creada");
    }

    @PutMapping("/rate-rules/{id}")
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<RateRuleResponse> updateRule(@PathVariable Long id, @Valid @RequestBody RateRuleRequest request) {
        return ApiResponse.ok(service.saveRule(id, request), "Regla actualizada");
    }

    @DeleteMapping("/rate-rules/{id}")
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        service.deleteRule(id);
        return ApiResponse.ok(null, "Regla eliminada");
    }

    @GetMapping("/taxes")
    @PreAuthorize("hasAuthority('RATES:VIEW')")
    public ApiResponse<List<TaxResponse>> taxes() {
        return ApiResponse.ok(service.taxes());
    }

    @PostMapping("/taxes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<TaxResponse> createTax(@Valid @RequestBody TaxRequest request) {
        return ApiResponse.ok(service.saveTax(null, request), "Impuesto creado");
    }

    @PutMapping("/taxes/{id}")
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<TaxResponse> updateTax(@PathVariable Long id, @Valid @RequestBody TaxRequest request) {
        return ApiResponse.ok(service.saveTax(id, request), "Impuesto actualizado");
    }

    @DeleteMapping("/taxes/{id}")
    @PreAuthorize("hasAuthority('RATES:MANAGE')")
    public ApiResponse<Void> deleteTax(@PathVariable Long id) {
        service.deleteTax(id);
        return ApiResponse.ok(null, "Impuesto eliminado");
    }

    @PostMapping("/rates/quote")
    @PreAuthorize("hasAnyAuthority('RATES:VIEW', 'AVAILABILITY:VIEW')")
    public ApiResponse<PricingEngine.Quote> quote(@Valid @RequestBody QuoteRequest request) {
        return ApiResponse.ok(service.quote(request));
    }

    @GetMapping("/extra-services")
    @PreAuthorize("hasAuthority('SERVICES:VIEW')")
    public ApiResponse<List<ServiceResponse>> services() {
        return ApiResponse.ok(service.services());
    }

    @PostMapping("/extra-services")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SERVICES:MANAGE')")
    public ApiResponse<ServiceResponse> createService(@Valid @RequestBody ServiceRequest request) {
        return ApiResponse.ok(service.saveService(null, request), "Servicio creado");
    }

    @PutMapping("/extra-services/{id}")
    @PreAuthorize("hasAuthority('SERVICES:MANAGE')")
    public ApiResponse<ServiceResponse> updateService(@PathVariable Long id, @Valid @RequestBody ServiceRequest request) {
        return ApiResponse.ok(service.saveService(id, request), "Servicio actualizado");
    }

    @DeleteMapping("/extra-services/{id}")
    @PreAuthorize("hasAuthority('SERVICES:MANAGE')")
    public ApiResponse<Void> deleteService(@PathVariable Long id) {
        service.deleteService(id);
        return ApiResponse.ok(null, "Servicio desactivado");
    }
}
