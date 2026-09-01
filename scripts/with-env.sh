#!/usr/bin/env bash
# 把 .env 中的配置导出为进程环境变量，再执行给定命令。
# Spring Boot 不会自动读取 .env，因此本地跑 AgentCode 需要经这个脚本包装。
#
#   scripts/with-env.sh mvn -o spring-boot:run
#   scripts/with-env.sh java -jar target/agentcode-java-0.0.1-SNAPSHOT.jar
#   ENV_FILE=/path/to/other.env scripts/with-env.sh mvn spring-boot:run
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "缺少 $ENV_FILE，请先：cp .env.example .env 并填入真实配置" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

echo "[with-env] loaded $ENV_FILE" >&2
echo "[with-env] ES_URIS=${ES_URIS:-<unset>} embedding=${DASHSCOPE_EMBEDDING_MODEL:-<unset>} dims=${DASHSCOPE_EMBEDDING_DIMENSIONS:-<unset>}" >&2

exec "$@"
