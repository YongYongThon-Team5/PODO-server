package com.podo.server.place.repository;

import com.podo.server.place.domain.Place;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, UUID> {

    Optional<Place> findByIdAndStatus(UUID id, String status);

    /** 공개 장소 목록. name ASC(바이트 순서), id ASC 키셋 페이지네이션. */
    @Query(value = """
            SELECT p.* FROM app.places p
            WHERE p.status = 'PUBLISHED'
              AND (CAST(:districtCode AS text) IS NULL OR p.district_code = CAST(:districtCode AS text))
              AND (:useBbox = false OR (p.longitude BETWEEN :minLon AND :maxLon
                                        AND p.latitude BETWEEN :minLat AND :maxLat))
              AND (CAST(:afterName AS text) IS NULL
                   OR p.name COLLATE "C" > CAST(:afterName AS text) COLLATE "C"
                   OR (p.name = CAST(:afterName AS text) AND p.id > CAST(:afterId AS uuid)))
            ORDER BY p.name COLLATE "C", p.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Place> findPublishedPage(@Param("districtCode") String districtCode,
                                  @Param("useBbox") boolean useBbox,
                                  @Param("minLon") double minLon, @Param("minLat") double minLat,
                                  @Param("maxLon") double maxLon, @Param("maxLat") double maxLat,
                                  @Param("afterName") String afterName, @Param("afterId") String afterId,
                                  @Param("limit") int limit);

    /** [placeId 문자열, 첫 사진 URL] */
    @Query(value = "SELECT CAST(p.id AS text), p.images -> 0 ->> 'url' FROM app.places p WHERE p.id IN (:ids)",
            nativeQuery = true)
    List<Object[]> findThumbnailRows(@Param("ids") Collection<UUID> ids);

    @Query(value = "SELECT CAST(p.images AS text) FROM app.places p WHERE p.id = :id", nativeQuery = true)
    String findImagesJson(@Param("id") UUID id);

    /**
     * [placeId 문자열, 3문항 공개 세트 존재 여부, 정정(INVALIDATED) 세트 존재 여부].
     * 퀴즈 테이블은 C 담당이라 Entity 없이 읽기만 한다.
     */
    @Query(value = """
            SELECT CAST(s.place_id AS text),
                   bool_or(s.status = 'PUBLISHED'
                           AND (SELECT count(*) FROM app.quiz_questions q WHERE q.quiz_set_id = s.id) = 3),
                   bool_or(s.status = 'INVALIDATED')
            FROM app.quiz_sets s
            WHERE s.place_id IN (:ids)
            GROUP BY s.place_id
            """, nativeQuery = true)
    List<Object[]> findQuizStateRows(@Param("ids") Collection<UUID> ids);
}
