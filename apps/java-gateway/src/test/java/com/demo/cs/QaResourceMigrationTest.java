package com.demo.cs;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Two real child JVM starts over synthetic pre-resource session tables; no user databases or .env. */
class QaResourceMigrationTest {
 @TempDir Path folder;
 final ObjectMapper json=new ObjectMapper();
 @Test void legacyPublicSessionMessagesSurviveUpgradeAndActualJvmRestart()throws Exception {
  String database="jdbc:h2:file:"+folder.resolve("legacy").toAbsolutePath().toString().replace('\\','/')+";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE";
  try(var c=DriverManager.getConnection(database,"sa","");var s=c.createStatement()){
   s.execute("CREATE TABLE cs_session(id varchar(64) primary key,user_id varchar(64) not null,channel varchar(32),status varchar(32),summary text,last_intent varchar(64),last_agent varchar(64),confirmation_payload text,created_at timestamp with time zone not null,updated_at timestamp with time zone not null)");
   s.execute("CREATE TABLE cs_message(id varchar(64) primary key,session_id varchar(64) not null,role varchar(32) not null,content text not null,agent_name varchar(64),citations_json text,tool_calls_json text,attachments_json text,created_at timestamp with time zone not null)");
   s.execute("INSERT INTO cs_session VALUES('qa_old_session','u_001','web','active','OLD_PUBLIC_SUMMARY','old_intent','chitchat',null,TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00',TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:01+00')");
   s.execute("INSERT INTO cs_message VALUES('qa_old_user','qa_old_session','user','OLD_PUBLIC_QUESTION',null,null,null,'[]',TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00')");
   s.execute("INSERT INTO cs_message VALUES('qa_old_reply','qa_old_session','assistant','OLD_PUBLIC_REPLY','chitchat','[]','[]','[]',TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:01+00')");
  }
  List<List<String>> original=snapshot(database);List<String> firstResources=null;
  // New inactivity requirement closes this months-old session at its original start + five minutes.
  // All other original columns and every public message must remain byte-for-byte unchanged.
  original.getFirst().set(3,"closed");original.getFirst().set(9,"2026-01-01 00:05:00+00");
  for(int run=1;run<=2;run++) {
   int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
   List<String> resources=List.of();Path log=folder.resolve("boot-"+run+".log");var args=new ArrayList<String>();args.add(Path.of(System.getProperty("java.home"),"bin","java.exe").toString());Path temporary=Files.createDirectories(folder.resolve("jvm-tmp-"+run));args.add("-Djava.io.tmpdir="+temporary);args.add("-cp");args.add(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));args.add("com.demo.cs.CustomerServiceApplication");
   args.addAll(List.of("--server.port="+port,"--spring.profiles.active=local,openai","--spring.datasource.url="+database,"--spring.datasource.username=sa","--spring.datasource.password=","--OPENAI_API_KEY=qa-migration-key","--KIMI_API_KEY=qa-migration-kimi-key","--OPENAI_BASE_URL=http://127.0.0.1:1","--KIMI_BASE_URL=http://127.0.0.1:1","--app.upload-dir="+folder.resolve("uploads"),"--app.knowledge-sample-dir="+folder.resolve("no-documents"),"--app.vector.backend=lexical","--spring.ai.model.embedding=none","--APP_RESOURCE_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","--management.endpoint.health.probes.enabled=true","--management.endpoint.shutdown.access=unrestricted","--management.endpoints.web.exposure.include=health,shutdown"));
   var builder=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().clear();builder.environment().put("TEMP",temporary.toString());builder.environment().put("TMP",temporary.toString());builder.environment().put("SystemRoot",System.getenv("SystemRoot")==null?"C:\\Windows":System.getenv("SystemRoot"));Process process=builder.start();
   try {
    // readiness turns UP only after ApplicationReadyEvent, i.e. after seeding runners finish; plain health would race them
    var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();boolean ready=false;
    while(System.nanoTime()<deadline&&process.isAlive()) {try{var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200&&json.readTree(response.body()).path("status").asText().equals("UP")){ready=true;break;}}catch(Exception ignored){}Thread.sleep(150);}
    assertThat(ready).as("child JVM startup: "+Files.readString(log)).isTrue();
    JsonNode session=get(client,port,"/api/v1/sessions/qa_old_session").path("data");assertThat(session.path("summary").asText()).isEqualTo("OLD_PUBLIC_SUMMARY");assertThat(session.path("messages")).hasSize(2);assertThat(session.path("messages").get(0).path("content").asText()).isEqualTo("OLD_PUBLIC_QUESTION");assertThat(session.path("messages").get(1).path("content").asText()).isEqualTo("OLD_PUBLIC_REPLY");
    List<String> listed=new ArrayList<>();get(client,port,"/api/v1/admin/resources").path("data").path("items").forEach(n->listed.add(n.path("id").asText()+":"+n.path("code").asText()+":"+n.path("publishedVersion").asText()));Collections.sort(listed);resources=listed;assertThat(resources).isNotEmpty();if(run==1)firstResources=resources;else assertThat(resources).isEqualTo(firstResources);
   } finally {shutdown(port);if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}}
   assertThat(process.exitValue()).as("child JVM "+run+" clean exit: "+Files.readString(log)).isZero();
   assertThat(snapshot(database)).as("old columns after child JVM "+run).isEqualTo(original);
   List<String> persisted=persistedResources(database);assertThat(persisted).as("resources persisted by child JVM "+run).isEqualTo(resources);
  }
 }
 void shutdown(int port)throws Exception {try{HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/shutdown")).timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}catch(Exception ignored){}}
 List<String> persistedResources(String database)throws Exception {List<String> rows=new ArrayList<>();try(var c=DriverManager.getConnection(database,"sa","");var s=c.createStatement();var r=s.executeQuery("SELECT id,code,published_version FROM cfg_resource WHERE deleted=false ORDER BY code")){while(r.next())rows.add(r.getString(1)+":"+r.getString(2)+":"+r.getString(3));}Collections.sort(rows);return rows;}
 JsonNode get(HttpClient c,int port,String path)throws Exception {var r=c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Admin-Role","viewer").timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(200);return json.readTree(r.body());}
 List<List<String>> snapshot(String database)throws Exception {List<List<String>> rows=new ArrayList<>();try(var c=DriverManager.getConnection(database,"sa","");var s=c.createStatement()){
  for(String query:List.of("SELECT id,user_id,channel,status,summary,last_intent,last_agent,confirmation_payload,created_at,updated_at FROM cs_session ORDER BY id","SELECT id,session_id,role,content,agent_name,citations_json,tool_calls_json,attachments_json,created_at FROM cs_message ORDER BY id"))try(var r=s.executeQuery(query)){while(r.next()){List<String> values=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)values.add(r.getString(i));rows.add(values);}}
 }return rows;}
}
