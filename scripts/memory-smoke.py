#!/usr/bin/env python3
"""长期记忆链路冒烟测试（不依赖 Spring 启动）。

校验 HybridMemoryStore 依赖的三个外部事实是否成立：
  1. DashScope 向量模型 + base-url 拼接是否正确（404 / 维度是否符合预期）
  2. ES 索引 mapping（dense_vector dims + cosine）能否按代码里的定义创建
  3. ES 8.x 的 BM25(query) + kNN + rank.rrf 混合检索、以及纯 kNN 取 cosineScore 能否工作

用法：
  scripts/with-env.sh python3 scripts/memory-smoke.py
  ES_URIS=http://localhost:9200 DASHSCOPE_API_KEY=*** python3 scripts/memory-smoke.py

索引名默认用 agent_memory_smoke，跑完自动删除；加 --keep 保留现场。
"""
import json
import os
import sys
import urllib.error
import urllib.request

ES_URIS = os.environ.get("ES_URIS", "http://localhost:9200").rstrip("/")
BASE_URL = os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com").rstrip("/")
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
MODEL = os.environ.get("DASHSCOPE_EMBEDDING_MODEL", "qwen3.7-text-embedding")
EXPECTED_DIMS = int(os.environ.get("DASHSCOPE_EMBEDDING_DIMENSIONS", "1024"))
INDEX = os.environ.get("SMOKE_INDEX", "agent_memory_smoke")
EMBED_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding"

failures = []


def check(name, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def http(url, payload=None, method=None, headers=None, timeout=30, data=None):
    body = data
    if body is None and payload is not None:
        body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, method=method or ("POST" if body else "GET"))
    # ES 8.x 会拒绝缺省/错误的 Content-Type（406：application/x-www-form-urlencoded is not supported）
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        # 404（删索引时不存在）等场景由调用方按 status 判断，不直接抛栈
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw}


def embed(text, label=""):
    status, body = http(
        BASE_URL + EMBED_PATH,
        payload={"model": MODEL, "input": {"texts": [text]}, "parameters": {"text_type": "document"}},
        headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
    )
    check(f"embedding HTTP 200 {label}", status == 200, f"status={status}")
    if status != 200:
        print("  embedding 调用失败，原始响应：", json.dumps(body, ensure_ascii=False)[:500])
        sys.exit(1)
    return body["output"]["embeddings"][0]["embedding"]


