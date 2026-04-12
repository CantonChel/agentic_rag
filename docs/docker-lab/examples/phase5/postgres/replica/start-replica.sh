#!/bin/bash
set -euo pipefail

PGDATA="${PGDATA:-/var/lib/postgresql/data}"

if [ ! -s "${PGDATA}/PG_VERSION" ]; then
  rm -rf "${PGDATA:?}"/*
  until pg_isready -h pg-primary -p 5432 -U "${POSTGRES_USER}" >/dev/null 2>&1; do
    sleep 2
  done
  export PGPASSWORD="${REPLICATION_PASSWORD}"
  until pg_basebackup -h pg-primary -D "${PGDATA}" -U "${REPLICATION_USER}" -Fp -Xs -P -R; do
    echo "pg_basebackup failed, retrying in 3s..."
    rm -rf "${PGDATA:?}"/*
    sleep 3
  done
  chmod 700 "${PGDATA}"
fi

exec postgres -c config_file=/etc/postgresql/postgresql.conf
