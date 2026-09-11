// Explicit live acceptance against a running app. Uses its configuration, never reads API keys.
import fs from 'node:fs';
import assert from 'node:assert/strict';
if (!process.argv.includes('--live')) throw Error('Pass --live to authorize calls through the configured application.');
const base = process.env.QA_BASE_URL || 'http://127.0.0.1:18080';
const evidence = { at: new Date().toISOString(), base, checks: [] };
const path = 'docs/model-providers/evidence/live-acceptance.json';
function record(name, data) {
  evidence.checks.push({name, data});
  fs.mkdirSync('docs/model-providers/evidence', {recursive:true});
  fs.writeFileSync(path, JSON.stringify(evidence, null, 2));
  console.log(`PASS ${name}`);
}
async function api(path, body) {
  const res = await fetch(base + '/api/v1' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {'Content-Type':'application/json', 'X-Admin-Role':'publisher'},
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(150000)
  });
  const result = await res.json();
  assert.equal(res.status, 200, JSON.stringify(result));
  assert.equal(result.code, 0, JSON.stringify(result));
  return result.data;
}
const cfg = await api('/debug/config');
assert.equal(cfg.llmProvider, 'kimi'); assert.equal(cfg.llmModel, 'kimi-k3');
assert.equal(cfg.embeddingModel, 'disabled'); assert.equal(cfg.vectorBackend, 'lexical');
record('kimi-active-embedding-disabled', cfg);
const ingested = await api('/knowledge/ingest', {title:'接入验证物流说明',content:'星河快递配送时效为三天，服务电话为 400-123-5678。',source:'live-acceptance'});
const search = await api('/knowledge/search', {query:'星河快递配送时效',topK:3});
assert(search.hits.some(h => h.metadata.doc_id === ingested.docId || h.content.includes('星河快递')));
assert(search.hits.every(h => h.metadata.retrieval === 'lexical'));
record('local-knowledge-ingest-and-retrieval', search);
const order = await api('/chat', {supervisorCode:'supervisor', userId:'u_001',text:'请实际调用查单工具查询订单 ORD20260730001 的状态，简短回答。'});
assert.equal(order.agentName,'order'); assert.match(order.answer,/ORD20260730001/);
assert.doesNotMatch(order.answer,/工具调用失败|Kimi.*failed|not_found/);
record('main-agent-routes-to-order-and-answers', order);
const audit = await api('/debug/tools?sessionId=' + encodeURIComponent(order.sessionId));
assert(audit.items.some(t => t.name === 'query_order' && t.success && t.arguments.orderId === 'ORD20260730001'));
record('real-tool-callback-audit', audit);
const draft = await api('/admin/agents', {name:'Kimi接入验收 Agent',type:'WORKER',description:'验证新建配置启用后使用 Kimi 模型',definition:{prompts:{systemPrompt:'你是接入验收助手。用户询问验收口令时，只输出：星河连接已就绪。'},skills:['clear_response']}});
assert.equal(draft.enabled,false);
await api('/admin/agents/' + draft.draft.code + '/enable', {});
const trial = await api('/admin/agents/' + draft.draft.code + '/trial', {text:'验收口令是什么？',userId:'u_001',useDraft:false});
assert.match(JSON.stringify(trial),/星河连接已就绪/);
record('create-enable-real-kimi-trial', {code:draft.draft.code,trial});
const knowledge = await api('/admin/agents/knowledge/trial', {text:'根据知识库，星河快递配送时效为多久？请简短回答。',userId:'u_001',useDraft:false});
assert.match(knowledge.answer, /三天|3\s*天/);
record('real-kimi-knowledge-answer', knowledge);
const stream = await fetch(base+'/api/v1/chat/stream', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({supervisorCode:'supervisor',userId:'u_001',text:'你好，请用一句话打个招呼。'}),signal:AbortSignal.timeout(150000)});
assert.equal(stream.status,200);
const sse=await stream.text();
assert.match(sse,/event:done/); assert.doesNotMatch(sse,/event:error/);
record('buffered-sse-completes', {contentType:stream.headers.get('content-type'),sse});
console.log('Live acceptance finished. OpenAI was not called by this script.');
