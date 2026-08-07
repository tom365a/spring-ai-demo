package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, String> {}
