package com.pparra.rssreader.repository;

import com.pparra.rssreader.domain.Source;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, Long> {

    boolean existsByUrlOrFeedUrl(String url, String feedUrl);

    List<Source> findAllByOrderByNameAsc();
}
