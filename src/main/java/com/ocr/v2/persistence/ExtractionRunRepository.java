package com.ocr.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRunEntity, UUID> {

    List<ExtractionRunEntity> findTop50ByOrderByCreatedAtDesc();

    List<ExtractionRunEntity> findByFileSha256OrderByCreatedAtDesc(String fileSha256);
}
