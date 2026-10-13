package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.CustomerDetail;
import com.nexora.hotel.dto.catalog.ReservationDtos.CustomerRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.CustomerResponse;
import com.nexora.hotel.service.CustomerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administración · Clientes")
@RestController
@RequestMapping("/api/admin/customers")
@RequiredArgsConstructor
public class AdminCustomerController {

    private final CustomerService service;

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMERS:VIEW')")
    public ApiResponse<PageResponse<CustomerResponse>> list(@RequestParam(required = false) String q,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(q, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMERS:VIEW')")
    public ApiResponse<CustomerDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CUSTOMERS:CREATE')")
    public ApiResponse<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        return ApiResponse.ok(service.create(request), "Cliente registrado");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMERS:UPDATE')")
    public ApiResponse<CustomerResponse> update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        return ApiResponse.ok(service.update(id, request), "Cliente actualizado");
    }
}
