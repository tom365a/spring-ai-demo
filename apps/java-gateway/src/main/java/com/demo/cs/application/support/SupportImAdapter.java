package com.demo.cs.application.support;
import com.demo.cs.domain.SupportAssignment;
import org.springframework.ai.chat.messages.Message;
import java.util.List;
/** Replace this adapter with a platform IM client; simulation is explicitly identified. */
public interface SupportImAdapter {
 String reply(SupportAssignment assignment,List<Message> publicHistory);
}
