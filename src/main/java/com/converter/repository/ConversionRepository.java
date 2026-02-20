package com.converter.repository;

import com.converter.entity.Conversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversionRepository extends JpaRepository<Conversion, Long> {

    Optional<Conversion> findByFileId(String fileId);

    @Query("""
        SELECT c FROM Conversion c
        WHERE c.user.id = :userId
        ORDER BY c.createdAt DESC
        """)
    List<Conversion> findRecentByUserId(@Param("userId") Long userId);

    /** Count conversions made today by an authenticated user (for daily rate limiting). */
    @Query("""
        SELECT COUNT(c) FROM Conversion c
        WHERE c.user.id = :userId
          AND c.createdAt >= :startOfDay
        """)
    long countTodayByUserId(@Param("userId") Long userId,
                            @Param("startOfDay") Instant startOfDay);

    /** Count conversions made today by a guest session (for daily rate limiting). */
    @Query("""
        SELECT COUNT(c) FROM Conversion c
        WHERE c.sessionId = :sessionId
          AND c.user IS NULL
          AND c.createdAt >= :startOfDay
        """)
    long countTodayBySessionId(@Param("sessionId") String sessionId,
                               @Param("startOfDay") Instant startOfDay);

    List<Conversion> findByExpiresAtBefore(Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM Conversion c WHERE c.expiresAt < :now")
    int deleteExpiredConversions(@Param("now") Instant now);
}