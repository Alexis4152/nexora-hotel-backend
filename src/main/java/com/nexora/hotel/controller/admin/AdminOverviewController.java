package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.response.AuditLogResponse;
import com.nexora.hotel.dto.response.OverviewResponse;
import com.nexora.hotel.service.HotelOverviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administración · Panel y auditoría")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOverviewController {

    private final HotelOverviewService service;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('DASHBOARD:VIEW')")
    public ApiResponse<OverviewResponse> overview() {
        return ApiResponse.ok(service.overview());
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('AUDIT:VIEW')")
    public ApiResponse<PageResponse<AuditLogResponse>> audit(@RequestParam(required = false) String action,
                                                             @RequestParam(required = false) String actor,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.audit(action, actor, page, size));
    }
}
