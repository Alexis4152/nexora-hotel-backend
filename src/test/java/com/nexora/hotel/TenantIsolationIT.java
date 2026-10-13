package com.nexora.hotel;

import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lo de un hotel nunca se ve ni se toca desde otro (requisito central del prompt). */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    private Role saveRole(Hotel hotel, String code) {
        return TenantContext.callAs(hotel.getId(), () -> roleRepository.save(
                Role.builder().code(code).name(code).build()));
    }

    private User saveUser(Hotel hotel, String email) {
        return TenantContext.callAs(hotel.getId(), () -> userRepository.save(User.builder()
                .email(email).passwordHash("$2a$12$not-a-real-hash-only-for-tests-xxxxxxxxxxxxxxxxxxxxxx")
                .firstName("Ana").lastName("Pérez").build()));
    }

    @Test
    void insertTakesTheCurrentHotelAutomatically() {
        Hotel hotel = createHotel(true);
        Role role = saveRole(hotel, "HOUSEKEEPING");
        assertThat(role.getHotelId()).isEqualTo(hotel.getId());
    }

    @Test
    void queriesOnlyReturnDataOfTheCurrentHotel() {
        Hotel a = createHotel(true);
        Hotel b = createHotel(true);
        saveRole(a, "ONLY_IN_A");
        saveRole(b, "ONLY_IN_B");

        List<String> visibleFromA = TenantContext.callAs(a.getId(),
                () -> roleRepository.findAll().stream().map(Role::getCode).toList());

        assertThat(visibleFromA).contains("ONLY_IN_A").doesNotContain("ONLY_IN_B", Role.SUPER_ADMIN);
        assertThat(TenantContext.callAs(a.getId(), () -> roleRepository.findByCode("ONLY_IN_B"))).isEmpty();
    }

    @Test
    void loadingAnotherHotelsRecordByIdIsNotFound() {
        Hotel a = createHotel(true);
        Hotel b = createHotel(true);
        Role foreign = saveRole(b, "FOREIGN");

        boolean visible = TenantContext.callAs(a.getId(), () -> {
            try {
                return roleRepository.findById(foreign.getId()).isPresent();
            } catch (ResourceNotFoundException e) {
                return false;
            }
        });

        assertThat(visible).isFalse();
    }

    @Test
    void requestWithoutHotelSeesNothingAndCannotWrite() {
        Hotel a = createHotel(true);
        saveRole(a, "INVISIBLE");

        assertThat(TenantContext.callAs(TenantContext.NONE, () -> roleRepository.findAll())).isEmpty();
        assertThatThrownBy(() -> TenantContext.callAs(TenantContext.NONE,
                () -> roleRepository.save(Role.builder().code("ORPHAN").name("x").build())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameEmailIsAllowedInDifferentHotelsButNotTwiceInTheSameHotel() {
        Hotel a = createHotel(true);
        Hotel b = createHotel(true);

        saveUser(a, "Recepcion@Ejemplo.MX");
        User inB = saveUser(b, "recepcion@ejemplo.mx");

        assertThat(inB.getEmail()).isEqualTo("recepcion@ejemplo.mx");
        assertThatThrownBy(() -> saveUser(a, "recepcion@ejemplo.mx"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
