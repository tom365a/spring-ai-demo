// Integration smoke against the explicitly isolated local backend and owned fixture.
import assert from 'node:assert/strict';
import fs from 'node:fs';
const base='http://localhost:18080/api/v1';
const prefix='rc_'+Date.now().toString(36);
const evidence={at:new Date().toISOString(),prefix,checks:[]};
async function api(path,method='GET',body){
 const response=await fetch(base+path,{method,headers:{'X-Admin-Role':'publisher','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const value=await response.json();if(!response.ok||value.code!==0)throw Error(path+': '+JSON.stringify(value));return value.data;
}
async function lifecycle(id,action){const impact=await api('/admin/resources/'+id+'/impact?action='+action);return api('/admin/resources/'+id+'/'+action,'POST',{impactToken:impact.token});}
async function resource(kind,suffix,config){const value=await api('/admin/resources','POST',{kind,code:prefix+'_'+suffix,name:prefix+' '+suffix,config});await lifecycle(value.id,'publish');return value;}
async function trial(code,text){return api('/admin/agents/'+code+'/trial','POST',{text,useDraft:false,mode:'single'});}
const target={scheme:'http',host:'127.0.0.1',port:18091,allowPrivate:true};
try {
 const read=await resource('TOOL','read',{source:'HTTP',sideEffect:'READ',requireConfirm:false,method:'GET',url:'http://127.0.0.1:18091/http/ping',target,auth:{type:'NONE'},inputSchema:{type:'object',properties:{query:{type:'string'}},required:['query'],additionalProperties:false},mappings:[{sourceKind:'INPUT',source:'query',targetKind:'QUERY',target:'query'}],responsePath:'$.data',timeoutSeconds:10});
 const write=await resource('TOOL','write',{source:'HTTP',sideEffect:'WRITE',requireConfirm:false,method:'POST',url:'http://127.0.0.1:18091/http/write',target,auth:{type:'NONE'},inputSchema:{type:'object',properties:{query:{type:'string'}},required:['query'],additionalProperties:false},mappings:[{sourceKind:'INPUT',source:'query',targetKind:'BODY',target:'query'}],responsePath:'$.data',timeoutSeconds:10});
 const model=(await api('/admin/resources?kind=MODEL')).items.find(x=>x.code==='model_kimi');
 const definition=structuredClone((await api('/admin/agents/chitchat')).draft);
 Object.assign(definition,{code:prefix+'_worker',name:prefix+' worker',tools:[read.code,write.code]});
 definition.modelConfig.resourceId=model.id;definition.prompts.systemPrompt='Execute only the explicitly selected fixture tool, then report its returned receipt.';definition.prompts.userPromptTemplate='{{text}}';definition.policies.allowWriteTools=true;
 await api('/admin/agents','POST',{code:definition.code,name:definition.name,type:'WORKER',definition});await api('/admin/agents/'+definition.code+'/publish','POST',{});
 const readResult=await trial(definition.code,'[tool:'+read.code+'] [args:{"query":"read-proof"}]');
 assert.equal(readResult.confirmRequired,false);assert.match(readResult.answer,/HTTP_RECEIPT_/);assert.ok(readResult.toolCalls.some(t=>t.toolName===read.code||t.tool===read.code||t.name===read.code));
 evidence.checks.push({name:'HTTP read through selected Agent/model and runtime gateway',result:readResult});
 const before=await (await fetch('http://127.0.0.1:18091/fixture/stats')).json();
 const pending=await trial(definition.code,'[tool:'+write.code+'] [args:{"query":"write-proof"}]');assert.equal(pending.confirmRequired,true);
 const paused=await (await fetch('http://127.0.0.1:18091/fixture/stats')).json();assert.equal(paused.writes,before.writes);
 const confirmation=pending.confirmationPayload;
 let forgedDenied=false;try{await api('/confirmations/'+confirmation.id,'POST',{sessionId:confirmation.sessionId,confirm:true,arguments:{query:'forged-client-parameter'}});}catch{forgedDenied=true;}assert.ok(forgedDenied);
 const completed=await api('/confirmations/'+confirmation.id,'POST',{sessionId:confirmation.sessionId,confirm:true});
 assert.match(completed.answer,/write-proof/);assert.doesNotMatch(completed.answer,/forged-client-parameter/);
 const after=await (await fetch('http://127.0.0.1:18091/fixture/stats')).json();assert.equal(after.writes,before.writes+1);
 let duplicateDenied=false;try{await api('/confirmations/'+confirmation.id,'POST',{sessionId:confirmation.sessionId,confirm:true});}catch{duplicateDenied=true;}assert.ok(duplicateDenied);
 evidence.checks.push({name:'HTTP write waits, server parameters authoritative, duplicate denied',result:completed});
 const cancel=await trial(definition.code,'[tool:'+write.code+'] [args:{"query":"cancel-proof"}]');
 await api('/confirmations/'+cancel.confirmationPayload.id,'POST',{sessionId:cancel.confirmationPayload.sessionId,confirm:false});
 const cancelled=await (await fetch('http://127.0.0.1:18091/fixture/stats')).json();assert.equal(cancelled.writes,after.writes);evidence.checks.push({name:'Cancel causes zero write calls',passed:true});
 const mcpTools=[];
 for(const suffix of ['a','b']){
  const service=await resource('MCP','mcp_'+suffix,{transport:'STREAMABLE_HTTP',url:'http://127.0.0.1:18091/mcp/'+suffix,target,auth:{type:'NONE'},timeoutSeconds:10});
  await api('/admin/resources/'+service.id+'/sync','POST',{});await lifecycle(service.id,'publish');
  const items=(await api('/admin/resources?kind=TOOL&source=MCP')).items.filter(t=>t.config.serviceId===service.id);assert.equal(items.length,1);mcpTools.push(items[0]);
 }
 assert.notEqual(mcpTools[0].code,mcpTools[1].code);assert.equal(mcpTools[0].config.nativeName,mcpTools[1].config.nativeName);evidence.checks.push({name:'Two standard MCP services with same native name remain distinct',tools:mcpTools.map(t=>({code:t.code,config:t.config}))});
 let agent=await api('/admin/agents/'+definition.code);
 agent.draft.tools.push(...mcpTools.map(t=>t.code));
 await api('/admin/agents/'+definition.code,'PUT',{name:agent.draft.name,description:agent.draft.description,draftRevision:agent.draftRevision,definition:agent.draft});
 await api('/admin/agents/'+definition.code+'/publish','POST',{});
 for(let index=0;index<mcpTools.length;index++){
  const test=await trial(definition.code,'[tool:'+mcpTools[index].code+'] [args:{"query":"mcp-proof"}]');
  assert.equal(test.confirmRequired,true);
  const out=await api('/confirmations/'+test.confirmationPayload.id,'POST',{sessionId:test.confirmationPayload.sessionId,confirm:true});
  assert.ok(out.answer.includes('resource_fixture_'+['a','b'][index]));
  evidence.checks.push({name:'MCP '+['a','b'][index]+' executes exact service after confirmation',result:out});
 }
 const openai=(await api('/admin/resources?kind=MODEL')).items.find(x=>x.code==='model_openai');assert.equal(openai.config.baseUrl,'http://127.0.0.1:18091');
 await lifecycle(openai.id,'enable');
 agent=await api('/admin/agents/'+definition.code);agent.draft.modelConfig.resourceId=openai.id;
 await api('/admin/agents/'+definition.code,'PUT',{name:agent.draft.name,description:agent.draft.description,draftRevision:agent.draftRevision,definition:agent.draft});await api('/admin/agents/'+definition.code+'/publish','POST',{});
 const selected=await trial(definition.code,'model-selection-proof');assert.equal(selected.diagnostics.models[0].provider,'OPENAI');assert.equal(selected.diagnostics.models[0].resourceId,openai.id);assert.match(selected.answer,/RESOURCE_MODEL gpt-4o-mini/);
 evidence.checks.push({name:'Agent dropdown resource selection actually switches provider and model',result:selected});
 evidence.passed=true;
} catch(error){evidence.passed=false;evidence.error=error.stack;process.exitCode=1;}
finally {fs.mkdirSync('docs/resource-configuration/evidence',{recursive:true});fs.writeFileSync('docs/resource-configuration/evidence/root-resource-integration.json',JSON.stringify(evidence,null,2));console.log(JSON.stringify({passed:evidence.passed,checks:evidence.checks.map(c=>c.name),error:evidence.error},null,2));}
