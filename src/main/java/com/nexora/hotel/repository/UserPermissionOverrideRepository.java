package com.nexora.hotel.repository;

import com.nexora.hotel.entity.UserPermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPermissionOverrideRepository extends JpaRepository<UserPermissionOverride, Long> {

    @Query("select o from UserPermissionOverride o join fetch o.permission where o.userId = :userId")
    List<UserPermissionOverride> findByUserIdWithPermission(@Param("userId") Long userId);

    @Modifying
    @Query("delete from UserPermissionOverride o where o.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
