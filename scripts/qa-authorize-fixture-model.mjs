// QA-only preparation: repoint the seeded model resource at an owned loopback fixture and explicitly
// authorize that private target, exactly as an administrator would through the pages. Publisher role.
// Usage: node scripts/qa-authorize-fixture-model.mjs [backendPort=18080] [fixturePort=18091] [code=model_kimi]
import assert from 'node:assert/strict';
const backendPort=Number(process.argv[2]||18080),fixturePort=Number(process.argv[3]||18091),code=process.argv[4]||'model_kimi';
const base='http://127.0.0.1:'+backendPort+'/api/v1';
async function api(path,method='GET',body){
 const response=await fetch(base+path,{method,headers:{'X-Admin-Role':'publisher','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const value=await response.json();if(!response.ok||value.code!==0)throw Error(method+' '+path+': '+JSON.stringify(value));return value.data;
}
const model=(await api('/admin/resources?kind=MODEL')).items.find(m=>m.code===code);
assert.ok(model,'seeded model resource '+code+' not found');
const current=await api('/admin/resources/'+model.id);
const config=structuredClone(current.config);
config.baseUrl='http://127.0.0.1:'+fixturePort;
config.target={scheme:'http',host:'127.0.0.1',port:fixturePort,allowPrivate:true};
await api('/admin/resources/'+model.id,'PUT',{kind:'MODEL',code:current.code,name:current.name,draftRevision:current.draftRevision,config});
const impact=await api('/admin/resources/'+model.id+'/impact?action=publish');
await api('/admin/resources/'+model.id+'/publish','POST',{impactToken:impact.token});
const published=await api('/admin/resources/'+model.id);
assert.equal(published.config.baseUrl,'http://127.0.0.1:'+fixturePort);
assert.equal(published.config.target.allowPrivate,true);
console.log(JSON.stringify({code,id:model.id,baseUrl:published.config.baseUrl,publishedVersion:published.publishedVersion,allowPrivate:true}));
