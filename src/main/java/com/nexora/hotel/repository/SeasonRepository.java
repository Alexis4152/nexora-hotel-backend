package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeasonRepository extends JpaRepository<Season, Long> {

    List<Season> findAllByOrderByStartsOnAsc();
}
