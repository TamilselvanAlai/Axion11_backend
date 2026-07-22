#!/bin/bash
set -e

PROJECT_ID="axion11"          # CHANGE THIS to your GCP Project ID
REGION="us-central1"
ARTIFACT_REPO="axion11-repo"
DB_INSTANCE_NAME="axion11-mysql-db"
DB_USER="axion11user"
DB_NAME="visualopsdb"
# Set this to your frontend Cloud Run URL to include it in CORS (leave empty if unknown)
FRONTEND_CLOUD_RUN_URL="https://axion11-frontend-475413982457.us-central1.run.app"
# GCS bucket for image uploads (create with: gsutil mb -p $PROJECT_ID gs://$GCS_BUCKET_NAME)
GCS_BUCKET_NAME="axion11-assets"

# Secrets are read from the environment — export them (or `source` a local, gitignored
# .env.deploy file) before running this script. See .env.deploy.example.
: "${DB_PASSWORD:?Set DB_PASSWORD in your environment before running this script}"
: "${JWT_SECRET:?Set JWT_SECRET in your environment before running this script}"
: "${GEMINI_API_KEY:?Set GEMINI_API_KEY in your environment before running this script}"

INSTANCE_CONNECTION_NAME="${PROJECT_ID}:${REGION}:${DB_INSTANCE_NAME}"
BACKEND_IMG="${REGION}-docker.pkg.dev/${PROJECT_ID}/${ARTIFACT_REPO}/backend:latest"

echo "======================================================"
echo " Building & Deploying Axion11 Backend to Cloud Run"
echo "======================================================"

# Resolve project number → default Compute Engine service account used by Cloud Run
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")
CLOUD_RUN_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

echo "--> Ensuring IAM roles for Cloud Run service account ($CLOUD_RUN_SA)..."

# Cloud Vision API has no separate predefined role — access is granted via the
# default Compute SA's project Editor role, which GCP assigns automatically.

# Cloud SQL — connect via socket factory
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${CLOUD_RUN_SA}" \
    --role="roles/cloudsql.client" \
    --condition=None

# IAM — allow signing URLs for direct browser-to-GCS uploads
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${CLOUD_RUN_SA}" \
    --role="roles/iam.serviceAccountTokenCreator" \
    --condition=None

# GCS — upload objects to the image assets bucket
gcloud storage buckets add-iam-policy-binding gs://$GCS_BUCKET_NAME \
    --member="serviceAccount:${CLOUD_RUN_SA}" \
    --role="roles/storage.objectCreator"

echo "--> Building Backend Image..."
gcloud builds submit --tag $BACKEND_IMG --project $PROJECT_ID

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
    --set-env-vars "^|^SERVER_PORT=8080|DB_URL=jdbc:mysql:///${DB_NAME}?cloudSqlInstance=${INSTANCE_CONNECTION_NAME}&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false|DB_USER=${DB_USER}|DB_PASSWORD=${DB_PASSWORD}|JWT_SECRET=${JWT_SECRET}|JWT_EXPIRATION=86400000|CORS_ORIGINS=https://imagemx.online,https://www.imagemx.online,${FRONTEND_CLOUD_RUN_URL}|FRONTEND_URL=https://imagemx.online|GCS_BUCKET_NAME=${GCS_BUCKET_NAME}|GEMINI_API_KEY=${GEMINI_API_KEY}" \
    --add-cloudsql-instances ${INSTANCE_CONNECTION_NAME} \
    --revision-suffix="$(date +%s)"

# Configure CORS on GCS bucket for direct browser uploads via signed URLs
echo "--> Configuring GCS bucket CORS..."
cat > /tmp/gcs-cors.json << 'CORS_EOF'
[{"origin":["https://imagemx.online","https://www.imagemx.online","https://axion11-frontend-475413982457.us-central1.run.app","https://axion11-frontend-3rnlo4cmua-uc.a.run.app","http://localhost:3000"],"method":["PUT","GET","HEAD"],"responseHeader":["Content-Type","Content-Length","x-goog-resumable"],"maxAgeSeconds":3600}]
CORS_EOF
gcloud storage buckets update gs://$GCS_BUCKET_NAME --cors-file=/tmp/gcs-cors.json --project $PROJECT_ID 2>/dev/null || true

# Increase max request body size to 512MB on the revision (template-level annotation)
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