def main():
    keep = "--keep" in sys.argv
    print(f"ES={ES_URIS}  EMBED={BASE_URL}{EMBED_PATH}  model={MODEL}  expectedDims={EXPECTED_DIMS}")

    print("\n[1] Elasticsearch 可达性（无认证）")
    status, info = http(f"{ES_URIS}/")
    check("GET / 200", status == 200, f"version={info['version']['number']}")
    ver = tuple(int(x) for x in info["version"]["number"].split("."))
    check("版本 >= 8.11（rank.rrf 可用）", ver >= (8, 11), f"actual={info['version']['number']}")

    print("\n[2] 向量模型与维度")
    vec_a = embed("用户偏好使用 pnpm 而不是 npm 管理依赖", "[document/user 偏好]")
    vec_b = embed("这个项目使用 Java 17 和 Spring Boot 3.5", "[document/项目栈]")
    vec_q = embed("包管理器用什么", "[query]")
    check("维度与配置一致", len(vec_a) == EXPECTED_DIMS, f"actual={len(vec_a)} configured={EXPECTED_DIMS}")
    check("三个向量维度相同", len(vec_a) == len(vec_b) == len(vec_q))

    print(f"\n[3] 建索引（复用 HybridMemoryStore.ensureIndex 的 mapping）index={INDEX}")
    http(f"{ES_URIS}/{INDEX}", method="DELETE")  # 清掉上次残留，404 忽略
    mapping = {
        "mappings": {
            "properties": {
                "memoryId": {"type": "keyword"},
                "content": {"type": "text"},
                "content_vector": {"type": "dense_vector", "dims": len(vec_a), "index": True, "similarity": "cosine"},
                "type": {"type": "keyword"},
                "confidence": {"type": "float"},
                "updateAt": {"type": "date"},
                "ttl": {"type": "integer"},
                "hitCount": {"type": "integer"},
                "meta": {"type": "object", "enabled": True},
            }
        }
    }
    status, body = http(f"{ES_URIS}/{INDEX}", payload=mapping, method="PUT")
    check("创建索引成功", status == 200 and body.get("acknowledged") is not False, f"status={status}")
    if status != 200:
        print("  建索引失败，原始响应：", json.dumps(body, ensure_ascii=False)[:500])
        print("  中止：继续 bulk 会被 auto-create 成 content_vector 非 dense_vector 的索引，后续结论全部失真")
        return 1
    status, m = http(f"{ES_URIS}/{INDEX}/_mapping")
    vec_field = m.get(INDEX, {}).get("mappings", {}).get("properties", {}).get("content_vector", {})
    check("mapping 为 dense_vector 且相似度 cosine",
          vec_field.get("type") == "dense_vector" and vec_field.get("similarity") == "cosine",
          f"actual={json.dumps(vec_field, ensure_ascii=False)}")

    docs = [
        ("m-user-1", "USER", "用户偏好使用 pnpm 而不是 npm 管理依赖", vec_a, 0.8, 5),
        ("m-proj-1", "PROJECT", "这个项目使用 Java 17 和 Spring Boot 3.5", vec_b, 0.7, 0),
    ]
    lines = []
    for mid, mtype, content, vec, conf, hits in docs:
        now = "2026-09-01T12:00:00"
        meta = {"runId": "smoke-run", "distinctHitCount": hits}
        lines.append(json.dumps({"index": {"_index": INDEX, "_id": mid}}))
        lines.append(json.dumps({
            "memoryId": mid, "type": mtype, "content": content, "content_vector": vec,
            "confidence": conf, "updateAt": now, "ttl": 86400, "hitCount": hits, "meta": meta,
        }))
    status, body = http(
        f"{ES_URIS}/_bulk",
        headers={"Content-Type": "application/x-ndjson"},
        data=("\n".join(lines) + "\n").encode(),
    )
    check("bulk 写入成功", status == 200 and not body.get("errors"), f"status={status}")
    # 注意：ES 的 _refresh 不接受 body（带 payload={} 会 400 且被忽略，导致后续搜索 0 命中）
    status, body = http(f"{ES_URIS}/{INDEX}/_refresh", method="POST")
    check("refresh 成功", status == 200, f"status={status}")
    status, body = http(f"{ES_URIS}/{INDEX}/_count", payload={"query": {"match_all": {}}})
    check("refresh 后文档可被搜索到", body.get("count") == len(docs),
          f"count={body.get('count')} expected={len(docs)}")

    def hybrid_query(mtype, text):
        """完全镜像 HybridMemoryStore.hybridSearchByType 的请求形状。"""
        return {
            "query": {"bool": {"must": [{"match": {"content": text}}],
                               "filter": [{"term": {"type": mtype}}]}},
            "knn": {"field": "content_vector", "query_vector": vec_q, "k": 10, "num_candidates": 100,
                    # 回归点：knn 不继承外层 query 的 filter，必须自己带一份，否则跨类型召回
                    "filter": [{"term": {"type": mtype}}]},
            "rank": {"rrf": {"rank_constant": 60, "rank_window_size": 100}},
            "size": 10,
            "_source": {"excludes": ["content_vector"]},
        }

    print("\n[4] 混合检索：BM25(query.bool) + kNN + rank.rrf（对应 hybridSearchByType）")
    status, body = http(f"{ES_URIS}/{INDEX}/_search", payload=hybrid_query("USER", "包管理器用什么"))
    hits = body.get("hits", {}).get("hits", [])
    check("混排查询未报错", status == 200,
          f"status={status} err={json.dumps(body.get('error', {}), ensure_ascii=False)[:200]}")
    check("混排命中 USER 记忆", [h["_id"] for h in hits] == ["m-user-1"], f"hits={[h['_id'] for h in hits]}")

    print("\n[5] type 隔离（回归：缺 knn.filter 会被向量腿跨类型打穿）")
    ids = [h["_id"] for h in body.get("hits", {}).get("hits", [])]
    check("USER 层混排不含 PROJECT 记忆", "m-proj-1" not in ids, f"hits={ids}")
    status, body = http(f"{ES_URIS}/{INDEX}/_search", payload=hybrid_query("PROJECT", "包管理器用什么"))
    ids = [h["_id"] for h in body.get("hits", {}).get("hits", [])]
    check("PROJECT 层混排命中 m-proj-1 且不含 USER",
          "m-proj-1" in ids and "m-user-1" not in ids, f"hits={ids}")

    # 反证：刻意去掉 knn.filter，如果仍能召回到另一类型，说明这个坑真实存在
    leaky = hybrid_query("USER", "包管理器用什么")
    del leaky["knn"]["filter"]
    status, body = http(f"{ES_URIS}/{INDEX}/_search", payload=leaky)
    leak_ids = [h["_id"] for h in body.get("hits", {}).get("hits", [])]
    check("反证成立：不带 knn.filter 时确实会跨类型召回", "m-proj-1" in leak_ids, f"hits={leak_ids}")

    print("\n[6] 纯 kNN 取 cosineScore（对应 vectorSearch，阈值判定用）")
    knn_only = {
        "knn": {"field": "content_vector", "query_vector": vec_q, "k": 20, "num_candidates": 200},
        "size": 20,
        "_source": {"excludes": ["content_vector"]},
    }
    status, body = http(f"{ES_URIS}/{INDEX}/_search", payload=knn_only)
    scores = {h["_id"]: round(h["_score"], 4) for h in body.get("hits", {}).get("hits", [])}
    check("纯 kNN 返回分数", bool(scores), f"scores={scores}")
    check("语义更近的分数更高（>=MATCH_THRESHOLD 0.85 才有机会命中）",
          scores.get("m-user-1", 0) > scores.get("m-proj-1", 0),
          f"user={scores.get('m-user-1')} project={scores.get('m-proj-1')}")
    check("cosine 分数落在 [0,1]（dense_vector similarity=cosine）",
          bool(scores) and all(0.0 <= v <= 1.0001 for v in scores.values()), f"scores={scores}")

    print("\n[7] partial update（对应 strengthenMemory -> updateMemory）")
    status, body = http(
        f"{ES_URIS}/{INDEX}/_update/m-user-1",
        payload={"doc": {"hitCount": 6, "confidence": 0.88, "type": "USER",
                          "updateAt": "2026-09-01T13:00:00", "ttl": 31536000,
                          "meta": {"runId": "smoke-run", "distinctHitCount": 6}}},
    )
    check("partial update 成功", status == 200, f"status={status} result={body.get('result')}")
    status, body = http(f"{ES_URIS}/{INDEX}/_doc/m-user-1")
    src = body.get("_source", {})
    check("向量未被 partial update 清空", len(src.get("content_vector", [])) == len(vec_a),
          f"dims={len(src.get('content_vector', []))}")
    check("content 保留", src.get("content", "").startswith("用户偏好"), f"content={src.get('content')}")

    if keep:
        print(f"\n[--keep] 保留索引 {INDEX}，可手动查看：curl {ES_URIS}/{INDEX}/_search")
    else:
        http(f"{ES_URIS}/{INDEX}", method="DELETE")
        print(f"\n清理冒烟索引 {INDEX}")

    print("\n" + ("=" * 56))
    if failures:
        print(f"冒烟结果: FAILED -> {failures}")
        return 1
    print("冒烟结果: ALL PASS（向量模型 / ES mapping / RRF 混排 / kNN cosine 均可用）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
