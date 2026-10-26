#!/usr/bin/env python3
"""Применение миграций Supabase через Management API.

Зачем не `supabase db push`: CLI для прямых миграций требует пароль от базы
данных. Management API выполняет SQL от имени сервиса, поэтому достаточно
Personal Access Token — того же, что и для деплоя edge-функций.

Использование:
    SUPABASE_ACCESS_TOKEN=... python scripts/apply-migration.py <file.sql> [...]
"""

import json
import os
import sys
import urllib.request

API = "https://api.supabase.com/v1"


def project_ref() -> str:
    ref = os.environ.get("SUPABASE_PROJECT_REF", "").strip()
    if ref:
        return ref
    path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "supabase",
        ".temp",
        "project-ref",
    )
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        raise SystemExit("SUPABASE_PROJECT_REF is not set and project-ref is not found")


def run_sql(ref: str, token: str, sql: str) -> None:
    request = urllib.request.Request(
        f"{API}/projects/{ref}/database/query",
        data=json.dumps({"query": sql}).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise SystemExit(f"SQL failed ({error.code}): {detail}")
    print(f"  applied: {body.strip()[:400]}")


def main() -> int:
    token = os.environ.get("SUPABASE_ACCESS_TOKEN", "").strip()
    if not token:
        print("SUPABASE_ACCESS_TOKEN is not set", file=sys.stderr)
        return 2
    files = sys.argv[1:]
    if not files:
        print("usage: apply-migration.py <file.sql> [...]", file=sys.stderr)
        return 2

    ref = project_ref()
    print(f"project: {ref}")
    for path in files:
        print(f"- {path}")
        with open(path, encoding="utf-8") as handle:
            run_sql(ref, token, handle.read())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
