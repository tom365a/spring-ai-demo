package com.demo.cs.application.support;
import com.demo.cs.agent.model.AgentModels.TurnOutcome;
import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.application.session.*;
import com.demo.cs.domain.*;
import com.demo.cs.infrastructure.persistence.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转人工。两种模式：
 *   HUMAN（默认）—— 会话进入坐席队列，由真人在管理端认领并回复，全程无模型参与。
 *   SIMULATED     —— 由两个子 Agent 扮演人工，供无人值守演示使用，回复带【模拟人工客服】前缀。
 */
@Service
public class HumanSupportService {
 public static final List<String> CODES=List.of("human_support_1","human_support_2");
 private final SupportAssignmentRepository assignments;private final CsSessionRepository sessionRepo;private final SessionService sessions;private final ConversationMonitor monitor;private final DefinitionRegistry agents;private final SupportImAdapter adapter;private final TransactionTemplate tx;
 private final Map<String,Object> locks=new ConcurrentHashMap<>();
 private final String mode;
 public HumanSupportService(SupportAssignmentRepository assignments,CsSessionRepository sessionRepo,SessionService sessions,ConversationMonitor monitor,DefinitionRegistry agents,SupportImAdapter adapter,PlatformTransactionManager manager,
                            @Value("${app.support.mode:HUMAN}") String mode){this.assignments=assignments;this.sessionRepo=sessionRepo;this.sessions=sessions;this.monitor=monitor;this.agents=agents;this.adapter=adapter;this.tx=new TransactionTemplate(manager);this.mode=mode==null||mode.isBlank()?"HUMAN":mode.trim().toUpperCase(Locale.ROOT);}

 private boolean simulated(){return "SIMULATED".equals(mode);}

 /** 真实排队位次：WAITING 队列按进入时间排序的 1-based 序号；不在等待中返回 0。不估等待时长——没有这个数据。 */
 public int waitingPosition(String id){
  var waiting=assignments.findByStatusInOrderByAssignedAtAsc(List.of(SupportAssignment.WAITING));
  for(int i=0;i<waiting.size();i++)if(waiting.get(i).sessionId.equals(id))return i+1;
  return 0;
 }

 /** 已结束接管的会话要交还给智能客服，因此 CLOSED 不算已分配。 */
 public boolean assigned(String id){return id!=null&&assignments.findById(id).filter(a->!SupportAssignment.CLOSED.equals(a.status)).isPresent();}

 public TurnOutcome unavailable(ChatRequest request){
  var s=request.sessionId()==null?sessions.createSession(Objects.requireNonNullElse(request.userId(),"u_001"),"web"):sessions.getSession(request.sessionId());
  if(s==null||!s.getUserId().equals(Objects.requireNonNullElse(request.userId(),"u_001")))throw new SecurityException("会话不可访问");
  if("closed".equals(s.getStatus()))throw new IllegalStateException("会话已结束，请创建新会话");
  var history=sessions.allMessages(s.getId());var last=history.isEmpty()?null:history.getLast();
  if(last==null||!"user".equals(last.getRole())||!Objects.equals(last.getContent(),request.text()))sessions.appendMessage(s.getId(),"user",request.text(),null,null,null,request.attachmentIds());
  String answer="智能客服暂时无法完成本次处理，您可以点击转人工继续咨询。";
  sessions.appendMessage(s.getId(),"assistant",answer,"supervisor",null,null,List.of());
  monitor.event(s.getId(),UUID.randomUUID().toString(),"TURN_FAILED","supervisor",Map.of("reason","EXECUTION_ERROR","handoffAvailable",true));
  return new TurnOutcome(s.getId(),answer,"unavailable","supervisor",null,"执行异常",List.of(),List.of(),false,null,Map.of("handoffAvailable",true,"handoffReason","EXECUTION_ERROR"));
 }

 public synchronized Map<String,Object> transfer(String id,String user){
  var session=sessions.getSession(id);if(session==null)throw new IllegalArgumentException("会话不存在");if(!session.getUserId().equals(user))throw new SecurityException("会话不属于当前用户");
  return tx.execute(status->{var s=sessionRepo.locked(id).orElseThrow();if("closed".equals(s.getStatus()))throw new IllegalStateException("会话已结束，请重新开始会话");
   var existing=assignments.findById(id).filter(a->!SupportAssignment.CLOSED.equals(a.status));if(existing.isPresent())return view(existing.get());

   var a=assignments.findById(id).orElseGet(SupportAssignment::new);a.sessionId=id;a.assignedAt=Instant.now();a.closedAt=null;a.lastOperatorAt=null;a.lastCustomerAt=null;
   if(simulated()){
    var candidates=CODES.stream().filter(code->agents.get(code).isPresent()).toList();if(candidates.isEmpty())throw new IllegalStateException("模拟人工客服暂不可用，请稍后再试");
    Map<String,Long> load=new HashMap<>();for(var other:assignments.findAll())if(!SupportAssignment.CLOSED.equals(other.status)&&sessionRepo.findById(other.sessionId).filter(x->!"closed".equals(x.getStatus())).isPresent()&&other.agentCode!=null)load.merge(other.agentCode,1L,Long::sum);
    a.agentCode=candidates.stream().min(Comparator.comparingLong(code->load.getOrDefault(code,0L))).orElseThrow();
    a.mode="SIMULATED";a.status=SupportAssignment.ACTIVE;a.operatorId=a.agentCode;a.operatorName=a.agentCode;a.claimedAt=Instant.now();
   } else {
    a.agentCode=null;a.mode="HUMAN";a.status=SupportAssignment.WAITING;a.operatorId=null;a.operatorName=null;a.claimedAt=null;
   }
   assignments.save(a);
   s.setConfirmationPayload(null);s.setStatus("active");s.setResolutionStatus("PENDING");if(a.agentCode!=null)s.setLastAgent(a.agentCode);sessionRepo.save(s);
   monitor.event(id,null,"HUMAN_ASSIGNED",a.agentCode,Map.of("mode",a.mode,"historyRetained",true));
   sessions.appendMessage(id,"assistant",greeting(a),a.operatorName,null,null,List.of());
   return view(a);
  });
 }

