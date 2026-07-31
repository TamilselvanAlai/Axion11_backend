$ErrorActionPreference = "Stop"

$PROJECT_ID = "axion11-prod"
$REGION = "us-central1"
$ARTIFACT_REPO = "axion11-repo"
$DB_INSTANCE_NAME = "axion11-mysql-db"
$DB_USER = "axion11user"
$DB_NAME = "visualopsdb"
$FRONTEND_CLOUD_RUN_URL = ""
$GCS_BUCKET_NAME = "axion11-prod-assets"
$GOOGLE_DRIVE_CLIENT_ID = "469711794178-ece32jugi3av3k6125glig1oat8hvops.apps.googleusercontent.com"
$GOOGLE_SIGNIN_REDIRECT_URI = "https://imagemx.online/oauth/callback/google-signin"
$SEED_DEMO_DATA = "false"

# DB_PASSWORD, JWT_SECRET, GOOGLE_DRIVE_CLIENT_SECRET, and GEMINI_API_KEY are all deliberately
# NOT in the hardcoded list below: they're left out of the deploy payload entirely (and out of
# $ownedKeys, so they instead flow through the generic .env forwarding loop, which already skips
# blank values) so a blank/missing local value can never overwrite whatever key is already live
# on the Cloud Run service. Set them in the environment (or in .env) to override the live value;
# leave them unset to deploy the new code with the live secrets untouched.

$INSTANCE_CONNECTION_NAME = "${PROJECT_ID}:${REGION}:${DB_INSTANCE_NAME}"
$BACKEND_IMG = "${REGION}-docker.pkg.dev/${PROJECT_ID}/${ARTIFACT_REPO}/backend:latest"

Write-Host "======================================================"
Write-Host " Building & Deploying Axion11 Backend to Cloud Run (Windows)"
Write-Host " Project: $PROJECT_ID"
Write-Host "======================================================"

$PROJECT_NUMBER = (gcloud projects describe $PROJECT_ID --format="value(projectNumber)").Trim()
$CLOUD_RUN_SA = "${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

Write-Host "--> Ensuring IAM roles for Cloud Run service account ($CLOUD_RUN_SA)..."

gcloud projects add-iam-policy-binding $PROJECT_ID `
    --member="serviceAccount:${CLOUD_RUN_SA}" `
    --role="roles/cloudsql.client" `
    --condition=None

gcloud projects add-iam-policy-binding $PROJECT_ID `
    --member="serviceAccount:${CLOUD_RUN_SA}" `
    --role="roles/iam.serviceAccountTokenCreator" `
    --condition=None

gcloud storage buckets add-iam-policy-binding gs://$GCS_BUCKET_NAME `
    --member="serviceAccount:${CLOUD_RUN_SA}" `
    --role="roles/storage.objectAdmin"

gcloud storage buckets add-iam-policy-binding gs://$GCS_BUCKET_NAME `
    --member="allUsers" `
    --role="roles/storage.objectViewer"

Write-Host "--> Building Backend Image..."
gcloud builds submit --tag $BACKEND_IMG --project $PROJECT_ID

$CORS_ORIGINS = "https://imagemx.online,https://www.imagemx.online"
if ($FRONTEND_CLOUD_RUN_URL) {
    $CORS_ORIGINS = "$CORS_ORIGINS,$FRONTEND_CLOUD_RUN_URL"
}
$FRONTEND_URL = "https://imagemx.online"

Write-Host "--> Deploying Backend to Cloud Run..."
$unixTime = [int][datetime]::UtcNow.Subtract((New-Object datetime 1970, 1, 1)).TotalSeconds

# Vars this script computes/owns directly (order matches the historical hardcoded list).
# DB_PASSWORD, JWT_SECRET, GOOGLE_DRIVE_CLIENT_SECRET, GEMINI_API_KEY are intentionally absent —
# see the comment above where they used to be read from the environment. They're picked up below
# by the generic .env forwarding loop instead, if and only if present there.
$envVars = "SERVER_PORT=8080|DB_URL=jdbc:mysql:///${DB_NAME}?cloudSqlInstance=${INSTANCE_CONNECTION_NAME}&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false|DB_USER=${DB_USER}|JWT_EXPIRATION=86400000|CORS_ORIGINS=${CORS_ORIGINS}|FRONTEND_URL=${FRONTEND_URL}|GCS_BUCKET_NAME=${GCS_BUCKET_NAME}|GOOGLE_DRIVE_CLIENT_ID=${GOOGLE_DRIVE_CLIENT_ID}|GOOGLE_SIGNIN_REDIRECT_URI=${GOOGLE_SIGNIN_REDIRECT_URI}|SEED_DEMO_DATA=${SEED_DEMO_DATA}"
$ownedKeys = @("SERVER_PORT","DB_URL","DB_USER","JWT_EXPIRATION","CORS_ORIGINS","FRONTEND_URL","GCS_BUCKET_NAME","GOOGLE_DRIVE_CLIENT_ID","GOOGLE_SIGNIN_REDIRECT_URI","SEED_DEMO_DATA")

# Forward every other var defined in .env verbatim (Google desktop client id, mail, OneDrive,
# etc.) so new config only ever needs to be added in one place instead of being hand-copied
# here too — a var present in .env but missing from this list is exactly what silently broke
# desktop Google sign-in on 2026-07-16.
$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -notmatch '^[A-Z_]+=') { continue }
        $key, $value = $line -split '=', 2
        if ($ownedKeys -contains $key) { continue }
        if ([string]::IsNullOrEmpty($value)) { continue }
        $envVars += "|$key=$value"
    }
}

gcloud run deploy axion11-backend `
    --image $BACKEND_IMG `
    --region $REGION `
    --project $PROJECT_ID `
    --allow-unauthenticated `
    --memory 8Gi `
    --cpu 2 `
    --timeout 600 `
    --execution-environment gen2 `
    --update-env-vars "^|^${envVars}" `
    --add-cloudsql-instances ${INSTANCE_CONNECTION_NAME} `
    --revision-suffix="$unixTime"

Write-Host "--> Configuring GCS bucket CORS..."
$corsJson = '[{"origin":["https://imagemx.online","https://www.imagemx.online","http://localhost:3000"],"method":["PUT","GET","HEAD"],"responseHeader":["Content-Type","Content-Length","x-goog-resumable"],"maxAgeSeconds":3600}]'
$tempCorsFile = Join-Path $env:TEMP "gcs-cors.json"
$corsJson | Out-File -FilePath $tempCorsFile -Encoding utf8 -NoNewline

try {
    gcloud storage buckets update gs://$GCS_BUCKET_NAME --cors-file=$tempCorsFile --project $PROJECT_ID
} catch {
    Write-Warning "Failed to update GCS bucket CORS settings: $_"
}

Write-Host "--> Setting max request body size to 512MB..."
try {
    gcloud run services update axion11-backend `
        --region $REGION `
        --project $PROJECT_ID `
        --update-revision-annotations "run.googleapis.com/http1-max-request-bytes=536870912"
} catch {
    Write-Warning "Failed to set request body size annotation: $_"
}

$BACKEND_URL = (gcloud run services describe axion11-backend --region $REGION --project $PROJECT_ID --format="value(status.url)").Trim()
Write-Host "======================================================"
Write-Host " Backend Deployment Complete!"
Write-Host " Backend URL: $BACKEND_URL"
Write-Host "======================================================"
