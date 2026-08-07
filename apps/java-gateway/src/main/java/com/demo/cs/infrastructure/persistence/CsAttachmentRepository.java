package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.CsAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CsAttachmentRepository extends JpaRepository<CsAttachment, String> {}
