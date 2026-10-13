package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.domain.PricingEngine;
import com.nexora.hotel.dto.catalog.RateDtos.QuoteRequest;
import com.nexora.hotel.dto.catalog.RateDtos.RateRuleRequest;
import com.nexora.hotel.dto.catalog.RateDtos.RateRuleResponse;
import com.nexora.hotel.dto.catalog.RateDtos.SeasonRequest;
import com.nexora.hotel.dto.catalog.RateDtos.SeasonResponse;
import com.nexora.hotel.dto.catalog.RateDtos.ServiceRequest;
import com.nexora.hotel.dto.catalog.RateDtos.ServiceResponse;
import com.nexora.hotel.dto.catalog.RateDtos.TaxRequest;
import com.nexora.hotel.dto.catalog.RateDtos.TaxResponse;
import com.nexora.hotel.entity.ExtraService;
import com.nexora.hotel.entity.RateRule;
import com.nexora.hotel.entity.RoomType;
import com.nexora.hotel.entity.Season;
import com.nexora.hotel.entity.TaxRate;
import com.nexora.hotel.enums.CatalogEnums.RateRuleType;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.ExtraServiceRepository;
import com.nexora.hotel.repository.RateRuleRepository;
import com.nexora.hotel.repository.RoomTypeRepository;
import com.nexora.hotel.repository.SeasonRepository;
import com.nexora.hotel.repository.TaxRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Tarifas, temporadas, impuestos y servicios adicionales del hotel actual (todo en BD, sin redeploy). */
@Service
@RequiredArgsConstructor
public class RateService {

    private final SeasonRepository seasonRepository;
    private final RateRuleRepository ruleRepository;
    private final TaxRateRepository taxRepository;
    private final ExtraServiceRepository serviceRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final AuditService auditService;

