#!/bin/bash
set -e

# NEW PROJECT (axion11-prod) — migrated from axion11 on 2026-04-23
#
# Secrets below are read from the environment — export them (or `source` a local,
# gitignored .env.deploy file) before running this script. See .env.deploy.example.
PROJECT_ID="axion11-prod"
REGION="us-central1"
ARTIFACT_REPO="axion11-repo"
DB_INSTANCE_NAME="axion11-mysql-db"
DB_USER="axion11user"
DB_NAME="visualopsdb"
GCS_BUCKET_NAME="axion11-prod-assets"
GOOGLE_DRIVE_CLIENT_ID="469711794178-ece32jugi3av3k6125glig1oat8hvops.apps.googleusercontent.com"
GOOGLE_SIGNIN_REDIRECT_URI="https://imagemx.online/oauth/callback/google-signin"
SEED_DEMO_DATA="false"
# Leave empty on first deploy — set after frontend deploys
FRONTEND_CLOUD_RUN_URL="${FRONTEND_CLOUD_RUN_URL:-}"

: "${DB_PASSWORD:?Set DB_PASSWORD in your environment before running this script}"
: "${JWT_SECRET:?Set JWT_SECRET in your environment before running this script}"
: "${GEMINI_API_KEY:?Set GEMINI_API_KEY in your environment before running this script}"
: "${GOOGLE_DRIVE_CLIENT_SECRET:?Set GOOGLE_DRIVE_CLIENT_SECRET in your environment before running this script}"

INSTANCE_CONNECTION_NAME="${PROJECT_ID}:${REGION}:${DB_INSTANCE_NAME}"
BACKEND_IMG="${REGION}-docker.pkg.dev/${PROJECT_ID}/${ARTIFACT_REPO}/backend:latest"

echo "======================================================"
echo " Building & Deploying Axion11 Backend to Cloud Run"
echo " Project: $PROJECT_ID"
echo "======================================================"

PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")
CLOUD_RUN_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

echo "--> Ensuring IAM roles for Cloud Run service account ($CLOUD_RUN_SA)..."

gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${CLOUD_RUN_SA}" \
    --role="roles/cloudsql.client" \
    --condition=None

gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${CLOUD_RUN_SA}" \
    --role="roles/iam.serviceAccountTokenCreator" \
    --condition=None

gcloud storage buckets add-iam-policy-binding gs://$GCS_BUCKET_NAME \
    --member="serviceAccount:${CLOUD_RUN_SA}" \
    --role="roles/storage.objectAdmin"

# Allow public read so frontend can display images via their public URL
gcloud storage buckets add-iam-policy-binding gs://$GCS_BUCKET_NAME \
    --member="allUsers" \
    --role="roles/storage.objectViewer"

echo "--> Building Backend Image..."
gcloud builds submit --tag $BACKEND_IMG --project $PROJECT_ID

CORS_ORIGINS="https://imagemx.online,https://www.imagemx.online"
if [ -n "$FRONTEND_CLOUD_RUN_URL" ]; then
    CORS_ORIGINS="$CORS_ORIGINS,$FRONTEND_CLOUD_RUN_URL"
fi

echo "--> Deploying Backend to Cloud Run..."
gcloud run deploy axion11-backend \
    --image $BACKEND_IMG \
    --region $REGION \
    --project $PROJECT_ID \
    --allow-unauthenticated \
    --memory 4Gi \
    --cpu 2 \
    --timeout 600 \
    --execution-environment gen2 \
    --set-env-vars "^|^SERVER_PORT=8080|DB_URL=jdbc:mysql:///${DB_NAME}?cloudSqlInstance=${INSTANCE_CONNECTION_NAME}&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false|DB_USER=${DB_USER}|DB_PASSWORD=${DB_PASSWORD}|JWT_SECRET=${JWT_SECRET}|JWT_EXPIRATION=86400000|CORS_ORIGINS=${CORS_ORIGINS}|GCS_BUCKET_NAME=${GCS_BUCKET_NAME}|GEMINI_API_KEY=${GEMINI_API_KEY}|GOOGLE_DRIVE_CLIENT_ID=${GOOGLE_DRIVE_CLIENT_ID}|GOOGLE_DRIVE_CLIENT_SECRET=${GOOGLE_DRIVE_CLIENT_SECRET}|GOOGLE_SIGNIN_REDIRECT_URI=${GOOGLE_SIGNIN_REDIRECT_URI}|SEED_DEMO_DATA=${SEED_DEMO_DATA}" \
    --add-cloudsql-instances ${INSTANCE_CONNECTION_NAME} \
    --revision-suffix="$(date +%s)"

# GCS CORS for signed-URL browser uploads
echo "--> Configuring GCS bucket CORS..."
cat > /tmp/gcs-cors.json << 'CORS_EOF'
[{"origin":["https://imagemx.online","https://www.imagemx.online","http://localhost:3000"],"method":["PUT","GET","HEAD"],"responseHeader":["Content-Type","Content-Length","x-goog-resumable"],"maxAgeSeconds":3600}]
CORS_EOF
gcloud storage buckets update gs://$GCS_BUCKET_NAME --cors-file=/tmp/gcs-cors.json --project $PROJECT_ID 2>/dev/null || true

echo "--> Setting max request body size to 512MB..."
gcloud run services update axion11-backend \
    --region $REGION \
    --project $PROJECT_ID \
    --update-revision-annotations "run.googleapis.com/http1-max-request-bytes=536870912" 2>/dev/null || true

BACKEND_URL=$(gcloud run services describe axion11-backend --region $REGION --project $PROJECT_ID --format="value(status.url)")
echo "======================================================"
echo " Backend Deployment Complete!"
echo " Backend URL: $BACKEND_URL"
echo "======================================================"
