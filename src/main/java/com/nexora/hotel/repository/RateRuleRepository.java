package com.nexora.hotel.repository;

import com.nexora.hotel.entity.RateRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RateRuleRepository extends JpaRepository<RateRule, Long> {

    List<RateRule> findAllByOrderByPriorityDescIdDesc();

    List<RateRule> findByActiveTrue();
}
