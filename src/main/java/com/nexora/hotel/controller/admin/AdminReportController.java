package com.nexora.hotel.controller.admin;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.ReportDtos.Dashboard;
import com.nexora.hotel.dto.catalog.ReportDtos.ExportFile;
import com.nexora.hotel.dto.catalog.ReportDtos.Filters;
import com.nexora.hotel.dto.catalog.ReportDtos.Report;
import com.nexora.hotel.dto.catalog.ReportDtos.ReportInfo;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.service.ReportExporter;
import com.nexora.hotel.service.ReportService;
import com.nexora.hotel.tenant.HotelDirectory;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard y reportes. Cada reporte exige REPORTS:OPERATIONAL o REPORTS:FINANCIAL según su tipo (lo valida el
 * servicio); exportar exige además REPORTS:EXPORT y queda en la bitácora.
 */
@Tag(name = "Administración · Reportes")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reports;
    private final ReportExporter exporter;
    private final HotelDirectory hotelDirectory;
    private final AuditService auditService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('DASHBOARD:VIEW')")
    public ApiResponse<Dashboard> dashboard() {
        return ApiResponse.ok(reports.dashboard());
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAnyAuthority('REPORTS:OPERATIONAL','REPORTS:FINANCIAL')")
    public ApiResponse<List<ReportInfo>> catalog() {
        return ApiResponse.ok(reports.catalog());
    }

    @GetMapping("/reports/{type}")
    @PreAuthorize("hasAnyAuthority('REPORTS:OPERATIONAL','REPORTS:FINANCIAL')")
    public ApiResponse<Report> report(@PathVariable String type,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(required = false) Long roomTypeId,
                                      @RequestParam(required = false) String method,
                                      @RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String basis) {
        return ApiResponse.ok(reports.report(type, new Filters(from, to, status, source, roomTypeId, method, kind, basis), false));
    }

    @GetMapping("/reports/{type}/export")
    @PreAuthorize("hasAuthority('REPORTS:EXPORT') and hasAnyAuthority('REPORTS:OPERATIONAL','REPORTS:FINANCIAL')")
    public ResponseEntity<byte[]> export(@PathVariable String type, @RequestParam String format,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) Long roomTypeId,
                                         @RequestParam(required = false) String method,
                                         @RequestParam(required = false) String kind,
                                         @RequestParam(required = false) String basis) {
        Report report = reports.report(type, new Filters(from, to, status, source, roomTypeId, method, kind, basis), true);
        Hotel hotel = hotelDirectory.current();
        ExportFile file = exporter.export(report, format, hotel.getName(), hotel.getSlug(), hotel.zoneId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("report", type);
        detail.put("format", format.toLowerCase());
        detail.put("from", report.from().toString());
        detail.put("to", report.to().toString());
        detail.put("rows", report.rows().size());
        auditService.record(AuditEntry.success("REPORT_EXPORTED", "Report", type).after(detail).build());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
