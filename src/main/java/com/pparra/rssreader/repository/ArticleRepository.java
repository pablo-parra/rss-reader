package com.pparra.rssreader.repository;

import com.pparra.rssreader.domain.Article;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    boolean existsBySourceIdAndUrl(Long sourceId, String url);

    boolean existsBySourceId(Long sourceId);

    @Query("select a from Article a where a.read = false or a.readAt >= :since")
    List<Article> findVisibleOnDashboard(@Param("since") Instant since);

    @Modifying
    @Query("delete from Article a where a.sourceId = :sourceId")
    void deleteBySourceId(@Param("sourceId") Long sourceId);
}
