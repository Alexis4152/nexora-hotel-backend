package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.catalog.SettingsDtos.IdentityRequest;
import com.nexora.hotel.dto.catalog.SettingsDtos.IdentityResponse;
import com.nexora.hotel.dto.catalog.SettingsDtos.OperationSettings;
import com.nexora.hotel.dto.catalog.SettingsDtos.PolicyRequest;
import com.nexora.hotel.dto.catalog.SettingsDtos.PolicyResponse;
import com.nexora.hotel.dto.catalog.SettingsDtos.PublicHotel;
import com.nexora.hotel.dto.catalog.SettingsDtos.ThemeRequest;
import com.nexora.hotel.dto.catalog.SettingsDtos.ThemeResponse;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.HotelBranding;
import com.nexora.hotel.entity.HotelPolicy;
import com.nexora.hotel.entity.HotelSettings;
import com.nexora.hotel.entity.StoredFile;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.repository.HotelBrandingRepository;
import com.nexora.hotel.repository.HotelPolicyRepository;
import com.nexora.hotel.repository.HotelRepository;
import com.nexora.hotel.repository.HotelSettingsRepository;
import com.nexora.hotel.repository.StoredFileRepository;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.storage.MediaUrls;
import com.nexora.hotel.storage.StorageService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.util.ColorContrast;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuración del hotel actual. Todo se guarda en PostgreSQL y se refleja sin redeploy: el portal y el panel
 * leen la identidad y el tema en cada carga.
 */
@Service
@RequiredArgsConstructor
public class HotelSettingsService {

    /** Imágenes de marca administrables y su carpeta en el almacenamiento. */
    public enum BrandImage {
        LOGO("branding", HotelBranding::getLogoFileId, HotelBranding::setLogoFileId),
        LOGO_ALT("branding", HotelBranding::getLogoAltFileId, HotelBranding::setLogoAltFileId),
        FAVICON("branding", HotelBranding::getFaviconFileId, HotelBranding::setFaviconFileId),
        HERO("hero", HotelBranding::getHeroFileId, HotelBranding::setHeroFileId);

        final String folder;
        final Function<HotelBranding, Long> getter;
        final BiConsumer<HotelBranding, Long> setter;

        BrandImage(String folder, Function<HotelBranding, Long> getter, BiConsumer<HotelBranding, Long> setter) {
            this.folder = folder;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private final HotelSettingsRepository settingsRepository;
    private final HotelBrandingRepository brandingRepository;
    private final HotelPolicyRepository policyRepository;
    private final HotelRepository hotelRepository;
    private final StoredFileRepository fileRepository;
    private final StorageService storage;
    private final MediaUrls mediaUrls;
    private final HotelDirectory hotelDirectory;
    private final AuditService auditService;

    // ── Operación ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OperationSettings operation() {
        return toDto(settings());
    }

    @Transactional
    public OperationSettings updateOperation(OperationSettings request) {
        HotelSettings s = settingsForUpdate();
        OperationSettings before = toDto(s);
        s.setCheckInTime(request.checkInTime());
        s.setCheckOutTime(request.checkOutTime());
        s.setReceptionOpens(request.receptionOpens());
        s.setReceptionCloses(request.receptionCloses());
        s.setReception24h(request.reception24h());
        s.setMinBookingLeadHours(request.minBookingLeadHours());
        s.setMaxBookingLeadDays(request.maxBookingLeadDays());
        s.setMinNights(request.minNights());
        s.setMaxNights(request.maxNights());
        s.setMaxRoomsPerBooking(request.maxRoomsPerBooking());
        s.setHoldMinutes(request.holdMinutes());
        s.setAllowSameDayBooking(request.allowSameDayBooking());
        s.setPendingConfirmationHours(request.pendingConfirmationHours());
        OperationSettings after = toDto(s);
        auditService.record(AuditEntry.success("SETTINGS_UPDATED", "HotelSettings", s.getId())
                .before(diff(before, after, true)).after(diff(before, after, false)).build());
        return after;
    }

    // ── Identidad y tema ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public IdentityResponse identity() {
        return toIdentity(hotelDirectory.current(), branding());
    }

    @Transactional
    public IdentityResponse updateIdentity(IdentityRequest r) {
        HotelBranding b = brandingForUpdate();
        Hotel hotel = hotelRepository.findById(hotelDirectory.current().getId()).orElseThrow();
        String previousName = hotel.getName();
        hotel.setName(r.name().trim());
        b.setTagline(trim(r.tagline()));
        b.setDescription(trim(r.description()));
        b.setLegalName(trim(r.legalName()));
        b.setRfc(r.rfc() == null || r.rfc().isBlank() ? null : r.rfc().trim().toUpperCase());
        b.setStreet(trim(r.street()));
        b.setNeighborhood(trim(r.neighborhood()));
        b.setCity(trim(r.city()));
        b.setState(trim(r.state()));
        b.setPostalCode(trim(r.postalCode()));
        b.setLatitude(r.latitude());
        b.setLongitude(r.longitude());
        b.setPhone(trim(r.phone()));
        b.setWhatsapp(trim(r.whatsapp()));
        b.setContactEmail(r.contactEmail() == null || r.contactEmail().isBlank() ? null : r.contactEmail().trim().toLowerCase());
        b.setWebsite(trim(r.website()));
        b.setFacebook(trim(r.facebook()));
        b.setInstagram(trim(r.instagram()));
        b.setTiktok(trim(r.tiktok()));
        hotelDirectory.evict();
        auditService.record(AuditEntry.success("IDENTITY_UPDATED", "HotelBranding", b.getId())
                .before(Map.of("name", previousName)).after(Map.of("name", hotel.getName())).build());
        return toIdentity(hotel, b);
    }

