#!/bin/sh
# One-time: Earshot's database on native Homebrew Postgres 17 + pgvector, port 5435 (owner
# decision D-5, 2026-10-24: no Docker VM; Homebrew's pgvector is built for Postgres 17 and 18).
#
#   scripts/setup-postgres.sh                 empty database (Flyway creates the tables)
#   scripts/setup-postgres.sh backup.dump     restore a pg_dump -Fc file into it
set -e
PG=/opt/homebrew/opt/postgresql@17/bin
brew list postgresql@17 >/dev/null 2>&1 || brew install postgresql@17
brew list pgvector >/dev/null 2>&1 || brew install pgvector
CONF=/opt/homebrew/var/postgresql@17/postgresql.conf
sed -i '' -E "s/^#?port = [0-9]+/port = 5435/; s/^#?listen_addresses = .*/listen_addresses = 'localhost'/" "$CONF"
brew services restart postgresql@17 >/dev/null
until "$PG/pg_isready" -q -h 127.0.0.1 -p 5435; do sleep 1; done
"$PG/psql" -p 5435 -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'earshot'" | grep -q 1 ||
  "$PG/psql" -p 5435 -d postgres -c "CREATE ROLE earshot LOGIN PASSWORD 'earshot'"
"$PG/psql" -p 5435 -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'earshot'" | grep -q 1 ||
  "$PG/psql" -p 5435 -d postgres -c "CREATE DATABASE earshot OWNER earshot"
# Extensions need a superuser; the earshot role owns everything else.
"$PG/psql" -p 5435 -d earshot -c "CREATE EXTENSION IF NOT EXISTS vector" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm"
if [ -n "$1" ]; then
  # Two "must be owner of extension" errors (extension comments) are expected and harmless.
  "$PG/pg_restore" -p 5435 -d earshot --no-owner --role=earshot "$1" || true
fi
echo "Postgres ready: jdbc:postgresql://127.0.0.1:5435/earshot"
