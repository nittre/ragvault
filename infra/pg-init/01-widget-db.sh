#!/bin/bash
# widget_db + widget 사용자 생성 — pgvector 최초 초기화 시 1회 실행
# WIDGET_DB_PASSWORD 환경변수로 비밀번호 지정 (기본값: widget)
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'widget') THEN
        CREATE ROLE widget WITH LOGIN PASSWORD '${WIDGET_DB_PASSWORD:-widget}';
      END IF;
    END \$\$;
    CREATE DATABASE widget_db OWNER widget;
    GRANT ALL PRIVILEGES ON DATABASE widget_db TO widget;
EOSQL

# superuser 권한으로 widget_db에 vector extension 설치 (일반 유저는 CREATE EXTENSION 불가)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "widget_db" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS vector;
    GRANT ALL ON SCHEMA public TO widget;
EOSQL