    @Transactional
    public ThemeResponse updateTheme(ThemeRequest r) {
        // Botones con texto blanco y menú lateral con texto claro: contraste mínimo legible.
        if (ColorContrast.ratio(r.primary(), "#ffffff") < 3.0) {
            throw new BusinessException("El color principal es muy claro: el texto blanco de los botones no se leería");
        }
        if (ColorContrast.ratio(r.sidebar(), "#ffffff") < 4.5) {
            throw new BusinessException("El color del menú lateral es muy claro: el texto no tendría contraste suficiente");
        }
        HotelBranding b = brandingForUpdate();
        Map<String, Object> before = new HashMap<>(b.getTheme());
        Map<String, String> theme = new LinkedHashMap<>();
        theme.put("primary", r.primary().toLowerCase());
        theme.put("accent", r.accent().toLowerCase());
        theme.put("sidebar", r.sidebar().toLowerCase());
        theme.put("defaultMode", r.defaultMode());
        b.setTheme(theme);
        auditService.record(AuditEntry.success("THEME_UPDATED", "HotelBranding", b.getId())
                .before(before).after(new HashMap<>(theme)).build());
        return theme(b);
    }

    @Transactional
    public IdentityResponse uploadImage(BrandImage kind, MultipartFile file) {
        HotelBranding b = brandingForUpdate();
        StoredFile stored = storage.storeImage(file, kind.folder, "HotelBranding." + kind.name(), b.getId());
        Long previous = kind.getter.apply(b);
        kind.setter.accept(b, stored.getId());
        if (previous != null) {
            fileRepository.findById(previous).ifPresent(storage::delete);
        }
        auditService.record(AuditEntry.success("BRAND_IMAGE_UPDATED", "HotelBranding", b.getId())
                .detail(kind.name()).build());
        return toIdentity(hotelDirectory.current(), b);
    }

    @Transactional
    public IdentityResponse removeImage(BrandImage kind) {
        HotelBranding b = brandingForUpdate();
        Long previous = kind.getter.apply(b);
        if (previous != null) {
            kind.setter.accept(b, null);
            fileRepository.findById(previous).ifPresent(storage::delete);
            auditService.record(AuditEntry.success("BRAND_IMAGE_REMOVED", "HotelBranding", b.getId()).detail(kind.name()).build());
        }
        return toIdentity(hotelDirectory.current(), b);
    }

