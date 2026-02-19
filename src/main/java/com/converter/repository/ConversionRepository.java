package com.converter.repository;

import com.converter.entity.Conversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversionRepository extends JpaRepository<Conversion, Long> {

    Optional<Conversion> findByFileId(String fileId);

    List<Conversion> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
        SELECT c FROM Conversion c
        WHERE c.user.id = :userId
        ORDER BY c.createdAt DESC
        """)
    List<Conversion> findRecentByUserId(@Param("userId") Long userId);

    // Count conversions today for rate-limiting (by user)
    @Query("""
        SELECT COUNT(c) FROM Conversion c
        WHERE c.user.id = :userId
          AND c.createdAt >= :startOfDay
        """)
    long countTodayByUserId(@Param("userId") Long userId,
                            @Param("startOfDay") Instant startOfDay);

    // Count conversions today for rate-limiting (by session/guest)
    @Query("""
        SELECT COUNT(c) FROM Conversion c
        WHERE c.sessionId = :sessionId
          AND c.createdAt >= :startOfDay
          AND c.user IS NULL
        """)
    long countTodayBySessionId(@Param("sessionId") String sessionId,
                               @Param("startOfDay") Instant startOfDay);

    List<Conversion> findByExpiresAtBefore(Instant now);

    @Modifying
    @Query("DELETE FROM Conversion c WHERE c.expiresAt < :now")
    int deleteExpiredConversions(@Param("now") Instant now);
}