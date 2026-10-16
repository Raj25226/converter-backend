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

    org.springframework.data.domain.Slice<Conversion> findByUser_Id(Long userId, org.springframework.data.domain.Pageable pageable);

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

    @Query("SELECT c FROM Conversion c WHERE c.expiresAt < :now AND c.fileId IS NOT NULL AND c.id > :after ORDER BY c.id")
    List<Conversion> findExpiredBatch(@Param("now") Instant now, @Param("after") long after, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE Conversion c SET c.fileId = NULL WHERE c.id IN :ids AND c.expiresAt < :now")
    int clearExpiredFiles(@Param("ids") List<Long> ids, @Param("now") Instant now);

    @Query("SELECT c.id FROM Conversion c WHERE c.expiresAt < :before AND c.fileId IS NULL ORDER BY c.id")
    List<Long> findRetiredBatch(@Param("before") Instant before, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM Conversion c WHERE c.id IN :ids AND c.expiresAt < :before AND c.fileId IS NULL")
    int deleteRetiredBatch(@Param("ids") List<Long> ids, @Param("before") Instant before);

    List<Conversion> findByExpiresAtBeforeAndFileIdIsNotNull(Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM Conversion c WHERE c.expiresAt < :now")
    int deleteExpiredConversions(@Param("now") Instant now);
}