    // ── Políticas ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PolicyResponse policies() {
        return toDto(currentPolicy());
    }

    /** Crea una versión nueva; las reservaciones existentes conservan la versión que aceptaron. */
    @Transactional
    public PolicyResponse updatePolicies(PolicyRequest r) {
        HotelPolicy current = currentPolicy();
        HotelPolicy next = policyRepository.save(HotelPolicy.builder()
                .version(current.getVersion() + 1)
                .freeCancelHours(r.freeCancelHours())
                .lateCancelPenaltyType(r.lateCancelPenaltyType())
                .lateCancelPenaltyValue(r.lateCancelPenaltyValue())
                .depositPercent(r.depositPercent())
                .pendingPaymentExpiryMinutes(r.pendingPaymentExpiryMinutes())
                .noShowPenaltyType(r.noShowPenaltyType())
                .noShowPenaltyValue(r.noShowPenaltyValue())
                .childrenPolicy(trim(r.childrenPolicy()))
                .petsAllowed(r.petsAllowed())
                .petsPolicy(trim(r.petsPolicy()))
                .refundPolicy(trim(r.refundPolicy()))
                .dateChangePolicy(trim(r.dateChangePolicy()))
                .terms(trim(r.terms()))
                .privacyNotice(trim(r.privacyNotice()))
                .createdBy(SecurityUtils.currentPrincipal().map(p -> p.userId()).orElse(null))
                .build());
        auditService.record(AuditEntry.success("POLICIES_UPDATED", "HotelPolicy", next.getId())
                .before(Map.of("version", current.getVersion(), "freeCancelHours", current.getFreeCancelHours(),
                        "depositPercent", current.getDepositPercent()))
                .after(Map.of("version", next.getVersion(), "freeCancelHours", next.getFreeCancelHours(),
                        "depositPercent", next.getDepositPercent()))
                .build());
        return toDto(next);
    }

    // ── Público ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PublicHotel publicView() {
        Hotel hotel = hotelDirectory.current();
        HotelBranding b = branding();
        HotelSettings s = settings();
        String address = Stream.of(b.getStreet(), b.getNeighborhood(), b.getPostalCode(), b.getCity(), b.getState())
                .filter(Objects::nonNull).filter(v -> !v.isBlank()).collect(Collectors.joining(", "));
        return new PublicHotel(hotel.getSlug(), hotel.getName(), hotel.getTimezone(), hotel.getCurrency(),
                b.getTagline(), b.getDescription(), b.getCity(), b.getState(), address.isBlank() ? null : address,
                b.getPhone(), b.getWhatsapp(), b.getContactEmail(), b.getWebsite(), b.getFacebook(), b.getInstagram(),
                b.getTiktok(), b.getLatitude(), b.getLongitude(), mediaUrls.ofId(b.getLogoFileId()),
                mediaUrls.ofId(b.getFaviconFileId()), mediaUrls.ofId(b.getHeroFileId()), theme(b),
                s.getCheckInTime(), s.getCheckOutTime(),
                policyRepository.findFirstByOrderByVersionDesc().map(HotelPolicy::isPetsAllowed).orElse(false));
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    /** Lectura: si el hotel aún no tiene fila (hoteles anteriores a V3), valores por defecto sin escribir. */
    public HotelSettings settings() {
        return settingsRepository.findFirstBy().orElseGet(() -> HotelSettings.builder().build());
    }

    private HotelSettings settingsForUpdate() {
        return settingsRepository.findFirstBy().orElseGet(() -> settingsRepository.save(HotelSettings.builder().build()));
    }

    private HotelBranding branding() {
        return brandingRepository.findFirstBy().orElseGet(() -> HotelBranding.builder().build());
    }

    private HotelBranding brandingForUpdate() {
        return brandingRepository.findFirstBy().orElseGet(() -> brandingRepository.save(HotelBranding.builder().build()));
    }

    public HotelPolicy currentPolicy() {
        return policyRepository.findFirstByOrderByVersionDesc()
                .orElseThrow(() -> new IllegalStateException("El hotel no tiene políticas iniciales"));
    }

    private IdentityResponse toIdentity(Hotel hotel, HotelBranding b) {
        return new IdentityResponse(hotel.getName(), hotel.getSlug(), b.getTagline(), b.getDescription(), b.getLegalName(),
                b.getRfc(), b.getStreet(), b.getNeighborhood(), b.getCity(), b.getState(), b.getPostalCode(),
                b.getCountry(), b.getLatitude(), b.getLongitude(), b.getPhone(), b.getWhatsapp(), b.getContactEmail(),
                b.getWebsite(), b.getFacebook(), b.getInstagram(), b.getTiktok(), mediaUrls.ofId(b.getLogoFileId()),
                mediaUrls.ofId(b.getLogoAltFileId()), mediaUrls.ofId(b.getFaviconFileId()), mediaUrls.ofId(b.getHeroFileId()),
                theme(b));
    }

    private static ThemeResponse theme(HotelBranding b) {
        Map<String, String> t = b.getTheme() == null ? Map.of() : b.getTheme();
        ThemeResponse d = ThemeResponse.DEFAULT;
        return new ThemeResponse(t.getOrDefault("primary", d.primary()), t.getOrDefault("accent", d.accent()),
                t.getOrDefault("sidebar", d.sidebar()), t.getOrDefault("defaultMode", d.defaultMode()));
    }

    private static OperationSettings toDto(HotelSettings s) {
        return new OperationSettings(s.getCheckInTime(), s.getCheckOutTime(), s.getReceptionOpens(),
                s.getReceptionCloses(), s.isReception24h(), s.getMinBookingLeadHours(), s.getMaxBookingLeadDays(),
                s.getMinNights(), s.getMaxNights(), s.getMaxRoomsPerBooking(), s.getHoldMinutes(),
                s.isAllowSameDayBooking(), s.getPendingConfirmationHours());
    }

    private static PolicyResponse toDto(HotelPolicy p) {
        return new PolicyResponse(p.getVersion(), p.getCreatedAt(), new PolicyRequest(p.getFreeCancelHours(),
                p.getLateCancelPenaltyType(), p.getLateCancelPenaltyValue(), p.getDepositPercent(),
                p.getPendingPaymentExpiryMinutes(), p.getNoShowPenaltyType(), p.getNoShowPenaltyValue(),
                p.getChildrenPolicy(), p.isPetsAllowed(), p.getPetsPolicy(), p.getRefundPolicy(),
                p.getDateChangePolicy(), p.getTerms(), p.getPrivacyNotice()));
    }

    /** Solo los campos que cambiaron, para una bitácora legible. */
    private static Map<String, Object> diff(OperationSettings before, OperationSettings after, boolean old) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var c : OperationSettings.class.getRecordComponents()) {
            try {
                Object a = c.getAccessor().invoke(before);
                Object b = c.getAccessor().invoke(after);
                if (!Objects.equals(a, b)) {
                    result.put(c.getName(), String.valueOf(old ? a : b));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
