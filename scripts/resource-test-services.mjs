// Owned deterministic fixtures for resource integration. Not a model-quality or real business service.
// node scripts/resource-test-services.mjs [port=18091]
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
const port=Number(process.argv[2]||18091);
const log='.tools/resource-fixture-requests.jsonl';fs.mkdirSync('.tools',{recursive:true});
const receipt='HTTP_RECEIPT_'+crypto.randomBytes(6).toString('hex');
let sequence=0,writes=0;
const stats={models:0,http:0,mcp:0};
function text(value){return typeof value==='string'?value:(Array.isArray(value)?value.map(x=>x.text||'').join('\n'):'');}
const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://127.0.0.1');
  const send=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(data));};
  if(url.pathname==='/health')return send(200,{fixture:true,ok:true});
  if(url.pathname==='/fixture/stats')return send(200,{...stats,writes,receipt});
  let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>32*1024*1024)return send(413,{error:'fixture body limit'});}
  let body;try{body=raw?JSON.parse(raw):{};}catch{return send(400,{error:'fixture JSON error'});}
  sequence++;
  fs.appendFileSync(log,JSON.stringify({sequence,at:new Date().toISOString(),path:url.pathname,method:req.method,body})+'\n');
  if(url.pathname==='/v1/chat/completions'){
    stats.models++;
    const system=(body.messages||[]).filter(m=>m.role==='system').map(m=>text(m.content)).join('\n');
    const user=text([...(body.messages||[])].reverse().find(m=>m.role==='user')?.content);
    const childCodes=[...new Set([
      ...[...system.matchAll(/^\d+\.\s+([A-Za-z0-9_-]+)（/gm)].map(m=>m[1]),
      ...[...system.matchAll(/^([A-Za-z0-9_-]+): .+ — /gm)].map(m=>m[1])
    ])];
    const selected=user.match(/\[route:([A-Za-z0-9_-]+)\]/)?.[1]||childCodes[0];
    const isRoute=childCodes.length>0;
    const results=(body.messages||[]).filter(m=>m.role==='tool');
    const wanted=user.match(/\[tool:([^\]]+)\]/)?.[1];
    const tool=(body.tools||[]).find(t=>t.function.name===wanted)||(wanted?(body.tools||[])[0]:null);
    const argumentsText=user.match(/\[args:(\{.*?\})\]/)?.[1]||'{}';
    let message;
    if(isRoute)message={role:'assistant',content:JSON.stringify({intent:'resource_test',confidence:0.99,targetAgent:selected||'none',reason:'controlled fixture routing',slots:{},needClarify:!selected})};
    else if(tool&&!results.length)message={role:'assistant',content:null,reasoning_content:'private fixture reasoning',tool_calls:[{id:'resource_call_'+sequence,type:'function',function:{name:tool.function.name,arguments:argumentsText}}]};
    else message={role:'assistant',content:results.length?'RESOURCE_TOOL_RESULT '+results.map(m=>text(m.content)).join('\n'):'RESOURCE_MODEL '+body.model+' | '+user.slice(-150)};
    return send(200,{id:'resource_completion_'+sequence,model:body.model,choices:[{index:0,message,finish_reason:message.tool_calls?'tool_calls':'stop'}],usage:{prompt_tokens:2,completion_tokens:2,total_tokens:4}});
  }
  if(url.pathname.startsWith('/http/')){
    stats.http++;if(req.method!=='GET')writes++;
    if(url.pathname==='/http/error')return send(503,{error:'untrusted provider failure'});
    if(url.pathname==='/http/redirect'){res.writeHead(302,{Location:'http://127.0.0.1:'+port+'/http/ping'});return res.end();}
    if(url.pathname==='/http/slow'){await new Promise(resolve=>setTimeout(resolve,1500));}
    return send(200,{ok:true,data:{receipt,method:req.method,path:url.pathname,query:Object.fromEntries(url.searchParams),body,writes}});
  }
  if(url.pathname.startsWith('/mcp/')){
    stats.mcp++;
    if(req.method==='DELETE'){res.writeHead(204);return res.end();}
    const session='resource_fixture_'+url.pathname.split('/').at(-1);
    const rpc=result=>send(200,{jsonrpc:'2.0',id:body.id,result});
    if(body.method==='initialize'){
      res.setHeader('Mcp-Session-Id',session);
      return rpc({protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name:session,version:'1'}});
    }
    if(req.headers['mcp-session-id']!==session||req.headers['mcp-protocol-version']!=='2025-11-25')return send(400,{error:'missing session/version'});
    if(body.method==='notifications/initialized'){res.writeHead(202);return res.end();}
    if(body.method==='tools/list')return rpc({tools:[{name:'lookup',description:'Fixture lookup from '+session,inputSchema:{type:'object',properties:{query:{type:'string'}},required:['query'],additionalProperties:false},annotations:{readOnlyHint:true}}]});
    if(body.method==='tools/call')return rpc({content:[{type:'text',text:JSON.stringify({service:session,query:body.params?.arguments?.query,receipt})}]});
    return send(200,{jsonrpc:'2.0',id:body.id,error:{code:-32601,message:'Unsupported fixture method'}});
  }
  if(url.pathname==='/legacy/tools')return send(200,{tools:[{name:'lookup',description:'Legacy fixture lookup',inputSchema:{type:'object',properties:{query:{type:'string'}},additionalProperties:false}}]});
  if(url.pathname==='/legacy/tools/call')return send(200,{ok:true,result:{source:'legacy',arguments:body.arguments}});
  send(404,{error:'Unknown fixture endpoint'});
});
server.listen(port,'127.0.0.1',()=>console.log('Resource fixtures listening on 127.0.0.1:'+port));
