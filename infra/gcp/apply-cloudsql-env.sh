#!/usr/bin/env bash
# Run in an authenticated Cloud Shell: bash apply-cloudsql-env.sh SERVICE REGION
# Updates the existing service's five DB variables, preserving other vars/secrets.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'Usage: bash %s EXISTING_CLOUD_RUN_SERVICE REGION\n' "$0" >&2
  exit 1
fi
command -v gcloud >/dev/null || { printf 'gcloud is required; run this in Cloud Shell.\n' >&2; exit 1; }
AXOX_SERVICE=$1
AXOX_REGION=$2

# Inspect only configuration metadata, never secret payloads. Do not print the
# complete service JSON: ordinary environment variables can also contain secrets.
gcloud run services describe "$AXOX_SERVICE" \
  --project=my-project-axox --region="$AXOX_REGION" --format=json |
  python3 -c '
import json, sys
service = json.load(sys.stdin)
containers = service["spec"]["template"]["spec"]["containers"]
if len(containers) != 1:
    sys.exit("Multiple containers: select the backend container explicitly in the console.")
container = containers[0]
entries = {item["name"]: item for item in container.get("env", [])}
expected = {
    "SPRING_PROFILES_ACTIVE": "cloudsql",
    "INSTANCE_CONNECTION_NAME": "axox-db-trial:asia-northeast1:free-trial-first-project",
    "SPRING_DATASOURCE_URL": "jdbc:postgresql:///ax",
    "SPRING_DATASOURCE_USERNAME": "axox_app",
    "CLOUD_SQL_IP_TYPES": "PUBLIC",
}
for key, value in expected.items():
    state = "MATCH" if entries.get(key, {}).get("value") == value else "MISSING/DIFFERENT"
    print(key + ": " + state)
password = entries.get("SPRING_DATASOURCE_PASSWORD", {})
if not password.get("valueFrom", {}).get("secretKeyRef"):
    sys.exit("Configure SPRING_DATASOURCE_PASSWORD as a Secret Manager reference first.")
print("Password Secret reference: configured (secret value not read)")
overrides = [key for key in entries if key in (
    "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION", "SPRING_CONFIG_NAME", "SPRING_APPLICATION_JSON"
) or key.startswith("SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES")]
for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS"):
    value = entries.get(key, {}).get("value", "")
    if any(part in value for part in ("spring.profiles.", "spring.config.", "spring.datasource.")):
        overrides.append(key)
args = " ".join(container.get("args", []) + container.get("command", []))
if any(part in args for part in ("spring.profiles.", "spring.config.", "spring.datasource.")):
    overrides.append("container command/args")
if overrides:
    sys.exit("Review settings that can override YAML/env before updating: " + ", ".join(overrides))
print("Latest ready revision: " + service.get("status", {}).get("latestReadyRevisionName", "unknown"))
'

# --update-env-vars preserves JWT, internal tokens, CORS, and Secret references.
# These values select the already packaged Connector, not direct localhost TCP.
gcloud run services update "$AXOX_SERVICE" \
  --project=my-project-axox --region="$AXOX_REGION" \
  --update-env-vars='SPRING_PROFILES_ACTIVE=cloudsql,INSTANCE_CONNECTION_NAME=axox-db-trial:asia-northeast1:free-trial-first-project,SPRING_DATASOURCE_URL=jdbc:postgresql:///ax,SPRING_DATASOURCE_USERNAME=axox_app,CLOUD_SQL_IP_TYPES=PUBLIC'

AXOX_REVISION=$(gcloud run services describe "$AXOX_SERVICE" \
  --project=my-project-axox --region="$AXOX_REGION" \
  --format='value(status.latestCreatedRevisionName)')
printf 'Updated revision: %s\n' "$AXOX_REVISION"
AXOX_QUERY_FILTER="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$AXOX_SERVICE\" AND resource.labels.revision_name=\"$AXOX_REVISION\" AND (textPayload:\"Database connectivity verified: SELECT 1 returned 1 (database=PostgreSQL)\" OR jsonPayload.message:\"Database connectivity verified: SELECT 1 returned 1 (database=PostgreSQL)\")"
AXOX_QUERY_LOG=$(gcloud logging read "$AXOX_QUERY_FILTER" \
  --project=my-project-axox --freshness=30m --limit=5 \
  --format='value(textPayload,jsonPayload.message)')
if [[ -z "$AXOX_QUERY_LOG" ]]; then
  printf 'Environment updated, but SELECT 1 is NOT verified. Check this revision in Cloud Run logs (logs may be delayed).\n' >&2
  exit 2
fi
printf '%s\n' "$AXOX_QUERY_LOG"
