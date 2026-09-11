package com.demo.cs.application.agentconfig;
import com.demo.cs.application.resources.ManagedAgentRuntime;
import com.demo.cs.api.dto.AdminDtos.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class AgentTrialService {
 private final ManagedAgentRuntime runtime;
 public AgentTrialService(ManagedAgentRuntime runtime){this.runtime=runtime;}
 public TrialResponse trial(String code,TrialRequest request){
 long start=System.currentTimeMillis();var out=runtime.trial(code,request.text(),!Boolean.FALSE.equals(request.useDraft()),"full_route".equals(request.mode()),false);
 return new TrialResponse(out.answer(),out.agentName(),null,List.of(),out.toolCalls(),Map.of(),System.currentTimeMillis()-start,out.confirmRequired(),out.confirmationPayload(),out.diagnostics());
 }
}