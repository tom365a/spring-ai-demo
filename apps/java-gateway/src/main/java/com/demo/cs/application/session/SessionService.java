package com.demo.cs.application.session;

import com.demo.cs.agent.model.AgentModels.Citation;
import com.demo.cs.agent.model.AgentModels.ToolCallRecord;
import com.demo.cs.api.dto.ApiDtos.MessageResponse;
import com.demo.cs.api.dto.ApiDtos.SessionResponse;
import com.demo.cs.domain.CsMessage;
import com.demo.cs.domain.CsSession;
import com.demo.cs.infrastructure.persistence.CsMessageRepository;
import com.demo.cs.infrastructure.persistence.CsSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionService {

    private final CsSessionRepository sessionRepo;
    private final CsMessageRepository messageRepo;
    private final ObjectMapper objectMapper;
    private final int windowSize;

    public SessionService(
            CsSessionRepository sessionRepo,
            CsMessageRepository messageRepo,
            ObjectMapper objectMapper,
            com.demo.cs.config.AppProperties props
    ) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.objectMapper = objectMapper;
        this.windowSize = props.sessionWindowSize();
    }

    @Transactional
    public CsSession createSession(String userId, String channel) {
        CsSession s = new CsSession();
        s.setId(newId("s_"));
        s.setUserId(userId != null && !userId.isBlank() ? userId : "u_001");
        s.setChannel(channel != null && !channel.isBlank() ? channel : "web");
        s.setStatus("active");
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return sessionRepo.save(s);
    }

    public CsSession getSession(String sessionId) {
        return sessionRepo.findById(sessionId).orElse(null);
    }

    public List<CsSession> listByUser(String userId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 100);
        return sessionRepo.findByUserIdOrderByUpdatedAtDesc(userId).stream().limit(cap).toList();
    }

    @Transactional
    public CsSession closeSession(String sessionId) {
        CsSession s = requireSession(sessionId);
        s.setStatus("closed");
        s.setConfirmationPayload(null);
        s.setUpdatedAt(Instant.now());
        return sessionRepo.save(s);
    }

    @Transactional
    public CsMessage appendMessage(
            String sessionId,
            String role,
            String content,
            String agentName,
            List<Citation> citations,
            List<ToolCallRecord> toolCalls,
            List<String> attachmentIds
    ) {
        CsSession session = requireSession(sessionId);
        CsMessage m = new CsMessage();
        m.setId(newId("m_"));
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setAgentName(agentName);
        m.setCitationsJson(toJson(citations));
        m.setToolCallsJson(toJson(toolCalls));
        m.setAttachmentsJson(toJson(attachmentIds != null ? attachmentIds : List.of()));
        m.setCreatedAt(Instant.now());
        messageRepo.save(m);

        session.setUpdatedAt(Instant.now());
        sessionRepo.save(session);
        return m;
    }

    public List<Map<String, Object>> recentMessages(String sessionId) {
        List<CsMessage> all = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int from = Math.max(0, all.size() - windowSize);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CsMessage m : all.subList(from, all.size())) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("role", m.getRole());
            String content = m.getContent();
            if (content != null && content.length() > 300) {
                content = content.substring(0, 300) + "…";
            }
            view.put("content", content);
            view.put("agentName", m.getAgentName());
            view.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            out.add(view);
        }
        return out;
    }

    public List<CsMessage> allMessages(String sessionId) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void updateSummary(String sessionId, String summary) {
        CsSession s = requireSession(sessionId);
        s.setSummary(summary);
        s.setUpdatedAt(Instant.now());
        sessionRepo.save(s);
    }

    @Transactional
    public void updateRouting(String sessionId, String intent, String agent) {
        CsSession s = requireSession(sessionId);
        s.setLastIntent(intent);
        s.setLastAgent(agent);
        s.setUpdatedAt(Instant.now());
        sessionRepo.save(s);
    }

    @Transactional
    public void setConfirmState(String sessionId, Map<String, Object> payload) {
        CsSession s = requireSession(sessionId);
        s.setStatus("pending_confirm");
        s.setConfirmationPayload(toJson(payload));
        s.setUpdatedAt(Instant.now());
        sessionRepo.save(s);
    }

    @Transactional
    public void clearConfirm(String sessionId) {
        CsSession s = requireSession(sessionId);
        s.setStatus("active");
        s.setConfirmationPayload(null);
        s.setUpdatedAt(Instant.now());
        sessionRepo.save(s);
    }

    public Map<String, Object> getConfirmationPayload(CsSession session) {
        if (session.getConfirmationPayload() == null || session.getConfirmationPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(session.getConfirmationPayload(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    public SessionResponse toResponse(CsSession session, boolean includeMessages) {
        List<MessageResponse> messages = List.of();
        if (includeMessages) {
            messages = allMessages(session.getId()).stream().map(this::toMessageResponse).toList();
        }
        return new SessionResponse(
                session.getId(),
                session.getUserId(),
                session.getChannel(),
                session.getStatus(),
                session.getSummary(),
                session.getLastIntent(),
                session.getLastAgent(),
                getConfirmationPayload(session),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                messages
        );
    }

    public MessageResponse toMessageResponse(CsMessage m) {
        return new MessageResponse(
                m.getId(),
                m.getRole(),
                m.getContent(),
                m.getAgentName(),
                readList(m.getCitationsJson(), new TypeReference<List<Citation>>() {}),
                readList(m.getToolCallsJson(), new TypeReference<List<ToolCallRecord>>() {}),
                readList(m.getAttachmentsJson(), new TypeReference<List<String>>() {}),
                m.getCreatedAt()
        );
    }

    private CsSession requireSession(String sessionId) {
        return sessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private <T> T readList(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
