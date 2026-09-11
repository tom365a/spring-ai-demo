// Independent QA: isolated backend 18080, own ephemeral loopback fixture, synthetic keys only.
import http from 'node:http';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const base='http://127.0.0.1:18080/api/v1',prefix='q_'+Date.now().toString(36);
const report={at:new Date().toISOString(),prefix,checks:[],failures:[]},packets=[],effects=[];
let seq=0,workerCode,fixturePort;
const server=http.createServer(async(req,res)=>{
 const send=(data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(data));};
 let raw='';for await(const c of req)raw+=c;let body=raw?JSON.parse(raw):{};
 if(req.url==='/v1/chat/completions'){
  packets.push(structuredClone(body));assert.equal(req.headers.authorization,'Bearer qa-offline-resource-key');
  const messages=body.messages||[],system=messages.filter(m=>m.role==='system').map(m=>m.content).join('\n');
  const last=messages.findLastIndex(m=>m.role==='user'),text=typeof messages[last]?.content==='string'?messages[last].content:JSON.stringify(messages[last]?.content);
  const command=text?.match(/QA_COMMAND (\{.*\})/)?.[1];const spec=command?JSON.parse(command):{};
  const results=messages.slice(last+1).filter(m=>m.role==='tool');let message;
  if(system.includes('QA_MAIN'))message={role:'assistant',content:JSON.stringify({targetAgent:workerCode,confidence:1,intent:'qa',needClarify:false})};
  else if(spec.tool&&(!results.length||spec.repeat))message={role:'assistant',content:null,reasoning_content:'QA_PRIVATE_REASONING',tool_calls:Array.from({length:spec.batch||1},(_,i)=>({id:'qa_call_'+(++seq)+'_'+i,type:'function',function:{name:spec.tool,arguments:JSON.stringify(spec.args||{})}}))};
  else message={role:'assistant',content:'QA_DONE '+(results.length?results.map(x=>x.content).join(' '):text)};
  return send({id:'qa_response_'+(++seq),model:body.model,choices:[{message,finish_reason:message.tool_calls?'tool_calls':'stop'}],usage:{prompt_tokens:3,completion_tokens:2}});
 }
 if(req.url==='/http/write'||req.url==='/http/read'){effects.push({source:'HTTP',method:req.method,body});return send({ok:true,receipt:'QA_RECEIPT',body});}
 if(req.url==='/mcp'){
  if(req.method==='DELETE'){res.writeHead(204);return res.end();}
  const rpc=result=>send({jsonrpc:'2.0',id:body.id,result});
  if(body.method==='initialize'){res.setHeader('Mcp-Session-Id','qa-session');return rpc({protocolVersion:'2025-11-25',capabilities:{tools:{}}});}
  assert.equal(req.headers['mcp-session-id'],'qa-session');
  if(body.method==='notifications/initialized'){res.writeHead(202);return res.end();}
  if(body.method==='tools/list')return rpc({tools:[{name:'qa_write',description:'Owned synthetic write',inputSchema:{type:'object',properties:{label:{type:'string'}},required:['label'],additionalProperties:false}}]});
  if(body.method==='tools/call'){effects.push({source:'MCP',body:body.params.arguments});return rpc({content:[{type:'text',text:'QA_MCP_RECEIPT '+JSON.stringify(body.params.arguments)}]});}
 }
 send({error:'fixture unknown path'},404);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));fixturePort=server.address().port;
