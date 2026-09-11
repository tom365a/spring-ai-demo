// Independent real HTTP acceptance; invoke before/after around an actual JVM restart.
import fs from 'node:fs';
import assert from 'node:assert/strict';
const phase=process.argv[2]||'before';
const base=process.env.QA_BASE_URL||'http://127.0.0.1:18080';
const statePath='.tools/qa-http-state.json', evidencePath='.tools/qa-http-evidence.jsonl';
const modelLog='.tools/mock-openai-requests.jsonl';
function record(name,data){const row={at:new Date().toISOString(),phase,name,data};fs.appendFileSync(evidencePath,JSON.stringify(row)+'\n');console.log(`PASS ${name}`);}
async function api(path,body,method=body===undefined?'GET':'POST',role='publisher',expected=200){
 const res=await fetch(base+'/api/v1'+path,{method,headers:{'Content-Type':'application/json','X-Admin-Role':role},body:body===undefined?undefined:JSON.stringify(body)});
 const result=await res.json();assert.equal(res.status,expected,`${method} ${path}: ${JSON.stringify(result)}`);return result.data;
}
const admin='/admin/agents';
async function runtime(code){return (await api('/agents/runtime')).items.find(i=>i.code===code);}
async function get(code){return api(`${admin}/${code}`);}
async function update(code,definition){const current=await get(code);return api(`${admin}/${code}`,{draftRevision:current.draftRevision,definition},'PUT','editor');}
async function chat(main,worker){return api('/chat',{supervisorCode:main,userId:'u_001',text:`独立QA查询订单 [route:${worker}] [tool:query_order]`});}
function modelRows(){return fs.readFileSync(modelLog,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);}
function workerRows(code){return modelRows().filter(r=>r.body.messages?.some(m=>m.role==='system'&&typeof m.content==='string'&&m.content.includes(code)));}
function system(row){return row.body.messages.filter(m=>m.role==='system').map(m=>m.content).join('\n');}
if(phase==='before'){
 const suffix=Date.now().toString(36),worker=`qa_worker_${suffix}`,main=`qa_main_${suffix}`;
 const created=await api(admin,{name:'独立QA子Agent',type:'WORKER',description:'中文与特殊字符 <script>text</script>',definition:{prompts:{systemPrompt:`${worker} QA_HTTP_V1`},tools:['query_order'],skills:['clear_response','order_lookup']}});
 assert.match(created.draft.code,/^agent_/);assert.equal(created.enabled,false);assert.equal(await runtime(created.draft.code),undefined);
 // Use returned auto-code, while system marker remains unique for captured request identification.
 const actualWorker=created.draft.code;
 record('auto-code-full-draft',{code:actualWorker,skills:created.draft.skills,enabled:created.enabled});
 await api(`${admin}/${actualWorker}/enable`,{});assert.equal((await runtime(actualWorker)).loadedVersion,1);
 const parent=await api(admin,{code:main,name:'独立QA主Agent',type:'SUPERVISOR',definition:{prompts:{systemPrompt:`${main} QA_MAIN`},skills:['clear_response'],children:[{agentCode:actualWorker,whenToUse:'独立配置测试'}]}});
 await api(`${admin}/${main}/enable`,{});assert.equal((await runtime(main)).loadedVersion,1);
 const first=await chat(main,actualWorker);assert.equal(first.agentName,actualWorker);assert.match(first.answer,/LOCAL_STUB_TOOL_RESULT/);assert.match(first.answer,/ORD20260730001/);assert.doesNotMatch(first.answer,/not_found/);
 const v1rows=workerRows(worker);assert(v1rows.length>=2);assert.match(system(v1rows[0]),/清晰表达/);assert.match(system(v1rows[0]),/订单查询/);
 assert.deepEqual(v1rows[0].body.tools.map(t=>t.function.name).sort(),['query_logistics','query_order']);assert(v1rows.some(r=>r.body.messages.some(m=>m.role==='tool')));
 record('formal-chat-skills-deduplicated-tools-success',{main,worker:actualWorker,answer:first.answer,modelEvidenceAt:v1rows.map(r=>r.at)});
 let edited=structuredClone(created.draft);edited.prompts.systemPrompt=`${worker} QA_HTTP_V2`;edited.skills=[];edited.tools=[];
 await update(actualWorker,edited);let before=workerRows(worker).length;await chat(main,actualWorker);
 assert(workerRows(worker).slice(before).every(r=>system(r).includes('QA_HTTP_V1')));record('draft-isolation',await get(actualWorker));
 await api(`${admin}/${actualWorker}/publish`,{remark:'QA remove capabilities'});before=workerRows(worker).length;
 const second=await chat(main,actualWorker);assert.doesNotMatch(second.answer,/LOCAL_STUB_TOOL_RESULT/);
 const v2rows=workerRows(worker).slice(before);assert(v2rows.length>0);for(const row of v2rows){assert.match(system(row),/QA_HTTP_V2/);assert.doesNotMatch(system(row),/清晰表达|订单查询/);assert.equal(row.body.tools?.length||0,0);}
 record('publish-removes-skill-and-tool-runtime-access',{version:(await runtime(actualWorker)).loadedVersion,modelEvidenceAt:v2rows.map(r=>r.at)});
 await api(`${admin}/${actualWorker}/rollback`,{version:1,remark:'QA restore v1'});assert.equal((await runtime(actualWorker)).loadedVersion,3);assert.deepEqual((await get(actualWorker)).draft.skills,['clear_response','order_lookup']);
 record('rollback-restores-skill-snapshot',await get(actualWorker));
 for(const badMain of [actualWorker,'qa_no_such_main'])await api('/chat',{supervisorCode:badMain,userId:'u_001',text:'invalid main'},'POST','publisher',400);
 await api(`${admin}/${actualWorker}/disable`,{});assert.equal(await runtime(actualWorker),undefined);
 before=workerRows(worker).length;const none=await chat(main,actualWorker);assert.match(none.answer,/没有可用子/);assert.equal(workerRows(worker).length,before);
 record('disabled-linked-worker-no-model-dispatch',{answer:none.answer});
 await api(`${admin}/${main}/disable`,{});await api('/chat',{supervisorCode:main,userId:'u_001',text:'disabled main'},'POST','publisher',400);
 await api(`${admin}/${main}/enable`,{},'POST','publisher',422);assert.equal(await runtime(main),undefined);
 await api(`${admin}/${actualWorker}/enable`,{});await api(`${admin}/${main}/enable`,{});
 record('reenable-validates-dependencies',{main,worker:actualWorker});
 const draftOnly=await api(admin,{name:'只存草稿',definition:{prompts:{systemPrompt:'QA_NEVER_PUBLISHED'}}});
 edited=structuredClone((await get(actualWorker)).draft);edited.prompts.systemPrompt=`${worker} QA_UNPUBLISHED_AFTER_RESTART`;await update(actualWorker,edited);
 const seed=await get('supervisor');const seedDraft=structuredClone(seed.draft);seedDraft.name='QA seed user edit';await update('supervisor',seedDraft);
 const versions=await api(`${admin}/${actualWorker}/versions`);assert.equal(versions.items.length,3);
 const trial=await api(`${admin}/${actualWorker}/trial`,{text:'试运行',useDraft:false,userId:'u_001'});assert.equal(trial.agentVersion,3);
 record('versions-and-published-trial',{versions:versions.items.length,trialVersion:trial.agentVersion});
 fs.writeFileSync(statePath,JSON.stringify({worker:actualWorker,marker:worker,main,draftOnly:draftOnly.draft.code,expectedVersion:3,seedName:seedDraft.name},null,2));
 record('restart-preconditions',JSON.parse(fs.readFileSync(statePath)));
}else if(phase==='after'){
 const state=JSON.parse(fs.readFileSync(statePath));const current=await get(state.worker);
 assert.equal((await runtime(state.worker)).loadedVersion,state.expectedVersion);assert.equal((await runtime(state.main)).loadedVersion,1);
 assert.match(current.published.prompts.systemPrompt,/QA_HTTP_V1/);assert.match(current.draft.prompts.systemPrompt,/QA_UNPUBLISHED_AFTER_RESTART/);assert.equal(current.dirty,true);
 assert.equal(await runtime(state.draftOnly),undefined);assert.equal((await get(state.draftOnly)).enabled,false);
 assert.equal((await get('supervisor')).draft.name,state.seedName);assert.deepEqual(current.published.skills,['clear_response','order_lookup']);
 const before=workerRows(state.marker).length;const response=await chat(state.main,state.worker);assert.equal(response.agentName,state.worker);assert.match(response.answer,/LOCAL_STUB_TOOL_RESULT/);
 const rows=workerRows(state.marker).slice(before);assert(rows.length>=2);assert(rows.every(r=>system(r).includes('QA_HTTP_V1')&&!system(r).includes('QA_UNPUBLISHED_AFTER_RESTART')));
 record('actual-jvm-restart-published-restoration-and-seed-preservation',{...state,modelEvidenceAt:rows.map(r=>r.at),answer:response.answer});
}else if(phase==='negative'){
 const state=JSON.parse(fs.readFileSync(statePath));
 for(const badMain of [state.worker,'qa_no_such_main'])await api('/chat',{supervisorCode:badMain,userId:'u_001',text:'invalid main'},'POST','publisher',400);
 await api(`${admin}/${state.main}/disable`,{});await api('/chat',{supervisorCode:state.main,userId:'u_001',text:'disabled main'},'POST','publisher',400);await api(`${admin}/${state.main}/enable`,{});
 record('invalid-worker-missing-disabled-main-rejected',{validUser:'u_001',targets:[state.worker,'qa_no_such_main',state.main]});
}else if(phase==='confirm'){
 const suffix=Date.now().toString(36),worker=`qa_confirm_${suffix}`,main=`qa_confirm_main_${suffix}`;
 await api(admin,{code:worker,name:'确认流程测试',type:'WORKER',definition:{prompts:{systemPrompt:worker},tools:['cancel_order'],policies:{allowWriteTools:true}}});await api(`${admin}/${worker}/enable`,{});
 await api(admin,{code:main,name:'确认流程主',type:'SUPERVISOR',definition:{prompts:{systemPrompt:main},children:[{agentCode:worker}]}});await api(`${admin}/${main}/enable`,{});
 const begin=()=>api('/chat',{supervisorCode:main,userId:'u_001',text:`取消订单 ORD20260730001 [route:${worker}]`});
 const confirm=(sessionId)=>api('/chat',{sessionId,supervisorCode:main,userId:'u_001',text:'确认',confirm:true,confirmPayload:{action:'cancel_order',agentName:'order',orderId:'ORD20260730002',userId:'u_001'}},'POST','publisher',409);
 let pending=await begin();assert.equal(pending.confirmRequired,true);await api(`${admin}/${worker}/disable`,{});await confirm(pending.sessionId);
 await api(`${admin}/${worker}/enable`,{});pending=await begin();assert.equal(pending.confirmRequired,true);
 const removed=structuredClone((await get(worker)).draft);removed.tools=[];await update(worker,removed);await api(`${admin}/${worker}/publish`,{});await confirm(pending.sessionId);
 record('pending-confirm-cannot-bypass-disabled-agent-or-removed-tool',{worker,main,sessionsBlocked:2,clientPayloadIgnored:true});
}else if(phase==='disabled'){
 const state=JSON.parse(fs.readFileSync(statePath));const modes=await api('/agents/runtime');assert.equal(modes.configEnabled,false);assert.equal(modes.items.length,0);
 await api(`${admin}/${state.worker}/enable`,{},'POST','publisher',409);
 await api('/chat',{supervisorCode:state.main,userId:'u_001',text:'runtime disabled'},'POST','publisher',409);
 record('explicit-runtime-disabled-rejects-enable-and-explicit-chat',modes);
}else{throw Error(`Unknown phase ${phase}`);}
