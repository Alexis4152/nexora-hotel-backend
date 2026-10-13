package com.nexora.hotel.repository;

import com.nexora.hotel.entity.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    List<TaxRate> findAllByOrderByNameAsc();

    List<TaxRate> findByActiveTrue();
}
