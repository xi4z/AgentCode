#!/usr/bin/env bash
# 启动长期记忆（HybridMemoryStore）依赖的单节点 Elasticsearch。
#
# 1) 不开启 xpack security —— 按要求无需账号密码即可访问
# 2) 激活免费 trial license —— RRF 与近似 kNN 是企业级特性，
#    basic license 下 HybridMemoryStore 的混排/纯 kNN 会直接 403 security_exception
# 3) 用一次真实 RRF 查询验证能力可用
#
#   scripts/es-up.sh
#   ES_CONTAINER=es-test ES_PORT=9201 scripts/es-up.sh
set -euo pipefail

NAME="${ES_CONTAINER:-es01}"
PORT="${ES_PORT:-9200}"
IMAGE="${ES_IMAGE:-docker.elastic.co/elasticsearch/elasticsearch:8.15.3}"
# 宿主机/VM 重启后需要自动拉起：否则容器会以 Exited(255) 躺在 docker ps -a 里，
# 表现却是记忆模块连不上 http://localhost:9200（本次实测踩过）。
RESTART="${ES_RESTART:-unless-stopped}"
BASE="http://localhost:$PORT"
PROBE_INDEX="agent_memory_license_probe"

if docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
  echo "[es-up] 容器 $NAME 已在运行，跳过启动"
else
  if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "[es-up] 启动已存在的容器 $NAME"
    docker start "$NAME" >/dev/null
    # 旧容器可能没有重启策略，这里补一次（幂等）
    docker update --restart "$RESTART" "$NAME" >/dev/null 2>&1 || true
  else
    echo "[es-up] 创建容器 $NAME ($IMAGE)"
    docker run -d --name "$NAME" \
      --restart "$RESTART" \
      -p "$PORT:9200" \
      -e "discovery.type=single-node" \
      -e "xpack.security.enabled=false" \
      -e "xpack.security.enrollment.enabled=false" \
      -e "ES_JAVA_OPTS=${ES_JAVA_OPTS:--Xms1g -Xmx1g}" \
      -e "cluster.routing.allocation.disk.threshold_enabled=false" \
      "$IMAGE" >/dev/null
  fi
fi

echo -n "[es-up] 等待 $BASE 就绪 "
for _ in $(seq 1 60); do
  if curl -fsS -m 2 "$BASE/_cluster/health" >/dev/null 2>&1; then
    echo "OK"
    break
  fi
  echo -n "."
  sleep 2
done
if ! curl -fsS -m 5 "$BASE/_cluster/health" >/dev/null 2>&1; then
  echo
  echo "[es-up] 120s 内未就绪，请查看：docker logs $NAME" >&2
  exit 1
fi
curl -fsS "$BASE/_cluster/health" | tr ',' '\n' | grep -E '"status"|"number_of_nodes"' | tr -d ' '

# 单节点集群默认 1 副本永远分配不出来，索引会一直停在 yellow。
# 注意：ES 8.15 拒绝在集群设置里写 index.number_of_replicas / archival.index.number_of_replicas
# （报 "not recognized"），所以改用组合索引模板，只作用于记忆相关索引。
# HybridMemoryStore.ensureIndex() 不设置副本数，模板会自动套用。
if curl -fsS -m 10 -X PUT "$BASE/_index_template/agentcode_memory" \
    -H 'Content-Type: application/json' \
    -d '{"index_patterns":["agent_memory*"],"priority":10,"template":{"settings":{"number_of_replicas":0}}}' >/dev/null; then
  echo "[es-up] 已套用索引模板：agent_memory* 副本数=0（单节点保持 green）"
else
  echo "[es-up] 警告：索引模板套用失败，索引可能停留在 yellow（不影响读写功能）" >&2
fi

# ---------- license ----------
CURRENT="$(curl -fsS -m 5 "$BASE/_license" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("license", {}).get("type", "unknown"))' 2>/dev/null \
  || echo unknown)"
echo "[es-up] 当前 license=$CURRENT"
case "$CURRENT" in
  trial|platinum|enterprise)
    echo "[es-up] license 已含 RRF/kNN 能力"
    ;;
  *)
    echo "[es-up] basic license 下 RRF/kNN 会 403，激活 30 天 trial"
    if curl -fsS -m 20 -X POST "$BASE/_license/start_trial?acknowledge=true&type=trial" >/dev/null; then
      echo "[es-up] trial 已激活（30 天后过期，过期重跑本脚本即可）"
    else
      echo "[es-up] 警告：trial 激活返回失败（trial 每个集群只能激活一次，重复调用即 403），继续做能力探测" >&2
    fi
    ;;
esac

# ---------- 能力探测：真实跑一次 BM25 + kNN + rrf ----------
curl -fsS -m 10 -X DELETE "$BASE/$PROBE_INDEX" >/dev/null 2>&1 || true
if ! curl -fsS -m 10 -X PUT "$BASE/$PROBE_INDEX" \
    -H 'Content-Type: application/json' \
    -d '{"mappings":{"properties":{"content":{"type":"text"},"content_vector":{"type":"dense_vector","dims":2,"index":true,"similarity":"cosine"},"type":{"type":"keyword"}}}}' >/dev/null; then
  echo "[es-up] 探测索引创建失败" >&2
  exit 1
fi
curl -fsS -m 10 -X POST "$BASE/$PROBE_INDEX/_bulk" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary '{"index":{"_id":"p1"}}
{"content":"pnpm 偏好","content_vector":[1.0,0.0],"type":"USER"}
' >/dev/null
# 注意：_refresh 不接受 body，带 -d '{}' 会 400
curl -fsS -m 10 -X POST "$BASE/$PROBE_INDEX/_refresh" >/dev/null

RESP="$(curl -fsS -m 10 -X POST "$BASE/$PROBE_INDEX/_search" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"bool":{"must":[{"match":{"content":"pnpm"}}],"filter":[{"term":{"type":"USER"}}]}},"knn":{"field":"content_vector","query_vector":[0.9,0.1],"k":5,"num_candidates":20,"filter":[{"term":{"type":"USER"}}]},"rank":{"rrf":{"rank_constant":60,"rank_window_size":100}},"size":5}' \
  || true)"

curl -fsS -m 10 -X DELETE "$BASE/$PROBE_INDEX" >/dev/null 2>&1 || true

if echo "$RESP" | grep -q '"_id":"p1"'; then
  echo "[es-up] BM25 + kNN + rank.rrf 能力可用（HybridMemoryStore 依赖的两条检索腿都通）"
else
  echo "[es-up] 混排能力探测失败，原始响应：$RESP" >&2
  echo "[es-up] 多半是 license 问题：basic license 不支持 RRF/近似 kNN" >&2
  exit 1
fi

echo "[es-up] OK（无认证）。记忆索引 agent_memory 会在首次 save/search 时自动创建"
