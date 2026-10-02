package com.pparra.rssreader.repository;

import com.pparra.rssreader.domain.Article;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    @Query("select a.url from Article a where a.sourceId = :sourceId")
    List<String> findUrlsBySourceId(@Param("sourceId") Long sourceId);

    /** Articles of a source that still lack feed content or a thumbnail, so a fetch can fill them in. */
    @Query("select a from Article a where a.sourceId = :sourceId and (a.feedContentHtml is null or a.imageUrl is null)")
    List<Article> findBackfillCandidates(@Param("sourceId") Long sourceId);

    /** Read-only dashboard rows: the article bodies are not loaded. */
    @Query("select new com.pparra.rssreader.domain.Article(a.id, a.sourceId, a.url, a.title, a.publishedAt, a.fetchedAt,"
            + " a.read, a.readAt, a.contentOrigin, a.contentFailureReason, a.imageUrl)"
            + " from Article a where a.read = false or a.readAt >= :since")
    List<Article> findVisibleOnDashboard(@Param("since") Instant since);

    @Modifying
    @Query("delete from Article a where a.sourceId = :sourceId")
    void deleteBySourceId(@Param("sourceId") Long sourceId);
}
