package com.ocr.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionCacheRepository extends JpaRepository<ExtractionCacheEntity, String> {
}