 public Map<String,Object> state(String id,String user){var s=sessions.getSession(id);if(s==null||!s.getUserId().equals(user))throw new SecurityException("会话不可访问");return assignments.findById(id).filter(a->!SupportAssignment.CLOSED.equals(a.status)).map(this::view).orElse(Map.of("assigned",false));}

 private String greeting(SupportAssignment a){
  return simulated()
   ? "已转接模拟人工客服 "+a.agentCode+"，此前对话已保留。请继续描述您的问题。"
   : "已为您转接人工客服，此前对话已保留。正在为您接入坐席，请稍候，坐席接入后会在此直接回复。";
 }

 private Map<String,Object> view(SupportAssignment a){
  Map<String,Object> out=new LinkedHashMap<>();
  out.put("assigned",true);out.put("sessionId",a.sessionId);out.put("mode",a.mode);out.put("status",a.status);
  out.put("agentCode",a.agentCode);out.put("operatorName",a.operatorName);
  if(SupportAssignment.WAITING.equals(a.status)){
   int pos=waitingPosition(a.sessionId);out.put("queuePosition",pos);
   out.put("message",pos>0?"已转人工，您在坐席队列第 "+pos+" 位，坐席接入后会在此直接回复。":"已转人工，正在为您接入坐席，请稍候。");
  } else out.put("message",greeting(a));
  return out;
 }

 public TurnOutcome chat(ChatRequest request){synchronized(locks.computeIfAbsent(request.sessionId(),x->new Object())){
  var s=sessions.getSession(request.sessionId());if(s==null||!s.getUserId().equals(Objects.requireNonNullElse(request.userId(),"u_001")))throw new SecurityException("会话不可访问");if("closed".equals(s.getStatus()))throw new IllegalStateException("会话已结束");
  var a=assignments.findById(s.getId()).orElseThrow();String turn=UUID.randomUUID().toString();
  sessions.appendMessage(s.getId(),"user",request.text(),null,null,null,request.attachmentIds());
  a.lastCustomerAt=Instant.now();assignments.save(a);
  monitor.event(s.getId(),turn,"HUMAN_MESSAGE",a.operatorName,Map.of("mode",a.mode));

  if(!simulated()){
   // 真人模式：消息投递给坐席队列，这里不产生任何回复。客户端轮询等待坐席作答。
   String receipt=SupportAssignment.ACTIVE.equals(a.status)
    ? "已送达坐席，等待回复…"
    : "已送达，正在为您接入坐席…";
   return new TurnOutcome(s.getId(),receipt,"human_support",Objects.requireNonNullElse(a.operatorName,"人工客服"),1.0,"人工坐席接管",List.of(),List.of(),false,null,
     Map.of("humanSupport",view(a),"awaitingOperator",true));
  }

  var history=new ArrayList<Message>();for(var m:sessions.allMessages(s.getId())){if("user".equals(m.getRole()))history.add(new UserMessage(Objects.requireNonNullElse(m.getContent(),"")));else if("assistant".equals(m.getRole()))history.add(new AssistantMessage(Objects.requireNonNullElse(m.getContent(),"")));}
  String answer;boolean failed=false;try{answer=adapter.reply(a,history);if(answer==null||answer.isBlank())throw new IllegalStateException();}catch(RuntimeException e){failed=true;answer="模拟人工客服的回复服务暂时不可用。您的消息已记录在本会话中，请稍后继续。本演示尚未连接真实人工客服平台。";}
  answer="【模拟人工客服】"+answer;sessions.appendMessage(s.getId(),"assistant",answer,a.agentCode,null,null,List.of());
  a.lastOperatorAt=Instant.now();assignments.save(a);
  monitor.event(s.getId(),turn,failed?"HUMAN_REPLY_FAILED":"HUMAN_REPLY",a.agentCode,Map.of("mode",a.mode));
  return new TurnOutcome(s.getId(),answer,"human_support",a.agentCode,1.0,"模拟IM客服接管",List.of(),List.of(),false,null,Map.of("humanSupport",view(a)));
 }}
}
