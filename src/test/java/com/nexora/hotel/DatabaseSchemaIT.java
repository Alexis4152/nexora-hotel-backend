package com.nexora.hotel;

import com.nexora.hotel.entity.AuditLog;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.enums.AuditResult;
import com.nexora.hotel.repository.AuditLogRepository;
import com.nexora.hotel.repository.PermissionRepository;
import com.nexora.hotel.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migraciones aplicadas, catálogo de permisos y garantías que dependen de PostgreSQL. */
class DatabaseSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void permissionCatalogMatchesDocumentation() {
        // docs/03-PERMISOS.md: 51 permisos, 4 reservados al ADMIN.
        assertThat(permissionRepository.count()).isEqualTo(51);
        assertThat(permissionRepository.countByReservedTrue()).isEqualTo(4);
        assertThat(permissionRepository.findByCode("ROLES:MANAGE")).get()
                .satisfies(p -> assertThat(p.isReserved()).isTrue());
        assertThat(permissionRepository.findByCode("RESERVATIONS:CREATE")).get()
                .satisfies(p -> assertThat(p.isDefaultReceptionist()).isTrue());
    }

    @Test
    void reservedPermissionsAreNeverDefaultForReceptionist() {
        Integer conflicts = jdbc.queryForObject(
                "SELECT count(*) FROM permissions WHERE reserved AND default_receptionist", Integer.class);
        assertThat(conflicts).isZero();
    }

    @Test
    void platformRoleExistsWithoutHotel() {
        Map<String, Object> row = jdbc.queryForMap("SELECT hotel_id, system FROM roles WHERE code = 'SUPER_ADMIN'");
        assertThat(row.get("hotel_id")).isNull();
        assertThat(row.get("system")).isEqualTo(true);
    }

    @Test
    void btreeGistExtensionIsInstalled() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void hotelSlugFormatIsEnforcedByDatabase() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO hotels (slug, name) VALUES ('Hotel Con Espacios', 'x')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditLogsCannotBeModifiedOrDeleted() {
        Hotel hotel = createHotel(true);
        AuditLog saved = TenantContext.callAs(hotel.getId(), () -> auditLogRepository.save(AuditLog.builder()
                .action("TEST_EVENT").entityType("Hotel").entityId(String.valueOf(hotel.getId()))
                .afterData(Map.of("name", hotel.getName())).result(AuditResult.SUCCESS).build()));

        assertThat(saved.getHotelId()).isEqualTo(hotel.getId());
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET action = 'TAMPERED' WHERE id = ?", saved.getId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("audit_logs").hasMessageContaining("UPDATE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", saved.getId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("audit_logs").hasMessageContaining("DELETE");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_logs"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_logs WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("TEST_EVENT");
    }
}
