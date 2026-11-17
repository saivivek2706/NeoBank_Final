#!/bin/bash
# Database-per-service: each microservice owns its own schema and nobody else
# is allowed to query it directly - all cross-service reads go through that
# service's API or its published Kafka events. This is what makes services
# independently deployable (you can change auth-service's schema without
# coordinating with account-service).
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE neobank_auth;
    CREATE DATABASE neobank_account;
    CREATE DATABASE neobank_transaction;
    CREATE DATABASE neobank_notification;
    CREATE DATABASE neobank_fraud;
    CREATE DATABASE neobank_compliance;
    CREATE DATABASE neobank_customer;
EOSQL

