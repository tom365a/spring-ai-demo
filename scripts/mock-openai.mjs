// Deterministic local test fixture; never evidence of a real model's quality.
// node scripts/mock-openai.mjs [port=18091] [log=tmp/mock-openai-requests.jsonl]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
const port = Number(process.argv[2] || 18091);
const logFile = path.resolve(process.argv[3] || 'tmp/mock-openai-requests.jsonl');
fs.mkdirSync(path.dirname(logFile), {recursive:true});
let sequence = 0;
const contentText = content => typeof content === 'string' ? content : (content || []).map(x => x.text || '').join('\n');
function completion(body) {
  const system = (body.messages || []).filter(m => m.role === 'system').map(m => contentText(m.content)).join('\n');
  const user = [...(body.messages || [])].reverse().find(m => m.role === 'user');
  const input = contentText(user?.content);
  const children = [...system.matchAll(/^\d+\.\s+([a-zA-Z0-9_-]+)（/gm)].map(m => m[1]);
  const requested = input.match(/\[route:([a-zA-Z0-9_-]+)\]/)?.[1];
  const isRouting = children.length > 0 || system.includes('（无可路由子 Agent）');
  let answer = `LOCAL_STUB_REPLY ${system.split('\n')[0] || 'summary'} | ${input.slice(-160)}`;
  if (isRouting) {
    const target = requested || children[0] || 'none';
    answer = JSON.stringify({intent:'test',confidence:0.99,targetAgent:target,reason:'local deterministic routing fixture',slots:{},needClarify:target==='none',clarifyQuestion:target==='none'?'没有可用子 Agent':null});
  }
  const toolRequested = input.includes('[tool:query_order]');
  const tool = (body.tools || []).find(t => /query_?order/i.test(t.function?.name || ''));
  const alreadyCalled = (body.messages || []).some(m => m.role === 'tool');
  const toolMessage = (body.messages || []).find(m => m.role === 'tool');
  if (toolMessage) answer = `LOCAL_STUB_TOOL_RESULT ${contentText(toolMessage.content)}`;
  const message = toolRequested && tool && !alreadyCalled && !isRouting
    ? {role:'assistant',content:null,tool_calls:[{id:`call_${sequence}`,type:'function',function:{name:tool.function.name,arguments:JSON.stringify({orderId:'ORD20260730001',userId:'u_001'})}}]}
    : {role:'assistant',content:answer};
  return {id:`chatcmpl-local-${sequence}`,object:'chat.completion',created:Math.floor(Date.now()/1000),model:body.model || 'local-stub',choices:[{index:0,message,finish_reason:message.tool_calls?'tool_calls':'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}};
}
const server = http.createServer(async (req,res) => {
  res.setHeader('Content-Type','application/json; charset=utf-8');
  if (req.url === '/health') return res.end(JSON.stringify({ok:true,fixture:true,logFile}));
  if (req.method !== 'POST') {res.statusCode=404;return res.end('{}');}
  try {
    let raw=''; for await (const chunk of req) {raw+=chunk;if(raw.length>2_000_000)throw Error('request too large');}
    const body=JSON.parse(raw);sequence++;
    fs.appendFileSync(logFile,JSON.stringify({sequence,at:new Date().toISOString(),path:req.url,body})+'\n');
    if (req.url?.endsWith('/embeddings')) {
      const inputs=Array.isArray(body.input)?body.input:[body.input];
      return res.end(JSON.stringify({object:'list',model:body.model,data:inputs.map((_,index)=>({object:'embedding',index,embedding:Array.from({length:1536},(_,i)=>i===0?1:0)})),usage:{prompt_tokens:1,total_tokens:1}}));
    }
    if(req.url?.endsWith('/chat/completions'))return res.end(JSON.stringify(completion(body)));
    res.statusCode=404;res.end(JSON.stringify({error:{message:'Unknown fixture endpoint'}}));
  } catch(error) {res.statusCode=400;res.end(JSON.stringify({error:{message:String(error.message)}}));}
});
server.listen(port,'127.0.0.1',()=>console.log(`Local OpenAI fixture listening on http://127.0.0.1:${port}; requests: ${logFile}`));
