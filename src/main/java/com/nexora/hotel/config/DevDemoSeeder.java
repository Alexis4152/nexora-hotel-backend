package com.nexora.hotel.config;

import com.nexora.hotel.dto.request.CreateHotelRequest;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.repository.HotelRepository;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.service.HotelProvisioningService;
import com.nexora.hotel.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * SOLO perfil dev y con {@code APP_DEMO_DATA=true}: crea el hotel "gran-hotel-nexora" con un administrador y
 * una recepcionista para probar el sistema. Las contraseñas vienen de {@code APP_DEMO_PASSWORD} (en .env, fuera
 * de git). Es idempotente: si el hotel ya existe no hace nada.
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
public class DevDemoSeeder implements ApplicationRunner {

    static final String DEMO_SLUG = "gran-hotel-nexora";

    private final AppProperties props;
    private final HotelRepository hotelRepository;
    private final HotelProvisioningService provisioning;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;

    public DevDemoSeeder(AppProperties props, HotelRepository hotelRepository, HotelProvisioningService provisioning,
                         UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder,
                         PlatformTransactionManager txManager) {
        this.props = props;
        this.hotelRepository = hotelRepository;
        this.provisioning = provisioning;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = props.bootstrap().demoPassword();
        if (!props.bootstrap().demoData() || password == null || password.isBlank()) {
            return;
        }
        if (TenantContext.callAs(TenantContext.ROOT, () -> hotelRepository.existsBySlug(DEMO_SLUG))) {
            return;
        }
        var created = provisioning.createWithPassword(new CreateHotelRequest("Gran Hotel Nexora", DEMO_SLUG,
                "America/Cancun", "Mariana", "Ortega", "admin@granhotelnexora.mx"), password);

        Long hotelId = created.hotel().id();
        TenantContext.runAs(hotelId, () -> tx.executeWithoutResult(s -> {
            Role receptionist = roleRepository.findByCode(Role.RECEPTIONIST).orElseThrow();
            userRepository.save(User.builder()
                    .email("recepcion@granhotelnexora.mx")
                    .firstName("Luis")
                    .lastName("Fernández")
                    .phone("9981234567")
                    .passwordHash(passwordEncoder.encode(password))
                    .roles(new HashSet<>(Set.of(receptionist)))
                    .build());
        }));
        log.info("Hotel demo creado: {}", created.hotel().accessUrl());
    }
}
