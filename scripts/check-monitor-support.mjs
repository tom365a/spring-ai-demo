import assert from 'node:assert/strict';
import fs from 'node:fs';
const base='http://localhost:18083/api/v1';
async function api(path,method='GET',body,role='publisher'){const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-Admin-Role':role},body:body===undefined?undefined:JSON.stringify(body)});const value=await response.json();if(!response.ok||value.code!==0)throw Error(path+': '+JSON.stringify(value));return value.data;}
const evidence={at:new Date().toISOString(),checks:[]};
try{
 const models=(await api('/admin/resources?kind=MODEL')).items;const model=models.find(m=>m.code==='model_kimi');assert.equal(model.config.baseUrl,'http://127.0.0.1:18091');model.config.target.allowPrivate=true;
 await api('/admin/resources/'+model.id,'PUT',{kind:'MODEL',name:model.name,description:model.description,config:model.config,draftRevision:model.draftRevision,credentialChange:{action:'KEEP'}});
 const impact=await api('/admin/resources/'+model.id+'/impact?action=publish');await api('/admin/resources/'+model.id+'/publish','POST',{impactToken:impact.token});
 const s=await api('/sessions','POST',{userId:'monitor_e2e',channel:'web'});
 const reply=await api('/chat','POST',{sessionId:s.id,userId:'monitor_e2e',text:'[route:chitchat] 你好',supervisorCode:'supervisor'});assert.equal(reply.agentName,'chitchat');
 const detail=await api('/admin/monitor/sessions/'+s.id);assert.equal(detail.messages.length,2);for(const type of ['SESSION_OPENED','TURN_STARTED','INTENT_RECOGNIZED','AGENT_TRANSFER','MODEL_COMPLETED','TURN_COMPLETED'])assert.ok(detail.events.some(e=>e.type===type),type);
 evidence.checks.push({name:'Real main-to-child trace and public history',sessionId:s.id,eventTypes:detail.events.map(e=>e.type)});
 await api('/admin/monitor/sessions/'+s.id+'/assessment','POST',{status:'RESOLVED',note:'测试已核对'});await api('/chat','POST',{sessionId:s.id,userId:'monitor_e2e',text:'[route:chitchat] 还有问题',supervisorCode:'supervisor'});assert.equal((await api('/admin/monitor/sessions/'+s.id)).assessment.status,'PENDING');
 const transfer=await api('/sessions/'+s.id+'/handoff','POST',{userId:'monitor_e2e'});assert.equal(transfer.mode,'SIMULATED');assert.deepEqual(await api('/sessions/'+s.id+'/handoff','POST',{userId:'monitor_e2e'}),transfer);
 const second=await api('/sessions','POST',{userId:'monitor_e2e',channel:'web'});const other=await api('/sessions/'+second.id+'/handoff','POST',{userId:'monitor_e2e'});assert.notEqual(transfer.agentCode,other.agentCode);
 const human=await api('/chat','POST',{sessionId:s.id,userId:'monitor_e2e',text:'请继续处理',supervisorCode:'supervisor'});assert.equal(human.agentName,transfer.agentCode);assert.ok(human.answer.includes('模拟人工客服'));
 const updated=await api('/admin/monitor/sessions/'+s.id);assert.ok(updated.events.some(e=>e.type==='HUMAN_ASSIGNED'));assert.ok(updated.events.some(e=>e.type==='HUMAN_REPLY'));assert.ok(updated.events.some(e=>e.type==='ASSESSMENT_RESET'));
 evidence.checks.push({name:'Two simulated seats, idempotent assignment, history and subsequent same-seat reply',sessionId:s.id,agent:transfer.agentCode,secondAgent:other.agentCode});
 let denied=false;try{await api('/admin/monitor/sessions/'+s.id+'/assessment','POST',{status:'RESOLVED',note:'not allowed'},'viewer');}catch{denied=true;}assert.ok(denied);
 await api('/sessions/'+second.id,'DELETE');assert.equal((await api('/sessions/'+second.id+'/activity','POST',{userId:'monitor_e2e'})).status,'closed');
 denied=false;try{await api('/sessions/'+second.id+'/handoff','POST',{userId:'monitor_e2e'});}catch{denied=true;}assert.ok(denied);
 evidence.checks.push({name:'Read-only permission and closed session cannot reactivate or transfer',passed:true});evidence.passed=true;
}catch(error){evidence.passed=false;evidence.error=error.stack;process.exitCode=1;}
fs.mkdirSync('docs/monitor-evidence',{recursive:true});fs.writeFileSync('docs/monitor-evidence/integration.json',JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence,null,2));
