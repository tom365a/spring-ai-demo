import fs from 'node:fs';
const readRows=p=>fs.readFileSync(p,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const dest='docs/agent-configuration/evidence';fs.mkdirSync(dest,{recursive:true});
const modelRows=readRows('.tools/mock-openai-requests.jsonl').filter(r=>r.body.messages?.some(m=>m.role==='system'&&typeof m.content==='string'&&/qa_worker_|qa_main_|qa_confirm_/.test(m.content)));
const startupLogs=['qa-server.log','qa-server-restart.log','qa-server-disabled.log','qa-server-final.log'].filter(p=>fs.existsSync('.tools/'+p)).flatMap(p=>fs.readFileSync('.tools/'+p,'utf8').split(/\r?\n/).filter(l=>/Starting CustomerServiceApplication|DefinitionRegistry loaded|Knowledge base has/.test(l)).map(line=>({file:p,line})));
fs.writeFileSync(dest+'/QA_RUNTIME_EVIDENCE.json',JSON.stringify({generatedAt:new Date().toISOString(),environment:{app:'Spring Boot 3.4.2 / Spring AI 1.0.0',java:'21.0.12.1',database:'H2 file ./data/qa-acceptance',http:'127.0.0.1:18080',modelFixture:'127.0.0.1:18091'},httpChecks:readRows('.tools/qa-http-evidence.jsonl'),startupLogs,modelRows},null,2));
for(const test of ['AgentConfigurationTest','QaAgentConfigurationTest'])fs.copyFileSync(`apps/java-gateway/target/surefire-reports/com.demo.cs.${test}.txt`,`${dest}/${test}.txt`);
console.log(`Evidence captured: ${modelRows.length} actual model requests`);
