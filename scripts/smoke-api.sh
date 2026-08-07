#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}

echo "[1] health"
curl -sf "$BASE/actuator/health" | grep -q UP

echo "[2] create session + chat"
SID=$(curl -sf -X POST "$BASE/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u_001"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
curl -sf -X POST "$BASE/api/v1/chat" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"userId\":\"u_001\",\"text\":\"国内订单退货几天？\"}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);assert d.get('code',0)==0;assert (d.get('data') or d).get('answer')"

echo "[3] admin agents"
curl -sf "$BASE/api/v1/admin/agents" -H 'X-Admin-Role: viewer' \
  | python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0 and d['data']['total']>=6"

echo "[4] admin forbid publish as viewer"
code=$(curl -s -o /tmp/pub.json -w '%{http_code}' -X POST "$BASE/api/v1/admin/agents/order/publish" \
  -H 'Content-Type: application/json' -H 'X-Admin-Role: viewer' -d '{"remark":"x"}')
python3 -c "import json;d=json.load(open('/tmp/pub.json'));assert d.get('code')==40301;print('http',$code)"

echo "smoke ok SESSION_ID=$SID"
