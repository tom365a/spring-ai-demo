// Explicit, minimal live-provider acceptance. Server owns the credential; this script never reads it.
import assert from 'node:assert/strict';
import fs from 'node:fs';
if(!process.argv.includes('--live'))throw Error('Pass --live for the explicitly authorized live Kimi check.');
const base='http://localhost:18082/api/v1';
const code='live_resource_'+Date.now().toString(36);
const evidence={at:new Date().toISOString(),base,provider:'KIMI',checks:[]};
async function api(path,method='GET',body){
 const response=await fetch(base+path,{method,headers:{'X-Admin-Role':'publisher','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(150000)});
 const value=await response.json();if(!response.ok||value.code!==0)throw Error(path+': '+JSON.stringify(value));return value.data;
}
try{
 const models=(await api('/admin/resources?kind=MODEL')).items;
 const model=models.find(m=>m.code==='model_kimi');assert.equal(model.config.provider,'KIMI');assert.equal(model.config.model,'kimi-k3');assert.ok(model.config.baseUrl.startsWith('https://api.moonshot.cn'));assert.equal(model.credential.configured,true);
 assert.equal(models.find(m=>m.code==='model_openai').enabled,false);
 const tool=await api('/admin/resources','POST',{kind:'TOOL',code:code+'_http',name:'资源链路只读验证',description:'读取本机自有测试服务的随机回执，无业务写入',config:{source:'HTTP',sideEffect:'READ',requireConfirm:false,method:'GET',url:'http://127.0.0.1:18091/http/ping',target:{scheme:'http',host:'127.0.0.1',port:18091,allowPrivate:true},auth:{type:'NONE'},inputSchema:{type:'object',properties:{},additionalProperties:false},mappings:[],responsePath:'$.data',timeoutSeconds:10}});
 const impact=await api('/admin/resources/'+tool.id+'/impact?action=publish');await api('/admin/resources/'+tool.id+'/publish','POST',{impactToken:impact.token});
 const definition=structuredClone((await api('/admin/agents/chitchat')).draft);
 Object.assign(definition,{code:code+'_agent',name:'资源链路验收助手',description:'隔离环境真实 Kimi 工具调用验证',tools:[tool.code],skills:[]});
 definition.modelConfig.resourceId=model.id;definition.prompts.systemPrompt='你负责连接验证。必须调用提供的只读工具一次，取得服务器返回的receipt，然后只输出该receipt原文。不要猜测或编造，不要重复调用。';definition.prompts.userPromptTemplate='{{text}}';definition.policies.maxToolRounds=1;definition.policies.taskTimeoutSeconds=120;
 await api('/admin/agents','POST',{code:definition.code,name:definition.name,type:'WORKER',definition});await api('/admin/agents/'+definition.code+'/publish','POST',{});
 const before=await(await fetch('http://127.0.0.1:18091/fixture/stats')).json();
 const result=await api('/admin/agents/'+definition.code+'/trial','POST',{text:'现在调用只读工具，返回实际receipt。',useDraft:false,mode:'single'});
 const after=await(await fetch('http://127.0.0.1:18091/fixture/stats')).json();
 assert.ok(result.answer.includes(after.receipt));assert.equal(after.http,before.http+1);assert.equal(after.writes,before.writes);assert.equal(result.confirmRequired,false);
 assert.ok(result.toolCalls.some(t=>t.source==='HTTP'&&t.success===true));assert.ok(result.diagnostics.models.every(m=>m.provider==='KIMI'&&m.resourceId===model.id&&m.model==='kimi-k3'));
 evidence.checks.push({name:'Real kimi-k3 selected by resource, performs HTTP read and returns unpredictable receipt',result,fixtureRequests:after.http-before.http,fixtureWrites:after.writes-before.writes});evidence.passed=true;
}catch(error){evidence.passed=false;evidence.error=error.stack;process.exitCode=1;}
finally{fs.mkdirSync('docs/resource-configuration/evidence',{recursive:true});fs.writeFileSync('docs/resource-configuration/evidence/kimi-live-resource.json',JSON.stringify(evidence,null,2));console.log(JSON.stringify({passed:evidence.passed,checks:evidence.checks.map(c=>c.name),error:evidence.error},null,2));}
