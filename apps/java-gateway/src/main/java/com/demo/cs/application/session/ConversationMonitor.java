package com.demo.cs.application.session;
import com.demo.cs.domain.*;
import com.demo.cs.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
@Service
public class ConversationMonitor {
 private final CsSessionRepository sessions; private final CsMessageRepository messages; private final ConversationEventRepository events; private final ObjectMapper json;
 public ConversationMonitor(CsSessionRepository sessions,CsMessageRepository messages,ConversationEventRepository events,ObjectMapper json){this.sessions=sessions;this.messages=messages;this.events=events;this.json=json;}
 @Transactional public void event(String sessionId,String turnId,String type,String agentCode,Map<String,?> data){
  var e=new ConversationEvent();e.sessionId=sessionId;e.turnId=turnId;e.type=type;e.agentCode=agentCode;
  try{e.dataJson=json.writeValueAsString(data);}catch(Exception ex){throw new IllegalStateException("无法保存监控事件");}events.save(e);
 }
 public Map<String,Object> summary(CsSession s){var m=new LinkedHashMap<String,Object>();m.put("sessionId",s.getId());m.put("userId",s.getUserId());m.put("channel",s.getChannel());m.put("status",s.getStatus());m.put("createdAt",s.getCreatedAt());m.put("updatedAt",s.getUpdatedAt());m.put("lastInputAt",s.getLastInputAt());m.put("expiresAt",s.getLastInputAt().plusSeconds(300));m.put("closeReason",s.getCloseReason());m.put("endedAt",s.getEndedAt());m.put("resolutionStatus",s.getResolutionStatus());m.put("lastAgent",s.getLastAgent());m.put("lastIntent",s.getLastIntent());return m;}
 public Map<String,Object> list(String q,String status,String channel,int page,int size){
  if(status!=null&&!status.isBlank())validateStatus(status);int p=Math.max(0,page),limit=Math.max(1,Math.min(100,size));String query=q==null?"":q.toLowerCase(Locale.ROOT).trim();
  var all=sessions.findAll().stream().filter(s->status==null||status.isBlank()||s.getResolutionStatus().equals(status)).filter(s->channel==null||channel.isBlank()||channel.equals(s.getChannel())).filter(s->query.isBlank()||(s.getId()+" "+s.getUserId()+" "+s.getLastAgent()+" "+s.getLastIntent()).toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparing(CsSession::getUpdatedAt).reversed()).toList();
  return Map.of("items",all.stream().skip((long)p*limit).limit(limit).map(this::summary).toList(),"total",all.size(),"page",p,"size",limit);
 }
 public Map<String,Object> detail(String id){var s=require(id);var history=events.findBySessionIdOrderByIdAsc(id);var out=new LinkedHashMap<String,Object>();out.put("session",summary(s));out.put("messages",messages.findBySessionIdOrderByCreatedAtAsc(id).stream().map(m->{var v=new LinkedHashMap<String,Object>();v.put("id",m.getId());v.put("role",m.getRole());v.put("content",m.getContent());v.put("agentName",m.getAgentName());v.put("createdAt",m.getCreatedAt());return v;}).toList());out.put("events",history.stream().map(e->{var v=new LinkedHashMap<String,Object>();v.put("id",e.id);v.put("turnId",e.turnId);v.put("type",e.type);v.put("agentCode",e.agentCode);v.put("createdAt",e.createdAt);try{v.put("data",json.readTree(e.dataJson));}catch(Exception ignored){v.put("data",Map.of());}return v;}).toList());var a=new LinkedHashMap<String,Object>();a.put("status",s.getResolutionStatus());a.put("note",s.getAssessmentNote());a.put("assessedAt",s.getAssessedAt());a.put("source",s.getAssessedAt()==null?"UNASSESSED":"HUMAN");out.put("assessment",a);out.put("traceCoverage",history.stream().anyMatch(e->e.type.equals("TURN_STARTED"))?"RECORDED_TURNS_ONLY":"LEGACY_MESSAGES_ONLY");return out;}
 @Transactional public Map<String,Object> assess(String id,String status,String note){validateStatus(status);if(!status.equals("PENDING")&&(note==null||note.isBlank()))throw new IllegalArgumentException("请填写评价依据");if(note!=null&&note.length()>4000)throw new IllegalArgumentException("评价说明最多4000字");var s=sessions.locked(id).orElseThrow(()->new IllegalArgumentException("会话不存在"));s.setResolutionStatus(status);s.setAssessmentNote(note);s.setAssessedAt(Instant.now());sessions.save(s);event(id,null,"ASSESSMENT",null,Map.of("status",status,"note",Objects.requireNonNullElse(note,""),"source","HUMAN"));return detail(id);}
 private void validateStatus(String status){if(!(status!=null&&Set.of("PENDING","RESOLVED","UNRESOLVED").contains(status)))throw new IllegalArgumentException("无效解决状态");}
 private CsSession require(String id){return sessions.findById(id).orElseThrow(()->new IllegalArgumentException("会话不存在"));}
}
