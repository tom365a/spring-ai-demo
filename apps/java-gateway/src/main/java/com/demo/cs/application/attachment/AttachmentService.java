package com.demo.cs.application.attachment;

import com.demo.cs.api.dto.ApiDtos.AttachmentResponse;
import com.demo.cs.config.AppProperties;
import com.demo.cs.domain.CsAttachment;
import com.demo.cs.infrastructure.persistence.CsAttachmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final CsAttachmentRepository attachmentRepo;
    private final Path uploadDir;

    public AttachmentService(CsAttachmentRepository attachmentRepo, AppProperties props) {
        this.attachmentRepo = attachmentRepo;
        this.uploadDir = Path.of(props.uploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException ignored) {
        }
    }

    @Transactional
    public CsAttachment upload(MultipartFile file, String sessionId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("only image uploads are allowed");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("file too large (max 5MB)");
        }

        String id = newId("att_");
        String ext = extension(contentType);
        String fileName = id + ext;
        Path target = uploadDir.resolve(fileName);

        try {
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            throw new IllegalStateException("upload failed: " + e.getMessage(), e);
        }

        CsAttachment att = new CsAttachment();
        att.setId(id);
        att.setSessionId(sessionId);
        att.setOriginalName(file.getOriginalFilename());
        att.setContentType(contentType);
        att.setFilePath(target.toString());
        att.setPublicUrl("/files/" + fileName);
        att.setSizeBytes(file.getSize());
        att.setCreatedAt(Instant.now());
        return attachmentRepo.save(att);
    }

    public CsAttachment get(String id) {
        return attachmentRepo.findById(id).orElse(null);
    }

    public AttachmentResponse toResponse(CsAttachment att) {
        return new AttachmentResponse(
                att.getId(),
                att.getOriginalName(),
                att.getContentType(),
                att.getPublicUrl(),
                att.getSizeBytes(),
                att.getSessionId()
        );
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
