package me.vonyayou.geminibridge.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

public interface HistoryRepository extends JpaRepository<RequestRecord, String>, JpaSpecificationExecutor<RequestRecord> {
    long countByTimestampGreaterThanEqual(Instant since);
    long countByHttpStatusBetween(int from, int to);
    @Query("select coalesce(avg(r.durationMs), 0) from RequestRecord r") double averageLatency();
    List<RequestRecord> findTop8ByOrderByTimestampDesc();
    @Transactional
    @Modifying
    @Query("delete from RequestRecord r where r.timestamp < :cutoff") int deleteOlderThan(Instant cutoff);
}
