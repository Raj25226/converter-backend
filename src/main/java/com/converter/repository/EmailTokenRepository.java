package com.converter.repository;

import com.converter.entity.EmailToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface EmailTokenRepository extends JpaRepository<EmailToken, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailToken> findByToken(String token);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailToken t WHERE t.user.id = :userId AND t.type = :type")
    void deleteByUserIdAndType(@Param("userId") Long userId,
                               @Param("type") EmailToken.TokenType type);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailToken t WHERE t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailToken t WHERE t.expiresAt < :now")
    void deleteExpiredTokens(@Param("now") Instant now);
}