    // ── Temporadas ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SeasonResponse> seasons() {
        return seasonRepository.findAllByOrderByStartsOnAsc().stream().map(RateService::toDto).toList();
    }

    @Transactional
    public SeasonResponse saveSeason(Long id, SeasonRequest r) {
        Season s = id == null ? new Season() : seasonRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Temporada no encontrada"));
        s.setName(r.name().trim());
        s.setKind(r.kind());
        s.setStartsOn(r.startsOn());
        s.setEndsOn(r.endsOn());
        s.setActive(r.active());
        s = seasonRepository.save(s);
        auditService.record(AuditEntry.success(id == null ? "SEASON_CREATED" : "SEASON_UPDATED", "Season", s.getId())
                .after(Map.of("name", s.getName(), "from", s.getStartsOn().toString(), "to", s.getEndsOn().toString()))
                .build());
        return toDto(s);
    }

    @Transactional
    public void deleteSeason(Long id) {
        Season s = seasonRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Temporada no encontrada"));
        if (ruleRepository.findAllByOrderByPriorityDescIdDesc().stream().anyMatch(r -> id.equals(r.getSeasonId()))) {
            throw new ConflictException("Hay reglas de tarifa que usan esta temporada. Elimínalas o cámbialas primero.");
        }
        seasonRepository.delete(s);
        auditService.record(AuditEntry.success("SEASON_DELETED", "Season", id).before(Map.of("name", s.getName())).build());
    }

    // ── Reglas de tarifa ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RateRuleResponse> rules() {
        Map<Long, String> types = roomTypeRepository.findAll().stream().collect(Collectors.toMap(RoomType::getId, RoomType::getName));
        Map<Long, String> seasons = seasonRepository.findAll().stream().collect(Collectors.toMap(Season::getId, Season::getName));
        return ruleRepository.findAllByOrderByPriorityDescIdDesc().stream()
                .sorted(Comparator.comparingInt((RateRule r) -> -r.getRuleType().rank()))
                .map(r -> toDto(r, types, seasons)).toList();
    }

    @Transactional
    public RateRuleResponse saveRule(Long id, RateRuleRequest r) {
        RateRule rule = id == null ? new RateRule() : ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Regla no encontrada"));
        if (r.roomTypeId() != null) {
            roomTypeRepository.findById(r.roomTypeId()).orElseThrow(() -> new BusinessException("El tipo de habitación no existe"));
        }
        if (r.ruleType() == RateRuleType.SEASON) {
            seasonRepository.findById(r.seasonId()).orElseThrow(() -> new BusinessException("La temporada no existe"));
        }
        rule.setName(r.name().trim());
        rule.setRoomTypeId(r.roomTypeId());
        rule.setRuleType(r.ruleType());
        rule.setSeasonId(r.ruleType() == RateRuleType.SEASON ? r.seasonId() : null);
        boolean dated = r.ruleType() == RateRuleType.DATE_OVERRIDE || r.ruleType() == RateRuleType.HOLIDAY
                || r.ruleType() == RateRuleType.WEEKEND;
        rule.setDateFrom(dated ? r.dateFrom() : null);
        rule.setDateTo(dated ? r.dateTo() : null);
        rule.setDaysOfWeek(r.ruleType() == RateRuleType.WEEKEND
                ? r.daysOfWeek().stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(",")) : null);
        rule.setAdjustmentType(r.adjustmentType());
        rule.setAdjustmentValue(r.adjustmentValue());
        rule.setMinNights(r.minNights());
        rule.setPriority(r.priority());
        rule.setActive(r.active());
        RateRule saved = ruleRepository.save(rule);
        auditService.record(AuditEntry.success(id == null ? "RATE_RULE_CREATED" : "RATE_RULE_UPDATED", "RateRule", saved.getId())
                .after(Map.of("name", saved.getName(), "type", saved.getRuleType().name(),
                        "adjustment", saved.getAdjustmentType().name() + " " + saved.getAdjustmentValue()))
                .build());
        Long savedId = saved.getId();
        return rules().stream().filter(x -> x.id().equals(savedId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteRule(Long id) {
        RateRule r = ruleRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Regla no encontrada"));
        ruleRepository.delete(r);
        auditService.record(AuditEntry.success("RATE_RULE_DELETED", "RateRule", id).before(Map.of("name", r.getName())).build());
    }

    // ── Impuestos ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TaxResponse> taxes() {
        return taxRepository.findAllByOrderByNameAsc().stream().map(RateService::toDto).toList();
    }

    @Transactional
    public TaxResponse saveTax(Long id, TaxRequest r) {
        TaxRate t = id == null ? new TaxRate() : taxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Impuesto no encontrado"));
        Map<String, Object> before = id == null ? null : Map.of("name", t.getName(), "percent", t.getPercent());
        t.setName(r.name().trim());
        t.setPercent(r.percent());
        t.setAppliesTo(r.appliesTo());
        t.setIncludedInPrice(r.includedInPrice());
        t.setActive(r.active());
        t = taxRepository.save(t);
        auditService.record(AuditEntry.success(id == null ? "TAX_CREATED" : "TAX_UPDATED", "TaxRate", t.getId())
                .before(before).after(Map.of("name", t.getName(), "percent", t.getPercent())).build());
        return toDto(t);
    }

    @Transactional
    public void deleteTax(Long id) {
        TaxRate t = taxRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Impuesto no encontrado"));
        taxRepository.delete(t);
        auditService.record(AuditEntry.success("TAX_DELETED", "TaxRate", id).before(Map.of("name", t.getName())).build());
    }

    // ── Servicios adicionales ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ServiceResponse> services() {
        return serviceRepository.findAllByOrderBySortOrderAscNameAsc().stream().map(RateService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> publicServices() {
        return serviceRepository.findByActiveTrueAndBookableOnlineTrueOrderBySortOrderAscNameAsc().stream()
                .map(RateService::toDto).toList();
    }

    @Transactional
    public ServiceResponse saveService(Long id, ServiceRequest r) {
        String name = r.name().trim();
        boolean duplicated = id == null ? serviceRepository.existsByNameIgnoreCase(name)
                : serviceRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (duplicated) {
            throw new ConflictException("Ya existe un servicio con ese nombre");
        }
        ExtraService s = id == null ? new ExtraService() : serviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Servicio no encontrado"));
        s.setName(name);
        s.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        s.setPrice(r.price());
        s.setChargeMode(r.chargeMode());
        s.setBookableOnline(r.bookableOnline());
        s.setIcon(r.icon());
        s.setSortOrder(r.sortOrder());
        s.setActive(r.active());
        s = serviceRepository.save(s);
        auditService.record(AuditEntry.success(id == null ? "SERVICE_CREATED" : "SERVICE_UPDATED", "ExtraService", s.getId())
                .after(Map.of("name", s.getName(), "price", s.getPrice(), "mode", s.getChargeMode().name())).build());
        return toDto(s);
    }

    @Transactional
    public void deleteService(Long id) {
        ExtraService s = serviceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Servicio no encontrado"));
        s.setActive(false);
        auditService.record(AuditEntry.success("SERVICE_DEACTIVATED", "ExtraService", id).build());
    }

    // ── Cotización ─────────────────────────────────────────────────────────────────────

    /** Simulador de precios con las reglas e impuestos vigentes (lo mismo que usará la reservación). */
    @Transactional(readOnly = true)
    public PricingEngine.Quote quote(QuoteRequest r) {
        RoomType type = roomTypeRepository.findById(r.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Tipo de habitación no encontrado"));
        if (r.adults() + r.children() > type.getMaxOccupancy() || r.adults() > type.getMaxAdults()
                || r.children() > type.getMaxChildren()) {
            throw new BusinessException("Los huéspedes superan la capacidad de " + type.getName()
                    + " (máximo " + type.getMaxOccupancy() + ")");
        }
        return PricingEngine.quote(rate(type), r.checkIn(), r.checkOut(), r.adults(), r.children(), activeRules(), activeTaxes());
    }

    /** Precio del hospedaje (sin impuestos) con las reglas vigentes: base de búsquedas y reservaciones. */
    @Transactional(readOnly = true)
    public PricingEngine.Quote lodging(RoomType type, java.time.LocalDate checkIn, java.time.LocalDate checkOut, int adults, int children) {
        return PricingEngine.quote(rate(type), checkIn, checkOut, adults, children, activeRules(), List.of());
    }

    @Transactional(readOnly = true)
    public List<PricingEngine.Tax> activeTaxes() {
        return taxRepository.findByActiveTrue().stream()
                .map(t -> new PricingEngine.Tax(t.getName(), t.getPercent(), t.getAppliesTo(), t.isIncludedInPrice()))
                .toList();
    }

    private List<PricingEngine.Rule> activeRules() {
        Map<Long, Season> seasons = seasonRepository.findAll().stream().filter(Season::isActive)
                .collect(Collectors.toMap(Season::getId, Function.identity()));
        return ruleRepository.findByActiveTrue().stream()
                .map(rule -> toEngine(rule, seasons))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static PricingEngine.RoomTypeRate rate(RoomType type) {
        return new PricingEngine.RoomTypeRate(type.getId(), type.getBasePrice(), type.getIncludedOccupancy(),
                type.getExtraAdultFee(), type.getExtraChildFee());
    }

    private static PricingEngine.Rule toEngine(RateRule r, Map<Long, Season> seasons) {
        if (r.getRuleType() == RateRuleType.SEASON) {
            Season s = seasons.get(r.getSeasonId());
            if (s == null) {
                return null;
            }
            return new PricingEngine.Rule(r.getId(), r.getName(), r.getRoomTypeId(), r.getRuleType(), s.getStartsOn(),
                    s.getEndsOn(), null, r.getAdjustmentType(), r.getAdjustmentValue(), r.getMinNights(), r.getPriority());
        }
        return new PricingEngine.Rule(r.getId(), r.getName(), r.getRoomTypeId(), r.getRuleType(), r.getDateFrom(),
                r.getDateTo(), PricingEngine.parseDays(r.getDaysOfWeek()), r.getAdjustmentType(), r.getAdjustmentValue(),
                r.getMinNights(), r.getPriority());
    }

    // ── Mapeo ──────────────────────────────────────────────────────────────────────────

    private static SeasonResponse toDto(Season s) {
        return new SeasonResponse(s.getId(), s.getName(), s.getKind(), s.getStartsOn(), s.getEndsOn(), s.isActive());
    }

    private static RateRuleResponse toDto(RateRule r, Map<Long, String> types, Map<Long, String> seasons) {
        List<DayOfWeek> days = r.getDaysOfWeek() == null ? List.of()
                : Arrays.stream(r.getDaysOfWeek().split(",")).map(DayOfWeek::valueOf).toList();
        return new RateRuleResponse(r.getId(), r.getName(), r.getRoomTypeId(),
                r.getRoomTypeId() == null ? null : types.get(r.getRoomTypeId()), r.getRuleType(), r.getSeasonId(),
                r.getSeasonId() == null ? null : seasons.get(r.getSeasonId()), r.getDateFrom(), r.getDateTo(), days,
                r.getAdjustmentType(), r.getAdjustmentValue(), r.getMinNights(), r.getPriority(), r.isActive());
    }

    private static TaxResponse toDto(TaxRate t) {
        return new TaxResponse(t.getId(), t.getName(), t.getPercent(), t.getAppliesTo(), t.isIncludedInPrice(), t.isActive());
    }

    private static ServiceResponse toDto(ExtraService s) {
        return new ServiceResponse(s.getId(), s.getName(), s.getDescription(), s.getPrice(), s.getChargeMode(),
                s.isBookableOnline(), s.getIcon(), s.getSortOrder(), s.isActive());
    }
}
