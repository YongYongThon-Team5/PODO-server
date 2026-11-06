package com.podo.server.place.repository;

import com.podo.server.place.domain.Place;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PlaceAccessRepository extends Repository<Place, UUID> {

    boolean existsByIdAndStatus(UUID id, String status);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select place from Place place where place.id = :id")
    Optional<Place> findLockedById(@Param("id") UUID id);
}