package com.converter.repository;

import com.converter.entity.EmailToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface EmailTokenRepository extends JpaRepository<EmailToken, Long> {

    Optional<EmailToken> findByToken(String token);

    @Modifying
    @Query("DELETE FROM EmailToken t WHERE t.user.id = :userId AND t.type = :type")
    void deleteByUserIdAndType(@Param("userId") Long userId,
                               @Param("type") EmailToken.TokenType type);

    @Modifying
    @Query("DELETE FROM EmailToken t WHERE t.expiresAt < :now")
    void deleteExpiredTokens(@Param("now") Instant now);
}