#!/bin/bash
# Creates every saga topic plus a "<topic>.DLT" dead-letter topic for it. Idempotent (--if-not-exists).
# Partitions > 1 so consumer groups can scale out; the saga events are keyed by account/transaction id,
# so per-key ordering is preserved within a partition.
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP_SERVER:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"

TOPICS=(
  account.debit.requested account.debit.completed account.debit.failed account.debit.compensate
  account.credit.requested account.credit.completed account.credit.failed
  transaction.completed transaction.failed
  account.opened compliance.account.action
)

for topic in "${TOPICS[@]}"; do
  kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$topic" \
    --partitions "$PARTITIONS" --replication-factor 1
  kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "${topic}.DLT" \
    --partitions 1 --replication-factor 1 --config retention.ms=2592000000
done
echo "Topics ready:"
kafka-topics --bootstrap-server "$BOOTSTRAP" --list
