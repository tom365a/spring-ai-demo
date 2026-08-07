"""Knowledge MCP HTTP bridge for Demo."""
from __future__ import annotations
import os, re, asyncio
from pathlib import Path
from typing import Any
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

app = FastAPI(title="knowledge-mcp", version="1.0.0")
KNOWLEDGE_DIR = Path(os.getenv("KNOWLEDGE_DIR", "/data/knowledge"))

TOOLS = [
    {
        "name": "search_docs",
        "description": "在知识库 Markdown 中检索相关片段",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string"}, "top_k": {"type": "integer"}},
            "required": ["query"],
        },
    },
    {
        "name": "get_doc_chunk",
        "description": "按文档名读取全文",
        "inputSchema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
]


class CallRequest(BaseModel):
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)


def load_docs() -> list[dict[str, str]]:
    if not KNOWLEDGE_DIR.exists():
        return []
    return [
        {"name": p.name, "content": p.read_text(encoding="utf-8")}
        for p in sorted(KNOWLEDGE_DIR.glob("*.md"))
    ]


def score(query: str, content: str) -> float:
    tokens = [t for t in re.split(r"\s+|，|。|、|？|\?", query.lower()) if t]
    if not tokens:
        return 0.0
    text = content.lower()
    return sum(1 for t in tokens if t in text) / len(tokens)


@app.get("/health")
def health():
    return {"ok": True, "docs": len(load_docs())}


@app.get("/tools")
def list_tools():
    return {"tools": TOOLS}


@app.post("/tools/call")
def call_tool(req: CallRequest):
    docs = load_docs()
    if req.name == "search_docs":
        query = str(req.arguments.get("query", ""))
        top_k = int(req.arguments.get("top_k", 3))
        ranked = sorted(
            ({"name": d["name"], "score": score(query, d["content"]), "snippet": d["content"][:400]} for d in docs),
            key=lambda x: x["score"],
            reverse=True,
        )
        return {"ok": True, "source": "mcp", "hits": [h for h in ranked if h["score"] > 0][:top_k]}
    if req.name == "get_doc_chunk":
        name = str(req.arguments.get("name", ""))
        for d in docs:
            if d["name"] == name:
                return {"ok": True, "source": "mcp", "name": name, "content": d["content"]}
        return {"ok": False, "message": f"doc not found: {name}"}
    return {"ok": False, "message": f"unknown tool: {req.name}"}


@app.get("/sse")
async def sse():
    async def gen():
        yield "event: endpoint\ndata: /message\n\n"
        while True:
            yield "event: ping\ndata: {}\n\n"
            await asyncio.sleep(15)
    return StreamingResponse(gen(), media_type="text/event-stream")