const target={scheme:'http',host:'127.0.0.1',port:fixturePort,allowPrivate:true},origin='http://127.0.0.1:'+fixturePort;
async function rawApi(path,method='GET',body,role='publisher') {const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-Admin-Role':role},body:body===undefined?undefined:JSON.stringify(body)});const v=await r.json();return {status:r.status,...v};}
async function api(...args){const r=await rawApi(...args);assert.equal(r.code,0,JSON.stringify(r));assert.ok(r.status<300,JSON.stringify(r));return r.data;}
async function check(name,work){try{const evidence=await work();report.checks.push({name,passed:true,evidence});console.log('PASS '+name);}catch(e){report.failures.push({name,error:e.stack});console.log('FAIL '+name+': '+e.message);}}
async function life(id,action='publish'){const i=await api('/admin/resources/'+id+'/impact?action='+action);return api('/admin/resources/'+id+'/'+action,'POST',{impactToken:i.token});}
async function resource(kind,suffix,config,credential=false){const body={kind,code:prefix+'_'+suffix,name:prefix+' '+suffix,config};if(credential)body.credentialChange={action:'REPLACE',source:'INPUT',value:'qa-offline-resource-key'};const r=await api('/admin/resources','POST',body);await life(r.id);return r;}
async function saveAgent(def){await api('/admin/agents','POST',{code:def.code,name:def.name,type:def.type,definition:def});await api('/admin/agents/'+def.code+'/publish','POST',{});return def;}
async function editAgent(code,mutate){const a=await api('/admin/agents/'+code);mutate(a.draft);await api('/admin/agents/'+code,'PUT',{name:a.draft.name,description:a.draft.description,draftRevision:a.draftRevision,definition:a.draft});return api('/admin/agents/'+code+'/publish','POST',{});}
const command=(tool,args={},extra={})=>'QA_COMMAND '+JSON.stringify({tool,args,...extra});
async function confirm(p,yes=true){return api('/confirmations/'+p.confirmationPayload.id,'POST',{sessionId:p.confirmationPayload.sessionId,confirm:yes});}
async function trial(text){return api('/admin/agents/'+workerCode+'/trial','POST',{text,useDraft:false,mode:'single'});}
async function chat(text,sessionId){return api('/chat','POST',{text,userId:'u_001',supervisorCode:prefix+'_main',sessionId});}
function onlySuccess(out,source){assert.equal(out.confirmRequired,false);assert.ok(out.toolCalls.some(t=>t.source===source&&t.state==='SUCCEEDED'),JSON.stringify(out));assert.ok(!out.toolCalls.some(t=>t.state==='PENDING'));}
try {
 const model=await resource('MODEL','model',{provider:'KIMI',baseUrl:origin,model:'qa-kimi-model',target,capabilities:{vision:true}},true);
 const schema={type:'object',properties:{label:{type:'string'}},required:['label'],additionalProperties:false};
 const httpConfig=write=>({source:'HTTP',sideEffect:write?'WRITE':'READ',requireConfirm:false,method:write?'POST':'GET',url:origin+'/http/'+(write?'write':'read'),target,auth:{type:'NONE'},inputSchema:schema,mappings:write?[{sourceKind:'INPUT',source:'$',targetKind:'BODY',target:'$'}]:[],timeoutSeconds:5});
 const write=await resource('TOOL','write',httpConfig(true)),read=await resource('TOOL','read',httpConfig(false));
 const mcp=await resource('MCP','mcp',{transport:'STREAMABLE_HTTP',url:origin+'/mcp',target,auth:{type:'NONE'},timeoutSeconds:5});await api('/admin/resources/'+mcp.id+'/sync','POST',{});await life(mcp.id);
 const mcpTool=(await api('/admin/resources?kind=TOOL&source=MCP')).items.find(t=>t.config.serviceId===mcp.id);
 const builtin=(await api('/admin/resources?kind=TOOL&source=BUILTIN')).items.find(t=>t.code==='create_ticket');
 const worker=structuredClone((await api('/admin/agents/chitchat')).draft);workerCode=prefix+'_worker';Object.assign(worker,{code:workerCode,name:workerCode,tools:[write.code,read.code,mcpTool.code,builtin.code],skills:[],skillVersions:{}});worker.modelConfig.resourceId=model.id;worker.prompts={systemPrompt:'QA_WORKER',userPromptTemplate:'{{text}}',outputMode:'TEXT'};worker.policies={allowWriteTools:true,requireConfirmFor:[],maxToolRounds:3,toolTimeoutSeconds:5,taskTimeoutSeconds:30};worker.memory={enabled:true,windowSize:5,injectSummary:false};await saveAgent(worker);
 const main=structuredClone((await api('/admin/agents/supervisor')).draft);Object.assign(main,{code:prefix+'_main',name:prefix+'_main',tools:[],skills:[],skillVersions:{},children:[{agentCode:workerCode,enabled:true}]});main.modelConfig.resourceId=model.id;main.prompts={systemPrompt:'QA_MAIN',userPromptTemplate:'{{text}}',outputMode:'TEXT'};main.memory={enabled:true,windowSize:5,injectSummary:false};await saveAgent(main);
 const types=[{resource:write,source:'HTTP',args:{label:'nine-cell-http'}},{resource:mcpTool,source:'MCP',args:{label:'nine-cell-mcp'}},{resource:builtin,source:'BUILTIN',args:{userId:'u_001',category:'其他',description:prefix+' synthetic QA ticket',priority:'P3'}}];
 for(const type of types)for(const entry of ['tool-test','trial','chat'])await check('confirmation '+type.source+' x '+entry,async()=>{
  const before=effects.length;const text=command(type.resource.code,type.args);
  const pending=entry==='tool-test'?await api('/admin/resources/'+type.resource.id+'/test','POST',{arguments:type.args}):entry==='trial'?await trial(text):await chat(text);
  assert.equal(pending.confirmRequired,true);assert.equal(effects.length,before);const done=await confirm(pending);onlySuccess(done,type.source);
  const duplicate=await rawApi('/confirmations/'+pending.confirmationPayload.id,'POST',{sessionId:pending.confirmationPayload.sessionId,confirm:true});assert.ok(duplicate.status>=400);
  if(type.source!=='BUILTIN')assert.equal(effects.length,before+1);return {pendingId:pending.confirmationPayload.id,trace:done.toolCalls,diagnostics:done.diagnostics};
 });
 await check('concurrent confirmations execute HTTP write exactly once',async()=>{
  const p=await trial(command(write.code,{label:'concurrent'})),before=effects.length;const results=await Promise.all(Array.from({length:6},()=>rawApi('/confirmations/'+p.confirmationPayload.id,'POST',{sessionId:p.confirmationPayload.sessionId,confirm:true})));
  assert.equal(results.filter(r=>r.code===0).length,1);assert.equal(effects.length,before+1);return results.map(r=>r.status);
 });
 await check('cancel and wrong-session confirmation have zero effects',async()=>{const p=await trial(command(write.code,{label:'cancel'})),before=effects.length;const wrong=await rawApi('/confirmations/'+p.confirmationPayload.id,'POST',{sessionId:'not-the-session',confirm:true});assert.ok(wrong.status>=400);await confirm(p,false);assert.equal(effects.length,before);return {wrongStatus:wrong.status};});
 await check('new chat invalidates old pending action',async()=>{const p=await chat(command(write.code,{label:'old'}));await chat('NEW_PUBLIC_MESSAGE',p.sessionId);const before=effects.length;const r=await rawApi('/confirmations/'+p.confirmationPayload.id,'POST',{sessionId:p.sessionId,confirm:true});assert.ok(r.status>=400);assert.equal(effects.length,before);return {status:r.status};});
 await check('completed confirmed turn enters public history without private tool reasoning',async()=>{const p=await chat(command(write.code,{label:'history-proof'}));await confirm(p);const from=packets.length;await chat('NEXT_PUBLIC_MESSAGE',p.sessionId);const observed=packets.slice(from).find(b=>b.messages.some(m=>m.role==='system'&&m.content.includes('QA_WORKER')));assert.ok(observed);assert.ok(observed.messages.some(m=>m.role==='assistant'&&m.content.includes('QA_DONE')));assert.ok(!observed.messages.some(m=>m.role==='tool'));assert.ok(!JSON.stringify(observed).includes('QA_PRIVATE_REASONING'));assert.equal(observed.messages.filter(m=>m.role==='user'&&m.content==='NEXT_PUBLIC_MESSAGE').length,1);return observed;});
 await check('zero tool rounds denies whole batch',async()=>{await editAgent(workerCode,d=>d.policies.maxToolRounds=0);const before=effects.length;const out=await trial(command(read.code,{label:'zero'}));assert.equal(effects.length,before);assert.match(out.answer,/轮数/);await editAgent(workerCode,d=>d.policies.maxToolRounds=3);return out;});
 await check('eleven calls reject entire batch before execution',async()=>{const before=effects.length;const out=await trial(command(read.code,{label:'eleven'},{batch:11}));assert.equal(effects.length,before);assert.match(out.answer,/10/);return out;});
 await check('two calls in one batch consume one round',async()=>{await editAgent(workerCode,d=>d.policies.maxToolRounds=1);const before=effects.length;const out=await trial(command(read.code,{label:'batch-two'},{batch:2}));assert.equal(effects.length,before+2);assert.equal(out.diagnostics.agents[workerCode].toolRounds,1);await editAgent(workerCode,d=>d.policies.maxToolRounds=3);return out;});
 await check('resource publish invalidates pending confirmation',async()=>{const p=await trial(command(write.code,{label:'version'})),before=effects.length;const r=await api('/admin/resources/'+write.id);await api('/admin/resources/'+write.id,'PUT',{kind:'TOOL',code:r.code,name:r.name+' v2',draftRevision:r.draftRevision,config:r.config});await life(write.id);const out=await confirm(p);assert.match(out.answer,/失效/);assert.equal(effects.length,before);return out;});
 await check('version-invalidated confirmation retains exhausted round budget in same session',async()=>{
  await editAgent(workerCode,d=>d.policies.maxToolRounds=1);const p=await chat(command(write.code,{label:'inherited-budget'}));const r=await api('/admin/resources/'+write.id);await api('/admin/resources/'+write.id,'PUT',{kind:'TOOL',code:r.code,name:r.name+' next',draftRevision:r.draftRevision,config:r.config});await life(write.id);await confirm(p);
  const before=effects.length;const out=await chat(command(read.code,{label:'no-budget-refund'}),p.sessionId);assert.match(out.answer,/轮数/);assert.equal(effects.length,before);await editAgent(workerCode,d=>d.policies.maxToolRounds=3);return out;
 });
 await check('zero history window and independent summary switch reach model',async()=>{
  const first=await chat('QA_HISTORY_FIRST');await editAgent(workerCode,d=>{d.memory.windowSize=0;d.memory.injectSummary=false;});let from=packets.length;await chat('QA_HISTORY_SECOND',first.sessionId);let observed=packets.slice(from).find(b=>b.messages.some(m=>m.role==='system'&&m.content.includes('QA_WORKER')));assert.equal(observed.messages.length,2);assert.ok(!JSON.stringify(observed).includes('QA_HISTORY_FIRST'));
  await editAgent(workerCode,d=>d.memory.injectSummary=true);from=packets.length;await chat('QA_HISTORY_THIRD',first.sessionId);observed=packets.slice(from).find(b=>b.messages.some(m=>m.role==='system'&&m.content.includes('QA_WORKER')));assert.equal(observed.messages.length,2);assert.ok(observed.messages[0].content.includes('QA_HISTORY_SECOND'));await editAgent(workerCode,d=>{d.memory.windowSize=5;d.memory.injectSummary=false;});return observed;
 });
 await check('uploaded image reaches configured vision model and cross-session binding is rejected',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'qa'});const form=new FormData();form.set('file',new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aAfoAAAAASUVORK5CYII=','base64')],{type:'image/png'}),'qa-pixel.png');form.set('sessionId',session.id);
  const uploaded=await (await fetch(base+'/attachments',{method:'POST',body:form})).json();assert.equal(uploaded.code,0);const id=uploaded.data.id,from=packets.length;
  await api('/chat','POST',{sessionId:session.id,text:'QA_IMAGE',userId:'u_001',supervisorCode:main.code,attachmentIds:[id]});const bodies=packets.slice(from);assert.equal(bodies.length,2);for(const b of bodies){const content=b.messages.findLast(m=>m.role==='user').content;assert.ok(Array.isArray(content));assert.ok(content.some(p=>p.type==='image_url'&&p.image_url.url.startsWith('data:image/png;base64,')));}
  const denied=await rawApi('/chat','POST',{text:'QA_WRONG_IMAGE_SESSION',userId:'u_001',supervisorCode:main.code,attachmentIds:[id]});assert.equal(denied.status,403);return {attachment:id,modelRequests:bodies.length,wrongSession:denied.status};
 });
 await check('skill version only upgrades after explicit Agent publication',async()=>{
  const extra=await resource('TOOL','skill_extra',httpConfig(false));const skill=await resource('SKILL','skill',{instructions:'QA_SKILL_VERSION_ONE',applicableTypes:['WORKER'],tools:[],enableRag:false});await editAgent(workerCode,d=>{d.skills=[skill.code];d.skillVersions={[skill.code]:1};});
  const s=await api('/admin/resources/'+skill.id);await api('/admin/resources/'+skill.id,'PUT',{kind:'SKILL',code:s.code,name:s.name,draftRevision:s.draftRevision,config:{...s.config,instructions:'QA_SKILL_VERSION_TWO',tools:[extra.code]}});await life(skill.id);
  let from=packets.length;await trial('QA_PINNED_SKILL');let packet=packets[from];assert.ok(packet.messages[0].content.includes('QA_SKILL_VERSION_ONE'));assert.ok(!packet.messages[0].content.includes('QA_SKILL_VERSION_TWO'));assert.ok(!packet.tools.some(t=>t.function.name===extra.code));
  await editAgent(workerCode,d=>d.skillVersions[skill.code]=2);from=packets.length;await trial('QA_UPGRADED_SKILL');packet=packets[from];assert.ok(packet.messages[0].content.includes('QA_SKILL_VERSION_TWO'));assert.ok(packet.tools.some(t=>t.function.name===extra.code));return {skill:skill.id,versions:[1,2],addedTool:extra.code};
 });
 await check('viewer cannot save and editor cannot change credentials or target',async()=>{
  const r=await api('/admin/resources/'+model.id),b={kind:'MODEL',code:r.code,name:r.name,draftRevision:r.draftRevision,config:r.config};
  const viewer=await rawApi('/admin/resources/'+model.id,'PUT',b,'viewer');const key=await rawApi('/admin/resources/'+model.id,'PUT',{...b,credentialChange:{action:'REPLACE',source:'INPUT',value:'qa-editor-forged'}},'editor');const changed=structuredClone(b);changed.config.target.port++;const targetResponse=await rawApi('/admin/resources/'+model.id,'PUT',changed,'editor');assert.equal(viewer.status,403);assert.equal(key.status,403);assert.equal(targetResponse.status,400);return {viewer:viewer.status,key:key.status,target:targetResponse.status};
 });
}catch(error){report.failures.push({name:'setup or unhandled',error:error.stack});}
finally {
 report.passed=report.failures.length===0;report.fixtureRequests={models:packets.length,effects:effects.length};report.fixturePort=fixturePort;
 fs.mkdirSync('docs/resource-configuration/evidence',{recursive:true});fs.writeFileSync('docs/resource-configuration/evidence/qa-resource-http.json',JSON.stringify(report,null,2));
 server.closeAllConnections();await new Promise(resolve=>server.close(resolve));console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,failures:report.failures},null,2));if(!report.passed)process.exitCode=1;
}
