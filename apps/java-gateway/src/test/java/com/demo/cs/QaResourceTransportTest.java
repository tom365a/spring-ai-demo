package com.demo.cs;

import com.demo.cs.infrastructure.resources.transport.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Independent hostile protocol fixtures. All sockets are ephemeral loopback; no environment keys. */
class QaResourceTransportTest {
 final ObjectMapper json=new ObjectMapper();
 final HttpToolTransport http=new HttpToolTransport(json);
 final McpToolTransport mcp=new McpToolTransport(json);
 final Duration budget=Duration.ofSeconds(3);
 interface Endpoint {void serve(HttpExchange x)throws Exception;}
 class Fixture implements AutoCloseable {
  final HttpServer server; final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
  final AtomicInteger count=new AtomicInteger(); final AtomicReference<Throwable> failure=new AtomicReference<>();
  Fixture(Endpoint endpoint)throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
   server.createContext("/",x->{count.incrementAndGet();try{endpoint.serve(x);}catch(Throwable e){if(!(e instanceof java.io.IOException)&&!(e instanceof InterruptedException))failure.compareAndSet(null,e);}finally{x.close();}});server.start();}
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  ObjectNode config(){var c=json.createObjectNode().put("url",url()).put("method","GET").put("transport","STREAMABLE_HTTP");c.putObject("target").put("scheme","http").put("host","127.0.0.1").put("port",server.getAddress().getPort()).put("allowPrivate",true);return c;}
  public void close(){server.stop(0);workers.shutdownNow();assertThat(failure.get()).as("fixture assertions").isNull();}
 }
 void reply(HttpExchange x,Object value)throws Exception {byte[] bytes=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}
 void rpc(HttpExchange x,JsonNode request,Object value)throws Exception {reply(x,Map.of("jsonrpc","2.0","id",request.path("id").asText(),"result",value));}
 boolean handshake(HttpExchange x,JsonNode r)throws Exception {
  if(r.path("method").asText().equals("initialize")){x.getResponseHeaders().set("Mcp-Session-Id","qa-session");rpc(x,r,Map.of("protocolVersion","2025-11-25","capabilities",Map.of("tools",Map.of())));return true;}
  if(r.path("method").asText().equals("notifications/initialized")){assertThat(x.getRequestHeaders().getFirst("Mcp-Session-Id")).isEqualTo("qa-session");x.sendResponseHeaders(202,-1);return true;}return false;
 }
 void error(Runnable work,String code){assertThatThrownBy(work::run).isInstanceOfSatisfying(TransportException.class,e->assertThat(e.code()).isEqualTo(code));}

 @Test void exactOriginRejectsProtocolHostUserinfoFragmentAndMetadataBeforeConnecting()throws Exception {
  try(var f=new Fixture(x->reply(x,Map.of("ok",true)))){
   for(String suffix:List.of("#fragment","@evil.invalid")) {var c=f.config().put("url",f.url()+suffix);assertThatThrownBy(()->http.execute(c,json.createObjectNode(),null,budget)).isInstanceOf(TransportException.class);}
   for(String changed:List.of(f.url().replace("http:","https:"),f.url().replace("127.0.0.1","localhost"),f.url().replace("127.0.0.1","user@127.0.0.1"))){var c=f.config().put("url",changed);assertThatThrownBy(()->http.execute(c,json.createObjectNode(),null,budget)).isInstanceOf(TransportException.class);}
   assertThat(f.count).hasValue(0);
  }
  for(String ip:List.of("169.254.170.2","192.0.0.8","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","100.100.100.200","ff02::1"))assertThatThrownBy(()->TargetPolicy.checkAddress(InetAddress.getByName(ip),true)).isInstanceOf(TransportException.class);
  assertThatThrownBy(()->TargetPolicy.checkAddress(InetAddress.getByName("100.64.0.1"),false)).isInstanceOf(TransportException.class);
 }
 @Test void pathTraversalAndHeaderInjectionAreRejectedBeforeAnyRequest()throws Exception {
  try(var f=new Fixture(x->reply(x,Map.of("ok",true)))){
   var c=f.config().put("url",f.url()+"/item/{id}");c.putArray("mappings").addObject().put("sourceKind","INPUT").put("source","id").put("targetKind","PATH").put("target","id");
   for(String value:List.of("..","a/b","a\\b"))assertThatThrownBy(()->http.execute(c,json.createObjectNode().put("id",value),null,budget)).isInstanceOf(TransportException.class);
   for(String header:List.of("Host","Content-Length","Cookie","Transfer-Encoding","Connection","Proxy-Authorization","Upgrade","TE","Trailer")){
    var h=f.config();h.putArray("mappings").addObject().put("sourceKind","LITERAL").put("literal","forged").put("targetKind","HEADER").put("target",header);assertThatThrownBy(()->http.execute(h,json.createObjectNode(),null,budget)).isInstanceOf(TransportException.class);
   }
   var injection=f.config();injection.putObject("auth").put("type","BEARER");assertThatThrownBy(()->http.execute(injection,json.createObjectNode(),"synthetic\r\nX-Injected: yes",budget)).isInstanceOf(TransportException.class);assertThat(f.count).hasValue(0);
  }
 }
 @Test void utf8ResultLimitAndChunkedResponseLimitAreAppliedToBytes()throws Exception {
  try(var f=new Fixture(x->reply(x,Map.of("text","界".repeat(12000))))){error(()->http.execute(f.config(),json.createObjectNode(),null,budget),"RESULT_TOO_LARGE");}
  try(var f=new Fixture(x->{x.sendResponseHeaders(200,0);x.getResponseBody().write(("{\"text\":\""+"a".repeat(512)+"\"}").getBytes(StandardCharsets.UTF_8));})){error(()->http.execute(f.config().put("responseMaxBytes",128),json.createObjectNode(),null,budget),"RESPONSE_TOO_LARGE");}
 }
 @Test void drippingResponseStillHonorsAbsoluteDeadlineWithoutRetryingWrite()throws Exception {
  try(var f=new Fixture(x->{x.sendResponseHeaders(200,0);for(int i=0;i<100;i++){x.getResponseBody().write(' ');x.getResponseBody().flush();Thread.sleep(50);}})){
   long began=System.nanoTime();error(()->http.execute(f.config().put("method","POST"),json.createObjectNode(),null,Duration.ofMillis(350)),"TIMEOUT");assertThat(Duration.ofNanos(System.nanoTime()-began).toMillis()).isLessThan(1800);assertThat(f.count).hasValue(1);
  }
 }
 @Test void expiredMcpSessionDoesNotReinitializeOrReplayToolCall()throws Exception {
  var methods=new CopyOnWriteArrayList<String>();try(var f=new Fixture(x->{if(x.getRequestMethod().equals("DELETE")){methods.add("DELETE");x.sendResponseHeaders(204,-1);return;}var r=json.readTree(x.getRequestBody());methods.add(r.path("method").asText());if(handshake(x,r))return;x.sendResponseHeaders(404,-1);})){
   error(()->mcp.call(f.config(),"write_once",json.createObjectNode(),null,budget),"HTTP_ERROR");assertThat(methods).containsExactly("initialize","notifications/initialized","tools/call","DELETE");
  }
 }
 @Test void mcpSseDisconnectAndMismatchedIdsFailWithoutReplay()throws Exception {
  for(boolean wrongId:List.of(false,true)) {var calls=new AtomicInteger();try(var f=new Fixture(x->{if(x.getRequestMethod().equals("DELETE")){x.sendResponseHeaders(204,-1);return;}var r=json.readTree(x.getRequestBody());if(handshake(x,r))return;calls.incrementAndGet();x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,0);String event=wrongId?"{\"jsonrpc\":\"2.0\",\"id\":\"wrong-id\",\"result\":{}}":"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}";x.getResponseBody().write(("data: "+event+"\n\n").getBytes(StandardCharsets.UTF_8));})){
   error(()->mcp.call(f.config(),"write_once",json.createObjectNode(),null,budget),"MCP_PROTOCOL_ERROR");assertThat(calls).hasValue(1);
  }}
 }
 @Test void mcpRejectsRepeatedPaginationCursorAndUnsupportedNegotiation()throws Exception {
  var pages=new AtomicInteger();try(var f=new Fixture(x->{if(x.getRequestMethod().equals("DELETE")){x.sendResponseHeaders(204,-1);return;}var r=json.readTree(x.getRequestBody());if(handshake(x,r))return;pages.incrementAndGet();rpc(x,r,Map.of("tools",List.of(),"nextCursor","same"));})){
   error(()->mcp.discover(f.config(),null,budget),"MCP_PROTOCOL_ERROR");assertThat(pages).hasValue(2);
  }
  try(var f=new Fixture(x->{var r=json.readTree(x.getRequestBody());rpc(x,r,Map.of("protocolVersion","2099-01-01","capabilities",Map.of("tools",Map.of())));})){error(()->mcp.discover(f.config(),null,budget),"MCP_PROTOCOL_ERROR");assertThat(f.count).hasValue(1);}
 }
 @Test void mcpRedactsCredentialsFromReturnedKeysAndNestedValues()throws Exception {
  String secret="qa-protocol-synthetic-secret";try(var f=new Fixture(x->{if(x.getRequestMethod().equals("DELETE")){x.sendResponseHeaders(204,-1);return;}assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+secret);var r=json.readTree(x.getRequestBody());if(handshake(x,r))return;rpc(x,r,Map.of("content",List.of(Map.of("type","text","text","echo "+secret)),secret,Map.of("nested",secret)));})){
   var c=f.config();c.putObject("auth").put("type","BEARER");var output=mcp.call(c,"qa_echo",json.createObjectNode(),secret,budget);assertThat(output.toString()).doesNotContain(secret).contains("[REDACTED]");
  }
 }
}
