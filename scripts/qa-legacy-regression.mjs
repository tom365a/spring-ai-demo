// Regression for the pre-resource demo scenarios running on the new resource runtime.
// Isolated backend + owned loopback model fixture only; never the 8080 user application or a real provider.
// Prerequisites:
//   scripts/qa-isolated-backend.ps1 -Fresh -ModelBaseUrl http://127.0.0.1:18091 -KnowledgeDir samples/knowledge
//   node scripts/resource-test-services.mjs 18091
//   node scripts/qa-authorize-fixture-model.mjs 18080 18091 model_kimi
import assert from 'node:assert/strict';
import fs from 'node:fs';
const base=process.env.QA_BASE_URL||'http://127.0.0.1:18080';
const report={at:new Date().toISOString(),base,checks:[],failures:[]};
async function api(path,method='GET',body,role='publisher'){
 const response=await fetch(base+'/api/v1'+path,{method,headers:{'Content-Type':'application/json','X-Admin-Role':role},body:body===undefined?undefined:JSON.stringify(body)});
 const value=await response.json();assert.equal(value.code,0,method+' '+path+': '+JSON.stringify(value));return value.data;
}
async function check(name,work){try{report.checks.push({name,passed:true,evidence:await work()});console.log('PASS '+name);}catch(e){report.failures.push({name,error:e.stack});console.log('FAIL '+name+': '+e.message);}}
const chat=(text,sessionId,extra={})=>api('/chat','POST',{text,userId:'u_001',sessionId,supervisorCode:'supervisor',...extra});

try {
 await check('legacy supervisor still routes to a seeded worker',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  const out=await chat('[route:knowledge] 国内订单退货时效是多久？',session.id);
  assert.equal(out.agentName,'knowledge',JSON.stringify(out));
  assert.equal(out.confirmRequired,false);
  return {sessionId:session.id,agentName:out.agentName,intent:out.intent,answer:out.answer.slice(0,120)};
 });

 await check('legacy knowledge retrieval still returns citations from sample documents',async()=>{
  const documents=await api('/knowledge/documents').catch(()=>null);
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  const out=await chat('[route:knowledge] 退货政策是什么？',session.id);
  assert.ok(out.citations.length>0,'expected citations, got '+JSON.stringify(out.citations));
  return {documents:documents?.items?.length??null,citations:out.citations.map(c=>c.title??c.source??c.docId)};
 });

 await check('legacy read tool query_order still executes without confirmation',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  const out=await chat('[route:order] [tool:query_order] [args:{"orderId":"ORD20260730001","userId":"u_001"}] 帮我查订单',session.id);
  const call=out.toolCalls.find(t=>t.name==='query_order');
  assert.ok(call,'query_order not called: '+JSON.stringify(out.toolCalls));
  assert.equal(call.state,'SUCCEEDED');assert.equal(out.confirmRequired,false);
  return {agentName:out.agentName,toolCalls:out.toolCalls,answer:out.answer.slice(0,200)};
 });

 await check('legacy write tool cancel_order still pauses for confirmation and then executes once',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  const pending=await chat('[route:order] [tool:cancel_order] [args:{"orderId":"ORD20260730001","userId":"u_001","reason":"回归测试"}] 取消订单',session.id);
  assert.equal(pending.confirmRequired,true,JSON.stringify(pending));
  assert.ok(!pending.toolCalls.some(t=>t.state==='SUCCEEDED'),'nothing may execute before confirmation');
  const done=await api('/confirmations/'+pending.confirmationPayload.id,'POST',{sessionId:pending.confirmationPayload.sessionId,confirm:true});
  const call=done.toolCalls.find(t=>t.name==='cancel_order');
  assert.ok(call&&call.state==='SUCCEEDED',JSON.stringify(done.toolCalls));
  let replayDenied=false;
  try{await api('/confirmations/'+pending.confirmationPayload.id,'POST',{sessionId:pending.confirmationPayload.sessionId,confirm:true});}catch{replayDenied=true;}
  assert.ok(replayDenied,'a spent confirmation must not execute a second time');
  return {pendingId:pending.confirmationPayload.id,toolCalls:done.toolCalls,replayDenied};
 });

 await check('legacy cancel choice leaves the write tool unexecuted',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  const pending=await chat('[route:order] [tool:cancel_order] [args:{"orderId":"ORD20260730001","userId":"u_001","reason":"回归取消"}] 取消订单',session.id);
  assert.equal(pending.confirmRequired,true);
  const out=await api('/confirmations/'+pending.confirmationPayload.id,'POST',{sessionId:pending.confirmationPayload.sessionId,confirm:false});
  assert.ok(!out.toolCalls.some(t=>t.state==='SUCCEEDED'),JSON.stringify(out.toolCalls));
  return {answer:out.answer,toolCalls:out.toolCalls};
 });

 await check('legacy ticket creation still works end to end',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  const pending=await chat('[route:ticket] [tool:create_ticket] [args:{"userId":"u_001","category":"物流","description":"回归测试包裹破损","priority":"P3"}] 包裹破损帮我开工单',session.id);
  assert.equal(pending.confirmRequired,true,JSON.stringify(pending));
  const done=await api('/confirmations/'+pending.confirmationPayload.id,'POST',{sessionId:pending.confirmationPayload.sessionId,confirm:true});
  const call=done.toolCalls.find(t=>t.name==='create_ticket');
  assert.ok(call&&call.state==='SUCCEEDED',JSON.stringify(done.toolCalls));
  return {toolCalls:done.toolCalls,answer:done.answer.slice(0,200)};
 });

 await check('legacy multi-turn history is still carried across turns in one session',async()=>{
  const session=await api('/sessions','POST',{userId:'u_001',channel:'web'});
  await chat('[route:knowledge] 国内订单退货时效是多久？',session.id);
  await chat('[route:knowledge] 海外呢？',session.id);
  const detail=await api('/sessions/'+session.id);
  const roles=detail.messages.map(m=>m.role);
  assert.ok(detail.messages.length>=4,'expected both turns persisted: '+JSON.stringify(roles));
  assert.ok(detail.messages.some(m=>m.role==='user'&&m.content.includes('海外')));
  return {sessionId:session.id,messageCount:detail.messages.length,roles};
 });

 await check('legacy debug config endpoint still reports the active runtime',async()=>{
  const config=await api('/debug/config');
  assert.ok(config,'no debug config');
  return config;
 });
}catch(error){report.failures.push({name:'setup or unhandled',error:error.stack});}
finally{
 report.passed=report.failures.length===0;
 fs.mkdirSync('docs/resource-configuration/evidence',{recursive:true});
 fs.writeFileSync('docs/resource-configuration/evidence/qa-legacy-regression.json',JSON.stringify(report,null,2));
 console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,failures:report.failures.map(f=>f.name)},null,2));
 if(!report.passed)process.exitCode=1;
}
