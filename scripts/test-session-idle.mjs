// Deterministic browser-state tests; no model calls, database changes or real waiting.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const html=fs.readFileSync('apps/java-gateway/src/main/resources/static/index.html','utf8');
const source=html.slice(html.indexOf('const API="";'),html.indexOf('async function refreshAgents()'));
let now=1_000_000,seq=0;
const requests=[],timers=[],elements=new Map();
const element=()=>({textContent:'',innerHTML:'',value:'',scrollHeight:0,appendChild(){},querySelectorAll(){return []},addEventListener(name,fn){this[name]=fn;}});
const context=vm.createContext({Date:{now:()=>now},console,encodeURIComponent,Error,JSON,
 document:{getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);},createElement:element,createTextNode:text=>text,addEventListener(){}},
 setInterval(fn){timers.push(fn);},setTimeout(fn){return fn;},clearTimeout(){},
 async fetch(url,options){requests.push({url,...options});return {ok:true,json:async()=>({code:0,data:url.endsWith('/activity')?{status:'active'}:options.method==='DELETE'?{}:{id:'session_'+(++seq)}})};}
});
vm.runInContext(source,context);
await vm.runInContext('createSession()',context);
assert.equal(seq,1,'opening starts one session');
now+=299_999;timers[0]();assert.equal(vm.runInContext('sessionEnded',context),false);
now++;timers[0]();assert.equal(vm.runInContext('sessionEnded',context),true,'300 seconds ends session');
await vm.runInContext('reportInput(true)',context);assert.equal(seq,2,'new input after timeout opens new session');
assert.equal(vm.runInContext('sessionEnded',context),false);
now+=240_000;await vm.runInContext('reportInput(true)',context);
now+=240_000;timers[0]();assert.equal(vm.runInContext('sessionEnded',context),false,'input resets idle clock');
await Promise.all([vm.runInContext('createSession()',context),vm.runInContext('createSession()',context)]);
assert.equal(seq,3,'concurrent session creation coalesces');
assert.equal(requests.filter(r=>r.method==='DELETE').length,1,'manual new session closes old session');
console.log('PASS: opening, 5-minute boundary, reopen on input, input refresh, concurrent creation and manual closure');
