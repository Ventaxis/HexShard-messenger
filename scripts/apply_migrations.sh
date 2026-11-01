#!/bin/bash
# HexShard: apply all repo migrations to the live Supabase project via Management API.
# Usage: apply_migrations.sh <SUPABASE_ACCESS_TOKEN> <PROJECT_REF>
# Applies supabase/migrations/*.sql in timestamp order, one file per request,
# aborting on the first error. Management API wraps each query call in a transaction.
set -u
TOKEN="$1"; REF="$2"
API="https://api.supabase.com/v1/projects/$REF/database/query"
cd "$(dirname "$0")/.."

for f in supabase/migrations/*.sql; do
  name=$(basename "$f")
  echo "=== applying $name"
  body=$(python3 -c "
import json,sys
sql=open('$f',encoding='utf-8').read()
print(json.dumps({'query': sql}))")
  resp=$(curl -s --max-time 120 -X POST "$API" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body")
  if [ -z "$resp" ] || echo "$resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
sys.exit(0 if isinstance(d,list) else 1)" 2>/dev/null; then
    echo "    OK"
  else
    echo "    FAILED:"
    echo "$resp" | head -c 600
    echo
    echo "Aborted at $name"
    exit 1
  fi
done
echo "=== all migrations applied"
