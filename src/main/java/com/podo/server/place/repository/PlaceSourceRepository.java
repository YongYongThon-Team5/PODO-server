package com.podo.server.place.repository;

import com.podo.server.place.domain.PlaceSource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceSourceRepository extends JpaRepository<PlaceSource, UUID> {
    List<PlaceSource> findByPlaceIdAndStatusOrderByIdAsc(UUID placeId, String status);
}
