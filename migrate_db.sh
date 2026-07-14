#!/bin/bash
set -e

# ======================================================
# Axion11 - Local DB → Cloud SQL Migration Script
# Supports: Docker container, local mysqldump, or .sql file
# ======================================================

PROJECT_ID="axion11"               # CHANGE THIS to your GCP Project ID
REGION="us-central1"
DB_INSTANCE_NAME="axion11-mysql-db"
DB_NAME="visualopsdb"
DB_USER="axion11user"
DB_PASSWORD="changeme123"          # CHANGE to match deploy_gcp.sh

# Local MySQL credentials
LOCAL_DB_NAME="visualopsdb"
LOCAL_DB_USER="root"
LOCAL_DB_PASSWORD="rootpassword"               # CHANGE to your local root password (DB_PASSWORD in .env)
LOCAL_HOST="localhost"
LOCAL_PORT="3306"
LOCAL_CONTAINER="visualops-mysql"

BUCKET_NAME="${PROJECT_ID}-axion11-db-migration"
DUMP_FILE="/tmp/visualops_data.sql"

echo "======================================================"
echo " Axion11 DB Migration: Local → Cloud SQL"
echo " Target:  $DB_INSTANCE_NAME ($DB_NAME)"
echo "======================================================"

# 1. Dump — try Docker first, fall back to local mysqldump
echo "--> Step 1: Dumping local database..."

DOCKER_AVAILABLE=false
if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
    DOCKER_AVAILABLE=true
fi
DOCKER_AVAILABLE=false

if $DOCKER_AVAILABLE && docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${LOCAL_CONTAINER}$"; then
    echo "Using Docker container '$LOCAL_CONTAINER'..."
    docker exec $LOCAL_CONTAINER \
        mysqldump -u$LOCAL_DB_USER -p$LOCAL_DB_PASSWORD \
        --single-transaction --routines --triggers \
        $LOCAL_DB_NAME > $DUMP_FILE
elif command -v mysqldump &>/dev/null; then
    echo "Docker not available — using local mysqldump on $LOCAL_HOST:$LOCAL_PORT..."
    if [ -z "$LOCAL_DB_PASSWORD" ]; then
        mysqldump -u$LOCAL_DB_USER \
            -h $LOCAL_HOST -P $LOCAL_PORT \
            --skip-ssl \
            --single-transaction --routines --triggers \
            $LOCAL_DB_NAME > $DUMP_FILE
    else
        mysqldump -u$LOCAL_DB_USER -p$LOCAL_DB_PASSWORD \
            -h $LOCAL_HOST -P $LOCAL_PORT \
            --skip-ssl \
            --single-transaction --routines --triggers \
            $LOCAL_DB_NAME > $DUMP_FILE
    fi
else
    echo "ERROR: Neither Docker nor mysqldump is available."
    echo "Options:"
    echo "  1. Start Docker Desktop and run: docker-compose -f backend/docker-compose.yml up -d"
    echo "  2. Install MySQL client: brew install mysql-client"
    echo "  3. Place an existing dump at $DUMP_FILE and re-run this script"
    exit 1
fi

echo "Dump saved to $DUMP_FILE ($(wc -l < $DUMP_FILE) lines)"

# 2. Create GCS bucket if it doesn't exist
echo "--> Step 2: Ensuring GCS bucket exists..."
if ! gcloud storage buckets describe gs://$BUCKET_NAME --project=$PROJECT_ID >/dev/null 2>&1; then
    gcloud storage buckets create gs://$BUCKET_NAME \
        --project=$PROJECT_ID \
        --location=$REGION
    echo "Created bucket gs://$BUCKET_NAME"
else
    echo "Bucket gs://$BUCKET_NAME already exists."
fi

# 3. Upload dump to GCS
echo "--> Step 3: Uploading dump to GCS..."
gcloud storage cp $DUMP_FILE gs://$BUCKET_NAME/visualops_data.sql
echo "Uploaded to gs://$BUCKET_NAME/visualops_data.sql"

# 4. Grant Cloud SQL service account read access to the bucket
echo "--> Step 4: Granting Cloud SQL service account access to bucket..."
SERVICE_ACCOUNT=$(gcloud sql instances describe $DB_INSTANCE_NAME \
    --project=$PROJECT_ID \
    --format="value(serviceAccountEmailAddress)")
echo "Cloud SQL service account: $SERVICE_ACCOUNT"
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME \
    --member="serviceAccount:${SERVICE_ACCOUNT}" \
    --role="roles/storage.objectViewer" 2>/dev/null || true

# 5. Import into Cloud SQL
echo "--> Step 5: Importing dump into Cloud SQL (this may take a minute)..."
gcloud sql import sql $DB_INSTANCE_NAME gs://$BUCKET_NAME/visualops_data.sql \
    --database=$DB_NAME \
    --project=$PROJECT_ID \
    -q

# 6. Verify
echo "--> Step 6: Verifying import..."
gcloud sql connect $DB_INSTANCE_NAME \
    --user=$DB_USER \
    --project=$PROJECT_ID << EOF
USE $DB_NAME;
SELECT 'users' AS tbl, COUNT(*) AS cnt FROM users
UNION ALL
SELECT 'projects', COUNT(*) FROM projects
UNION ALL
SELECT 'batches', COUNT(*) FROM batches;
EOF

echo "======================================================"
echo " Migration Complete!"
echo " Data from '$LOCAL_DB_NAME' is now in Cloud SQL."
echo "======================================================"
