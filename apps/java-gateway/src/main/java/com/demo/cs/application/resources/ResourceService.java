package com.demo.cs.application.resources;

import com.demo.cs.domain.*;
import com.demo.cs.infrastructure.persistence.*;
import com.demo.cs.infrastructure.resources.transport.McpToolTransport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** One JVM lifecycle coordinator; snapshots are immutable and read from committed versions. */
@Service
public class ResourceService {
 private static final Logger log=LoggerFactory.getLogger(ResourceService.class);
 private final ManagedResourceRepository repo;
 private final ManagedResourceVersionRepository versions;
 private final ResourceSettingRepository settings;
 private final ResourceAuditRepository audits;
 private final AgtAgentRepository agents;
 private final AgtAgentVersionRepository agentVersions;
 private final CredentialVault credentials;
 private final ObjectMapper json;
 private final TransactionTemplate tx;
 private final McpToolTransport mcp;
 public ResourceService(ManagedResourceRepository repo,ManagedResourceVersionRepository versions,
 ResourceSettingRepository settings,ResourceAuditRepository audits,AgtAgentRepository agents,
 AgtAgentVersionRepository agentVersions,CredentialVault credentials,ObjectMapper json,
 PlatformTransactionManager manager,McpToolTransport mcp) {
 this.repo=repo;this.versions=versions;this.settings=settings;this.audits=audits;this.agents=agents;
 this.agentVersions=agentVersions;this.credentials=credentials;this.json=json;this.tx=new TransactionTemplate(manager);this.mcp=mcp;
 }
 public JsonNode parse(String value) {try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("资源配置无法读取");}}
 public ManagedResource require(String id) {ManagedResource r=repo.findById(id).orElseGet(()->repo.findByCode(id).orElseThrow(()->new IllegalArgumentException("资源不存在")));if(r.deleted)throw new IllegalStateException("资源已删除");return r;}
 public List<Map<String,Object>> list(String kind,String q,Boolean enabled,String source) {
 return repo.findByDeletedFalseOrderByKindAscNameAsc().stream().filter(r->kind==null||kind.equals(r.kind))
 .filter(r->enabled==null||enabled==r.enabled).filter(r->q==null||(r.name+" "+r.code).toLowerCase().contains(q.toLowerCase()))
 .filter(r->source==null||source.equals(parse(r.draftJson).path("config").path("source").asText())).map(this::item).toList();
 }
 private Map<String,Object> item(ManagedResource r) {
 Map<String,Object> v=new LinkedHashMap<>();v.put("id",r.id);v.put("code",r.code);v.put("kind",r.kind);v.put("name",r.name);v.put("description",r.description);
 v.put("enabled",r.enabled);v.put("publishedVersion",r.publishedVersion);v.put("draftRevision",r.draftRevision);v.put("dirty",dirty(r));
 v.put("isDefault",r.id.equals(defaultModelId()));v.put("config",parse(r.draftJson).path("config"));v.put("credential",credentials.status(r.credentialRef));
 v.put("connectionStatus",r.connectionStatus);v.put("lastTestRevision",r.lastTestRevision);return v;
 }
 public Map<String,Object> detail(String id) {
 ManagedResource r=require(id);Map<String,Object> out=item(r);out.put("draft",view(r,parse(r.draftJson)));
 out.put("published",r.publishedVersion==null?null:view(r,parse(version(r.id,r.publishedVersion).definitionJson)));
 out.put("status",r.enabled?"ENABLED":r.publishedVersion==null?"DRAFT":"DISABLED");out.put("references",references(r.id));return out;
 }
 private Map<String,Object> view(ManagedResource r,JsonNode d) {Map<String,Object> out=new LinkedHashMap<>();out.put("id",r.id);out.put("code",r.code);out.put("kind",r.kind);out.put("name",d.path("name").asText());out.put("description",d.path("description").asText());out.put("config",d.path("config"));return out;}
 private boolean dirty(ManagedResource r) {if(r.publishedVersion==null)return true;var v=version(r.id,r.publishedVersion);return !r.draftJson.equals(v.definitionJson)||!Objects.equals(r.credentialRef,v.credentialRef);}
 private ManagedResourceVersion version(String id,int version) {return versions.findByResourceIdAndVersionNumber(id,version).orElseThrow(()->new IllegalArgumentException("资源版本不存在"));}
 public synchronized Map<String,Object> save(String id,JsonNode body,boolean publisher) {
 String saved=tx.execute(status->{
 if(!body.isObject())throw new IllegalArgumentException("资源请求必须为对象");
 ManagedResource r=id==null?new ManagedResource():require(id);
 String kind=text(body,"kind");if(!Set.of("MODEL","TOOL","MCP","SKILL").contains(kind))throw new IllegalArgumentException("kind 不支持");
 if(id!=null&&!kind.equals(r.kind))throw new IllegalArgumentException("资源类型不可更改");
 String name=text(body,"name").trim();if(name.isBlank()||name.length()>128)throw new IllegalArgumentException("name 必填且最多128字符");
 String desc=body.path("description").asText("");if(desc.length()>1000)throw new IllegalArgumentException("description 最多1000字符");
 JsonNode config=body.path("config");validateConfig(kind,config,false);
 if(id!=null && (!body.path("draftRevision").isIntegralNumber()||body.path("draftRevision").asInt()!=r.draftRevision))throw new IllegalStateException("草稿已更新，请刷新后重试");
 JsonNode old=id==null?json.createObjectNode():parse(r.draftJson).path("config");
 if(kind.equals("TOOL")) {
   if(id==null&&!config.path("source").asText().equals("HTTP"))throw new IllegalArgumentException("只能新建HTTP工具；内置和MCP身份由系统登记");
   if(id!=null&&!old.path("source").equals(config.path("source")))throw new IllegalArgumentException("工具来源不可更改");
   for(String key:List.of("builtinCode","serviceId","nativeName"))if(id!=null&&!Objects.equals(old.get(key),config.get(key)))throw new IllegalArgumentException("工具执行身份不可更改");
   if(old.path("source").asText().equals("BUILTIN")) {
     if(!old.path("sideEffect").equals(config.path("sideEffect")))throw new IllegalArgumentException("内置工具读写风险不可降低或篡改");
     if(!old.path("inputSchema").equals(config.path("inputSchema")))throw new IllegalArgumentException("内置工具参数定义不可修改");
   }
   if(old.path("source").asText().equals("MCP")&&!old.path("inputSchema").equals(config.path("inputSchema")))throw new IllegalArgumentException("MCP参数定义只能通过目录同步更新");
 }
 if(kind.equals("MCP")&&old.has("tools")&&!config.has("tools"))throw new IllegalArgumentException("MCP目录不可从草稿移除，请通过同步更新");
 if(kind.equals("MCP")&&config.has("tools")) {
   if(!config.path("tools").isArray())throw new IllegalArgumentException("MCP tools必须为数组");
   Map<String,JsonNode> known=new HashMap<>();for(JsonNode t:old.path("tools"))known.put(t.path("name").asText(),t);
   if(config.path("tools").size()!=known.size())throw new IllegalArgumentException("MCP工具目录只能通过同步更新");
   Set<String> seen=new HashSet<>();for(JsonNode t:config.path("tools")) {if(!t.isObject())throw new IllegalArgumentException("MCP目录项必须为对象");String toolName=t.path("name").asText();JsonNode before=known.get(toolName);if(before==null||!seen.add(toolName))throw new IllegalArgumentException("MCP工具目录只能通过同步更新");
     ObjectNode a=(ObjectNode)t.deepCopy(),b=(ObjectNode)before.deepCopy();a.remove(List.of("sideEffect","requireConfirm"));b.remove(List.of("sideEffect","requireConfirm"));if(!a.equals(b))throw new IllegalArgumentException("MCP工具定义只能通过同步更新");
   }
 }
 if(!publisher && (!Objects.equals(old.get("target"),config.get("target")) || !Objects.equals(old.get("auth"),config.get("auth"))))throw new SecurityException("只有 publisher 可更改连接目标授权或认证方式");
 if(id==null) {r.id=UUID.randomUUID().toString();r.code=body.path("code").asText("").trim();if(r.code.isBlank())r.code=kind.toLowerCase()+"_"+UUID.randomUUID().toString().replace("-","").substring(0,16);if(!r.code.matches("[A-Za-z][A-Za-z0-9_-]{0,99}"))throw new IllegalArgumentException("code 格式不正确");r.kind=kind;}
 else if(body.hasNonNull("code")&&!body.get("code").asText().equals(r.code))throw new IllegalArgumentException("code 不可更改");
 r.credentialRef=credentials.change(r.credentialRef,body.get("credentialChange"),publisher);
 r.name=name;r.description=desc;r.draftJson=json.createObjectNode().put("name",name).put("description",desc).set("config",config.deepCopy()).toString();
 r.draftRevision++;r.updatedAt=Instant.now();r.connectionStatus="UNTESTED";r.lastTestRevision=null;repo.saveAndFlush(r);audit(r,"SAVE_DRAFT");return r.id;
 });return detail(saved);
 }
 private static String text(JsonNode n,String key) {if(!n.path(key).isTextual())throw new IllegalArgumentException(key+" 必须为字符串");return n.path(key).asText();}
 public void validateConfig(String kind,JsonNode c,boolean activating) {
 if(!c.isObject())throw new IllegalArgumentException("config 必须为对象");
 for(String forbidden:List.of("credentialRef","apiKey","password","secret","authorization","headers"))if(c.has(forbidden))throw new IllegalArgumentException("凭证只能通过专用凭证输入保存");
 int timeout=c.path("timeoutSeconds").asInt(30);if(c.has("timeoutSeconds")&&!c.get("timeoutSeconds").isIntegralNumber()||timeout<1||timeout>300)throw new IllegalArgumentException("timeoutSeconds 必须为1–300整数");
 for(String flag:List.of("requireConfirm","enableRag"))if(c.has(flag)&&!c.get(flag).isBoolean())throw new IllegalArgumentException(flag+" 必须为布尔值");
 if(c.path("target").has("allowPrivate")&&!c.path("target").get("allowPrivate").isBoolean())throw new IllegalArgumentException("target.allowPrivate 必须为布尔值");
 if(kind.equals("MODEL")) {
 if(!Set.of("OPENAI","KIMI").contains(text(c,"provider")))throw new IllegalArgumentException("模型供应商不支持");
 if(text(c,"model").isBlank())throw new IllegalArgumentException("model 必填");if(activating||c.has("target"))address(c,"baseUrl");
 JsonNode p=c.path("parameters");if(!p.isMissingNode()&&!p.isObject())throw new IllegalArgumentException("parameters 必须为对象");
 Set<String> allowed=c.path("provider").asText().equals("KIMI")?Set.of("reasoningEffort","maxCompletionTokens"):Set.of("temperature","maxTokens");
 p.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("模型参数不支持: "+k);});
 if(p.has("reasoningEffort")&&!Set.of("low","high","max").contains(p.get("reasoningEffort").asText()))throw new IllegalArgumentException("reasoningEffort 不支持");
 for(String k:List.of("maxTokens","maxCompletionTokens"))if(p.has(k)&&(!p.get(k).isIntegralNumber()||p.get(k).asInt()<1||p.get(k).asInt()>131072))throw new IllegalArgumentException(k+" 超出范围");
 if(p.has("temperature")&&(!p.get("temperature").isNumber()||p.get("temperature").asDouble()<0||p.get("temperature").asDouble()>2))throw new IllegalArgumentException("temperature 范围0–2");
 } else if(kind.equals("TOOL")) {
 String source=text(c,"source");if(!Set.of("BUILTIN","HTTP","MCP").contains(source))throw new IllegalArgumentException("工具来源不支持");
 if(!Set.of("READ","WRITE").contains(text(c,"sideEffect")))throw new IllegalArgumentException("sideEffect 必须为READ或WRITE");
 JsonSchemaGuard.definition(c.path("inputSchema"));
 if(source.equals("HTTP")) {if(activating||c.has("target"))address(c,"url");if(!Set.of("GET","POST","PUT","PATCH","DELETE").contains(text(c,"method")))throw new IllegalArgumentException("HTTP method 不支持");if(!c.path("mappings").isArray())throw new IllegalArgumentException("mappings 必须为数组");for(JsonNode mapping:c.path("mappings")){if(!Set.of("INPUT","LITERAL").contains(mapping.path("sourceKind").asText())||!Set.of("PATH","QUERY","HEADER","BODY").contains(mapping.path("targetKind").asText()))throw new IllegalArgumentException("映射类型不支持");if(c.path("method").asText().equals("GET")&&mapping.path("targetKind").asText().equals("BODY"))throw new IllegalArgumentException("GET 不支持请求体");}}
 if(source.equals("MCP")&&activating)published(c.path("serviceId").asText(),"MCP",null);
 } else if(kind.equals("MCP")) {if(!Set.of("STREAMABLE_HTTP","LEGACY_BRIDGE").contains(text(c,"transport")))throw new IllegalArgumentException("MCP transport 不支持");if(activating||c.has("target"))address(c,"url");}
 else {
 if(text(c,"instructions").isBlank()||c.path("instructions").asText().length()>20000)throw new IllegalArgumentException("技能instructions 必填且最多20000字符");
 if(!c.path("applicableTypes").isArray()||c.path("applicableTypes").isEmpty())throw new IllegalArgumentException("技能至少选择一个适用Agent类型");
 for(JsonNode type:c.path("applicableTypes"))if(!Set.of("SUPERVISOR","WORKER").contains(type.asText()))throw new IllegalArgumentException("技能适用类型不支持");
 if(!c.path("tools").isArray())throw new IllegalArgumentException("技能tools 必须为数组");
 for(JsonNode tool:c.path("tools")){if(!tool.isTextual()||tool.asText().isBlank())throw new IllegalArgumentException("技能工具身份不能为空");if(activating)published(tool.asText(),"TOOL",null);}
 }
 }
 private void address(JsonNode c,String key) {
 try {String raw=text(c,key);int pathStart=raw.indexOf('/',raw.indexOf("://")+3);if(raw.substring(0,pathStart<0?raw.length():pathStart).contains("{"))throw new IllegalArgumentException();URI u=URI.create(raw.replaceAll("\\{[A-Za-z][A-Za-z0-9_]*}","value"));String scheme=u.getScheme();int port=u.getPort()<0?("https".equals(scheme)?443:80):u.getPort();JsonNode t=c.path("target");
 if(!Set.of("http","https").contains(scheme)||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||!scheme.equals(t.path("scheme").asText())||!u.getHost().equalsIgnoreCase(t.path("host").asText())||port!=t.path("port").asInt())throw new IllegalArgumentException("连接地址与目标授权不匹配");
 }catch(IllegalArgumentException|NullPointerException e){throw new IllegalArgumentException(key+" 地址或精确目标授权不正确");}
 }
 public String defaultModelId() {return settings.findById("default-model").map(s->s.settingValue).orElse(null);}
 public ResourceSnapshot published(String id,String kind,Integer pinned) {
 if(id==null||id.isBlank())id=defaultModelId();if(id==null)throw new IllegalStateException("未配置默认模型");
 ManagedResource r=require(id);if(!r.kind.equals(kind))throw new IllegalArgumentException("资源类型不匹配: "+r.code);
 if(!r.enabled||r.publishedVersion==null)throw new IllegalStateException("资源未启用: "+r.code);
 ManagedResourceVersion v=version(r.id,pinned==null?r.publishedVersion:pinned);
 JsonNode d=parse(v.definitionJson);return new ResourceSnapshot(r.id,r.code,r.kind,d.path("name").asText(),v.versionNumber,d.path("config"),v.credentialRef,r.publishedVersion);
 }
 public void available(ResourceSnapshot s,boolean sameVersion) {ManagedResource r=require(s.id());if(s.version()<0){if(r.draftRevision!=-s.version())throw new IllegalStateException("测试草稿已变化: "+r.code);return;}if(!r.enabled||sameVersion&&!Objects.equals(r.publishedVersion,s.observedPublishedVersion()))throw new IllegalStateException("资源已停用或相关版本已变化: "+r.code);}
 public String credential(ResourceSnapshot s) {return credentials.resolve(s.credentialRef());}
 public Map<String,Object> impact(String id,String action) {
 return impact(id,action,null);
 }
 public Map<String,Object> impact(String id,String action,Integer rollbackVersion) {
 ManagedResource r=require(id);Map<String,Object> out=new LinkedHashMap<>();out.put("token",token(r,action));out.put("currentVersion",r.publishedVersion);out.put("targetVersion",Objects.requireNonNullElse(r.publishedVersion,0)+(dirty(r)?1:0));
 if(action.equals("rollback")){if(rollbackVersion==null)throw new IllegalArgumentException("回滚预览必须指定version");out.put("token",token(r,action+":"+rollbackVersion));out.put("targetVersion",Objects.requireNonNullElse(r.publishedVersion,0)+1);out.put("rollbackFromVersion",rollbackVersion);}
 out.put("changes",Map.of("before",r.publishedVersion==null?json.createObjectNode():parse(version(r.id,r.publishedVersion).definitionJson),"after",parse(r.draftJson),"credentialChanged",r.publishedVersion==null?r.credentialRef!=null:!Objects.equals(r.credentialRef,version(r.id,r.publishedVersion).credentialRef)));
 if(action.equals("rollback")){var selected=version(r.id,rollbackVersion);out.put("changes",Map.of("before",r.publishedVersion==null?json.createObjectNode():parse(version(r.id,r.publishedVersion).definitionJson),"after",parse(selected.definitionJson),"credentialChanged",!Objects.equals(r.credentialRef,selected.credentialRef)));}
 out.put("references",references(r.id));out.put("effectiveAt","新任务使用新配置；停用阻止后续调用；技能需引用者显式升级");return out;
 }
 private String token(ManagedResource r,String action) {return hash(r.id+":"+r.draftRevision+":"+r.publishedVersion+":"+r.enabled+":"+action+":"+defaultModelId()+":"+references(r.id));}
 private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("无法计算资源版本");}}
 public synchronized Map<String,Object> lifecycle(String id,String action,JsonNode body) {
 tx.executeWithoutResult(status->{ManagedResource r=require(id);
 if(!token(r,action.equals("rollback")?action+":"+body.path("version").asInt():action).equals(body.path("impactToken").asText()))throw new IllegalStateException("影响预览已变化，请重新查看并确认");
 if(action.equals("disable")) {r.enabled=false;}
 else if(action.equals("default")) {if(!r.kind.equals("MODEL")||!r.enabled)throw new IllegalStateException("默认模型必须是已启用模型");ResourceSetting setting=new ResourceSetting();setting.id="default-model";setting.settingValue=r.id;settings.save(setting);}
 else {
 if(action.equals("rollback")) {int target=body.path("version").asInt(0);var old=version(r.id,target);r.draftJson=old.definitionJson;r.credentialRef=old.credentialRef;r.name=parse(r.draftJson).path("name").asText();r.description=parse(r.draftJson).path("description").asText();r.draftRevision++;}
 validateConfig(r.kind,parse(r.draftJson).path("config"),true);
 if(r.kind.equals("MODEL")||!parse(r.draftJson).path("config").path("auth").path("type").asText("NONE").equals("NONE")) {String value=credentials.resolve(r.credentialRef);if(value==null||value.isBlank())throw new IllegalStateException("发布配置缺少有效凭证");}
 if(dirty(r)||action.equals("rollback"))publishInternal(r);r.enabled=true;
 if(r.kind.equals("MCP"))publishMcpTools(r);
 }
 r.updatedAt=Instant.now();repo.saveAndFlush(r);audit(r,action.toUpperCase());});return detail(id);
 }
 /**
  * 按代码目录对账一个已登记的内置工具。
  *
  * seed() 只在资源缺失时写入，所以目录改了之后老库永远停在旧元数据上——
  * 而管理接口又刻意禁止改内置工具的读写风险（防止有人把写工具偷偷降级绕过确认），
  * 于是没有任何途径能修正它。这里补上唯一合法的途径：以代码为准，在启动时对账。
  *
  * 只对账「代码拥有」的字段：sideEffect / inputSchema / choice / 名称 / 描述。
  * requireConfirm 和 timeoutSeconds 属于运维可调项，原样保留；
  * 只有当 sideEffect 真的变了，才把 requireConfirm 重算回新默认值
  * （否则工具已经不是写操作了，却还永远卡着一张确认卡片）。
  */
 public synchronized void reconcileBuiltinTool(String code,String name,String description,JsonNode desired) {
 tx.executeWithoutResult(s->{
  var found=repo.findByCode(code);if(found.isEmpty())return;
  ManagedResource r=found.get();if(r.deleted)return;
  ObjectNode draft=(ObjectNode)parse(r.draftJson);JsonNode current=draft.path("config");
  if(!current.path("source").asText().equals("BUILTIN"))return;
  if(!current.path("builtinCode").asText().equals(desired.path("builtinCode").asText()))return;

  List<String> changed=new ArrayList<>();
  ObjectNode next=current.deepCopy();
  for(String key:List.of("sideEffect","inputSchema","choice")) {
   JsonNode want=desired.get(key);JsonNode have=current.get(key);
   if(Objects.equals(want,have))continue;
   changed.add(key);
   if(want==null)next.remove(key);else next.set(key,want.deepCopy());
  }
  if(changed.contains("sideEffect"))
   next.put("requireConfirm",desired.path("requireConfirm").asBoolean(false));
  boolean nameChanged=!Objects.equals(r.name,name)||!Objects.equals(Objects.requireNonNullElse(r.description,""),Objects.requireNonNullElse(description,""));
  if(changed.isEmpty()&&!nameChanged)return;

  r.name=name;r.description=description;
  draft.put("name",name).put("description",description);draft.set("config",next);
  r.draftJson=draft.toString();r.draftRevision++;
  publishInternal(r);repo.save(r);
  audit(r,"RECONCILE_BUILTIN");
  log.info("内置工具 {} 已按代码目录对账：{}{}",code,changed,nameChanged?" +名称/描述":"");
 });
 }

 private void publishInternal(ManagedResource r) {ManagedResourceVersion v=new ManagedResourceVersion();v.id=UUID.randomUUID().toString();v.resourceId=r.id;v.versionNumber=Objects.requireNonNullElse(r.publishedVersion,0)+1;v.definitionJson=r.draftJson;v.credentialRef=r.credentialRef;versions.save(v);r.publishedVersion=v.versionNumber;}
 private void publishMcpTools(ManagedResource service) {
 Set<String> seen=new HashSet<>();
 for(JsonNode nativeTool:parse(service.draftJson).path("config").path("tools")) {
 String name=nativeTool.path("name").asText();if(name.isBlank()||!seen.add(name))throw new IllegalArgumentException("MCP目录工具名称缺失或重复");
 String code="mcp_"+hash(service.id+":"+name).substring(0,32);ManagedResource t=repo.findByCode(code).orElseGet(ManagedResource::new);
 if(t.id==null){t.id=UUID.randomUUID().toString();t.code=code;t.kind="TOOL";t.name=name;t.description=nativeTool.path("description").asText("");}
 ObjectNode c=json.createObjectNode().put("source","MCP").put("serviceId",service.id).put("nativeName",name).put("sideEffect","WRITE").put("requireConfirm",true).put("timeoutSeconds",30);
 if(t.draftJson!=null){JsonNode prior=parse(t.draftJson).path("config");c.put("sideEffect",prior.path("sideEffect").asText("WRITE"));c.put("requireConfirm",prior.path("requireConfirm").asBoolean(true));}
 if(nativeTool.has("sideEffect")){String risk=nativeTool.path("sideEffect").asText();if(!Set.of("READ","WRITE").contains(risk))throw new IllegalArgumentException("MCP工具风险分类不正确");c.put("sideEffect",risk);c.put("requireConfirm",risk.equals("WRITE")||nativeTool.path("requireConfirm").asBoolean(true));}
 c.set("inputSchema",nativeTool.path("inputSchema"));JsonSchemaGuard.definition(c.path("inputSchema"));
 String d=json.createObjectNode().put("name",t.name).put("description",t.description).set("config",c).toString();if(!d.equals(t.draftJson)){t.draftJson=d;t.draftRevision++;publishInternal(t);}t.enabled=true;repo.save(t);
 }
 for(ManagedResource t:repo.findByDeletedFalseOrderByKindAscNameAsc()) {JsonNode c=parse(t.draftJson).path("config");if(t.kind.equals("TOOL")&&c.path("serviceId").asText().equals(service.id)&&!seen.contains(c.path("nativeName").asText())){t.enabled=false;repo.save(t);}}
 }
 public synchronized Map<String,Object> sync(String id) {
 ManagedResource r=require(id);if(!r.kind.equals("MCP"))throw new IllegalArgumentException("仅MCP可同步目录");
 int revision=r.draftRevision;JsonNode c=parse(r.draftJson).path("config");List<JsonNode> found;
 try {found=mcp.discover(c,credentials.resolve(r.credentialRef),Duration.ofSeconds(c.path("timeoutSeconds").asInt(30)));}
 catch(RuntimeException e){tx.executeWithoutResult(s->{var current=require(id);current.connectionStatus="FAILED";current.lastTestRevision=revision;repo.save(current);});throw e;}
 tx.executeWithoutResult(s->{ManagedResource current=require(id);if(current.draftRevision!=revision)throw new IllegalStateException("同步期间草稿已变更");
 ObjectNode d=(ObjectNode)parse(current.draftJson);((ObjectNode)d.path("config")).set("tools",json.valueToTree(found));current.draftJson=d.toString();current.draftRevision++;current.connectionStatus="SUCCESS";current.lastTestRevision=current.draftRevision;repo.save(current);audit(current,"SYNC_CANDIDATE");});
 return detail(id);
 }
 public synchronized void delete(String id) {tx.executeWithoutResult(s->{ManagedResource r=require(id);if(!references(id).isEmpty()||r.id.equals(defaultModelId()))throw new IllegalStateException("资源仍被引用，无法删除");r.deleted=true;r.enabled=false;repo.save(r);audit(r,"DELETE");});}
 public List<Map<String,Object>> history(String id) {ManagedResource r=require(id);return versions.findByResourceIdOrderByVersionNumberDesc(r.id).stream().map(v->{Map<String,Object> m=new LinkedHashMap<>();m.put("version",v.versionNumber);m.put("createdAt",v.createdAt);m.put("definition",view(r,parse(v.definitionJson)));return m;}).toList();}
 public List<ResourceAudit> auditHistory(String id) {return audits.findByResourceIdOrderByCreatedAtDesc(require(id).id);}
 public synchronized void testResult(String id,int revision,String result) {tx.executeWithoutResult(s->{var r=require(id);if(r.draftRevision==revision){r.connectionStatus=result;r.lastTestRevision=revision;repo.save(r);audit(r,"TEST_"+result);}});}
 private void audit(ManagedResource r,String action) {
 ResourceAudit a=new ResourceAudit();a.id=UUID.randomUUID().toString();a.resourceId=r.id;a.action=action;a.revision=r.draftRevision;a.versionNumber=r.publishedVersion;
 a.actorContext="system";var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
 if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request){String role=request.getRequest().getHeader("X-Admin-Role");a.actorContext="demo-role:"+(Set.of("viewer","editor","publisher","admin").contains(Objects.requireNonNullElse(role,"").toLowerCase())?role.toLowerCase():"viewer");}
 int before=Objects.requireNonNullElse(r.publishedVersion,0);if(Set.of("PUBLISH","ENABLE","ROLLBACK").contains(action))before--;
 JsonNode previous=before>0?parse(version(r.id,before).definitionJson):json.createObjectNode();List<String> fields=new ArrayList<>();changedPaths(previous,parse(r.draftJson),"",fields);
 ObjectNode diff=json.createObjectNode().put("beforeVersion",Math.max(0,before)).put("afterVersion",Objects.requireNonNullElse(r.publishedVersion,0));diff.set("changedFields",json.valueToTree(fields));diff.put("credentialChanged",before>0?!Objects.equals(version(r.id,before).credentialRef,r.credentialRef):r.credentialRef!=null);a.differenceJson=diff.toString();audits.save(a);
 }
 private void changedPaths(JsonNode before,JsonNode after,String prefix,List<String> output) {
 if(before.equals(after))return;if(before.isObject()&&after.isObject()){Set<String> keys=new TreeSet<>();before.fieldNames().forEachRemaining(keys::add);after.fieldNames().forEachRemaining(keys::add);for(String key:keys)changedPaths(before.path(key),after.path(key),prefix.isBlank()?key:prefix+"."+key,output);}else output.add(prefix);
 }
 public List<Map<String,Object>> references(String id) {
 ManagedResource target=require(id);List<Map<String,Object>> refs=new ArrayList<>();
 for(var a:agents.findAll()) {if(agentRefers(parse(a.getDraftJson()),target))refs.add(Map.of("kind","AGENT","code",a.getCode(),"name",a.getName(),"state","DRAFT"));
 if(a.getPublishedVersion()!=null)agentVersions.findByAgentCodeAndVersion(a.getCode(),a.getPublishedVersion()).ifPresent(v->{if(agentRefers(parse(v.getSnapshotJson()),target))refs.add(Map.of("kind","AGENT","code",a.getCode(),"name",a.getName(),"state","PUBLISHED"));});}
 for(var r:repo.findByDeletedFalseOrderByKindAscNameAsc())if(!r.id.equals(target.id)) {
 if(refers(parse(r.draftJson),target))refs.add(Map.of("kind",r.kind,"code",r.code,"name",r.name,"state","DRAFT"));
 if(r.publishedVersion!=null&&refers(parse(version(r.id,r.publishedVersion).definitionJson),target))refs.add(Map.of("kind",r.kind,"code",r.code,"name",r.name,"state","PUBLISHED"));
 }
 return refs;
 }
 private boolean agentRefers(JsonNode def,ManagedResource target) {
 if(refers(def,target))return true;
 if(target.kind.equals("MODEL")&&target.id.equals(defaultModelId())&&def.path("modelConfig").path("resourceId").asText("").isBlank())return true;
 for(JsonNode code:def.path("skills")) {
 var skill=repo.findByCode(code.asText());if(skill.isEmpty())continue;
 int pin=def.path("skillVersions").path(code.asText()).asInt(1);
 var snapshot=versions.findByResourceIdAndVersionNumber(skill.get().id,pin);
 if(snapshot.isPresent()&&refers(parse(snapshot.get().definitionJson),target))return true;
 if(snapshot.isPresent()&&toolServicesRefer(parse(snapshot.get().definitionJson).path("config").path("tools"),target))return true;
 }
 return toolServicesRefer(def.path("tools"),target)||toolServicesRefer(def.path("mcp").path("toolAllowlist"),target);
 }
 private boolean toolServicesRefer(JsonNode codes,ManagedResource target) {
 if(!target.kind.equals("MCP"))return false;
 for(JsonNode code:codes){var tool=repo.findByCode(code.asText());if(tool.isPresent()&&refers(parse(tool.get().draftJson),target))return true;}
 return false;
 }
 private boolean refers(JsonNode root,ManagedResource target) {
 if(root.isObject()) {var it=root.fields();while(it.hasNext()){var e=it.next();String k=e.getKey();JsonNode v=e.getValue();
 if(Set.of("resourceId","serviceId").contains(k)&&v.asText().equals(target.id))return true;
 if(Set.of("tools","skills").contains(k)&&v.isArray())for(JsonNode item:v)if(item.asText().equals(target.code))return true;
 if(refers(v,target))return true;}}
 else if(root.isArray())for(JsonNode v:root)if(refers(v,target))return true;
 return false;
 }
 /** Startup migration is additive and idempotent; never copies environment secret values. */
 public synchronized void seed(String code,String kind,String name,String description,JsonNode config,String envName,boolean enabled,boolean makeDefault) {
 tx.executeWithoutResult(s->{if(repo.findByCode(code).isPresent())return;
 ManagedResource r=new ManagedResource();r.id=UUID.randomUUID().toString();r.code=code;r.kind=kind;r.name=name;r.description=description;r.draftRevision=1;
 r.draftJson=json.createObjectNode().put("name",name).put("description",description).set("config",config).toString();
 if(envName!=null)r.credentialRef=credentials.referenceEnvironment(envName);
 publishInternal(r);r.enabled=enabled;repo.save(r);audit(r,"MIGRATE_V1");
 if(makeDefault&&defaultModelId()==null){ResourceSetting d=new ResourceSetting();d.id="default-model";d.settingValue=r.id;settings.save(d);}
 });
 }
}

