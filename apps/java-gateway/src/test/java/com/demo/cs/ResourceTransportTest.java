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
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ResourceTransportTest {
    final ObjectMapper json=new ObjectMapper();
    final HttpToolTransport http=new HttpToolTransport(json);
    final McpToolTransport mcp=new McpToolTransport(json);
    final Duration timeout=Duration.ofSeconds(4);
    interface Handler { void respond(HttpExchange x)throws Exception; }
    class Server implements AutoCloseable {
        final HttpServer server; final AtomicInteger count=new AtomicInteger();
        final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        Server(Handler handler)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",x->{count.incrementAndGet();try{handler.respond(x);}catch(Exception error){try{x.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{x.close();}});server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        ObjectNode config(){ObjectNode c=json.createObjectNode().put("url",url()).put("method","GET");
            c.putObject("target").put("scheme","http").put("host","127.0.0.1").put("port",server.getAddress().getPort()).put("allowPrivate",true);return c;}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    void reply(HttpExchange x,int status,Object value)throws Exception {
        byte[] bytes=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
    }
    JsonNode node(String value)throws Exception{return json.readTree(value);}

    @Test void httpSupportsTypedMappingsMethodsExtractionAndCredentialRedaction()throws Exception {
        List<String> methods=new CopyOnWriteArrayList<>();
        try(Server server=new Server(x->{
            methods.add(x.getRequestMethod());
            assertThat(x.getRequestURI().getRawPath()).isEqualTo("/orders/id%20with%20space");
            assertThat(x.getRequestURI().getRawQuery()).isEqualTo("term=%E4%B8%AD%E6%96%87%20%26%3F");
            assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-credential");
            assertThat(x.getRequestHeaders().getFirst("X-Tag")).isEqualTo("fixed-tag");
            if(!x.getRequestMethod().equals("GET")) {
                JsonNode body=json.readTree(x.getRequestBody());assertThat(body.path("items").get(0).path("count").asInt()).isEqualTo(2);
            }
            reply(x,200,Map.of("data",Map.of("result",List.of(Map.of("state","ok","secret","synthetic-credential")))));
        })) {
            for(String method:List.of("GET","POST","PUT","PATCH","DELETE")) {
                var config=server.config().put("method",method).put("url",server.url()+"/orders/{id}").put("responsePath","data.result[0]");
                config.putObject("auth").put("type","BEARER");
                var mappings=config.putArray("mappings");
                mappings.addObject().put("sourceKind","INPUT").put("source","id").put("targetKind","PATH").put("target","id");
                mappings.addObject().put("sourceKind","INPUT").put("source","search").put("targetKind","QUERY").put("target","term");
                mappings.addObject().put("sourceKind","LITERAL").put("literal","fixed-tag").put("targetKind","HEADER").put("target","X-Tag");
                if(!method.equals("GET"))mappings.addObject().put("sourceKind","INPUT").put("source","count").put("targetKind","BODY").put("target","items[0].count");
                JsonNode result=http.execute(config,node("{\"id\":\"id with space\",\"search\":\"中文 &?\",\"count\":2}"),"synthetic-credential",timeout);
                assertThat(result.path("state").asText()).isEqualTo("ok");assertThat(result.path("secret").asText()).isEqualTo("[REDACTED]");
            }
            assertThat(methods).containsExactly("GET","POST","PUT","PATCH","DELETE");
        }
    }

    @Test void rejectsUnapprovedTargetsAndHeaderOverrideBeforeRequest()throws Exception {
        try(Server server=new Server(x->reply(x,200,Map.of("ok",true)))) {
            var config=server.config();((ObjectNode)config.path("target")).put("allowPrivate",false);
            assertThatThrownBy(()->http.execute(config,json.createObjectNode(),null,timeout)).hasMessageContaining("内网");
            ((ObjectNode)config.path("target")).put("allowPrivate",true).put("port",1);
            assertThatThrownBy(()->http.execute(config,json.createObjectNode(),null,timeout)).hasMessageContaining("精确授权");
            ((ObjectNode)config.path("target")).put("port",server.server.getAddress().getPort());
            config.putArray("mappings").addObject().put("sourceKind","LITERAL").put("literal","tamper").put("targetKind","HEADER").put("target","Authorization");
            assertThatThrownBy(()->http.execute(config,json.createObjectNode(),null,timeout)).hasMessageContaining("Header");
            assertThat(server.count.get()).isZero();
        }
        for(String address:List.of("169.254.169.254","100.100.100.200","0.0.0.0","224.0.0.1","::","fe80::1","2001:db8::1")) {
            assertThatThrownBy(()->TargetPolicy.checkAddress(InetAddress.getByName(address),true)).isInstanceOf(TransportException.class);
        }
        assertThatCode(()->TargetPolicy.checkAddress(InetAddress.getByName("::1"),true)).doesNotThrowAnyException();
        assertThatThrownBy(()->TargetPolicy.checkAddress(InetAddress.getByName("::1"),false)).isInstanceOf(TransportException.class);
        assertThatCode(()->TargetPolicy.checkAddress(InetAddress.getByName("8.8.8.8"),false)).doesNotThrowAnyException();
    }

    @Test void neverFollowsRedirectOrRetriesWritesAndDoesNotEchoErrorBody()throws Exception {
        try(Server target=new Server(x->reply(x,200,Map.of("ok",true)));Server redirect=new Server(x->{x.getResponseHeaders().set("Location",target.url());x.sendResponseHeaders(302,-1);})){ 
            var config=redirect.config().put("method","POST");config.putObject("auth").put("type","BEARER");
            assertThatThrownBy(()->http.execute(config,json.createObjectNode(),"secret-token",timeout)).hasMessageContaining("302").hasMessageNotContaining("secret-token");
            assertThat(redirect.count.get()).isEqualTo(1);assertThat(target.count.get()).isZero();
        }
        try(Server error=new Server(x->reply(x,401,Map.of("error","secret-token raw upstream error")))) {
            assertThatThrownBy(()->http.execute(error.config().put("method","POST"),json.createObjectNode(),"secret-token",timeout))
                    .hasMessageContaining("401").hasMessageNotContaining("secret-token").hasMessageNotContaining("raw upstream");
            assertThat(error.count.get()).isEqualTo(1);
        }
    }

    @Test void responseLimitsJsonNullMissingPathsAndAbsoluteDeadline()throws Exception {
        try(Server server=new Server(x->reply(x,200,Map.of("data",Collections.singletonMap("value",null))))) {
            var config=server.config().put("responsePath","data.value");
            assertThat(http.execute(config,json.createObjectNode(),null,timeout).isNull()).isTrue();
            config.put("responsePath","missing");assertThatThrownBy(()->http.execute(config,json.createObjectNode(),null,timeout)).hasMessageContaining("字段缺失");
            config.put("responsePath","$").put("responseMaxBytes",5);assertThatThrownBy(()->http.execute(config,json.createObjectNode(),null,timeout)).hasMessageContaining("大小限制");
        }
        try(Server invalid=new Server(x->{byte[] bytes="not JSON".getBytes();x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);})){
            assertThatThrownBy(()->http.execute(invalid.config(),json.createObjectNode(),null,timeout)).hasMessageContaining("有效 JSON");
        }
        try(Server slow=new Server(x->{Thread.sleep(2000);reply(x,200,Map.of("ok",true));})){
            long start=System.nanoTime();assertThatThrownBy(()->http.execute(slow.config().put("method","POST"),json.createObjectNode(),null,Duration.ofMillis(120))).hasMessageContaining("超时");
            assertThat(Duration.ofNanos(System.nanoTime()-start).toMillis()).isLessThan(1500);assertThat(slow.count.get()).isEqualTo(1);
        }
    }

    @Test void streamableMcpNegotiatesSessionPaginatesAndReadsSseBeforeStreamClose()throws Exception {
        List<String> seen=new CopyOnWriteArrayList<>();
        try(Server server=new Server(x->{
            if(x.getRequestMethod().equals("DELETE")){seen.add("DELETE");x.sendResponseHeaders(204,-1);return;}
            JsonNode request=json.readTree(x.getRequestBody());String method=request.path("method").asText();seen.add(method);
            assertThat(x.getRequestHeaders().getFirst("Accept")).contains("application/json","text/event-stream");
            assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer mcp-test-key");
            if(method.equals("initialize")) {
                x.getResponseHeaders().set("Mcp-Session-Id","session-fixture");
                reply(x,200,Map.of("jsonrpc","2.0","id",request.path("id").asText(),"result",Map.of("protocolVersion","2025-06-18","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"))));return;
            }
            assertThat(x.getRequestHeaders().getFirst("Mcp-Session-Id")).isEqualTo("session-fixture");
            assertThat(x.getRequestHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-06-18");
            if(method.equals("notifications/initialized")){x.sendResponseHeaders(202,-1);return;}
            Object result;
            if(method.equals("tools/list"))result=request.path("params").has("cursor")?Map.of("tools",List.of(Map.of("name","second","inputSchema",Map.of("type","object"))))
                    :Map.of("tools",List.of(Map.of("name","first","inputSchema",Map.of("type","object"))),"nextCursor","page-2");
            else {assertThat(method).isEqualTo("tools/call");assertThat(request.path("params").path("name").asText()).isEqualTo("first");result=Map.of("content",List.of(Map.of("type","text","text","MCP actual result")));}
            String body=json.writeValueAsString(Map.of("jsonrpc","2.0","id",request.path("id").asText(),"result",result));
            x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,0);
            x.getResponseBody().write(("event: message\ndata: "+body+"\n\n").getBytes(StandardCharsets.UTF_8));x.getResponseBody().flush();Thread.sleep(900);
        })) {
            var config=server.config().put("transport","STREAMABLE_HTTP");config.putObject("auth").put("type","BEARER");
            assertThat(mcp.discover(config,"mcp-test-key",timeout)).extracting(t->t.path("name").asText()).containsExactly("first","second");
            assertThat(mcp.call(config,"first",json.createObjectNode(),"mcp-test-key",timeout).toString()).contains("MCP actual result");
            assertThat(seen).containsExactly("initialize","notifications/initialized","tools/list","tools/list","DELETE","initialize","notifications/initialized","tools/call","DELETE");
        }
    }

    @Test void legacyBridgeIsExplicitAndMcpErrorsAreNotSuccess()throws Exception {
        try(Server bridge=new Server(x->{if(x.getRequestURI().getPath().equals("/tools"))reply(x,200,Map.of("tools",List.of(Map.of("name","search_docs"))));
            else {assertThat(x.getRequestURI().getPath()).isEqualTo("/tools/call");reply(x,200,Map.of("hits",List.of("legacy")));}})) {
            var config=bridge.config().put("transport","LEGACY_BRIDGE");
            assertThat(mcp.discover(config,null,timeout)).hasSize(1);
            assertThat(mcp.call(config,"search_docs",json.createObjectNode(),null,timeout).path("hits").get(0).asText()).isEqualTo("legacy");
        }
        try(Server bad=new Server(x->{JsonNode req=json.readTree(x.getRequestBody());reply(x,200,Map.of("jsonrpc","2.0","id",req.path("id").asText(),"error",Map.of("code",-32603,"message","do not echo secret-token")));})) {
            assertThatThrownBy(()->mcp.discover(bad.config(),null,timeout)).hasMessageContaining("-32603").hasMessageNotContaining("secret-token");
        }
    }

    @Test void bodyReadFailureIsNotMisreportedAsDeadlineCancellation()throws Exception {
        try(Server server=new Server(x->reply(x,200,Map.of("ok",true)))) {
            assertThatThrownBy(()->GuardedHttp.exchange(server.config(),"GET",server.url(),Map.of(),null,timeout,
                    (status,headers,input)->{throw new java.io.IOException("private connection failure");}))
                    .isInstanceOfSatisfying(TransportException.class,e->assertThat(e.code()).isEqualTo("NETWORK_ERROR"))
                    .hasMessageNotContaining("private connection failure");
        }
    }

    @Test void jsonPathRejectsExecutableAndConflictingMappings()throws Exception {
        JsonNode base=node("{\"items\":[{\"id\":3}]}");assertThat(JsonPaths.read(base,"$.items[0].id").asInt()).isEqualTo(3);
        for(String path:List.of("items[*]","items[1000]","items..id","items[0]id","items[0].","${exec()}","items.[0]")) {
            assertThatThrownBy(()->JsonPaths.read(base,path)).isInstanceOf(TransportException.class);
        }
        assertThatThrownBy(()->JsonPaths.write(base,"items[0].id",json.getNodeFactory().numberNode(4))).hasMessageContaining("重复");
    }
}
