package com.converter.repository;

import com.converter.entity.ToolStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ToolStatRepository extends JpaRepository<ToolStat, String> {

    @Modifying
    @Query("""
        UPDATE ToolStat t SET t.count = t.count + 1 WHERE t.tool = :tool
        """)
    int incrementCount(@Param("tool") String tool);

    List<ToolStat> findAllByOrderByCountDesc();
}