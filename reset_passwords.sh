#!/bin/bash
set -e

# ======================================================
# Axion11 - Reset all user passwords to "test1234"
# Updates both local MySQL and Cloud SQL
# Encoding: BCrypt (cost 10) — matches Spring Security
# ======================================================

PROJECT_ID="axion11"
DB_INSTANCE_NAME="axion11-mysql-db"
DB_NAME="visualopsdb"
CLOUD_DB_USER="axion11user"

# Local MySQL credentials
LOCAL_DB_USER="root"
LOCAL_DB_PASSWORD="rootpassword"    # CHANGE to match backend/.env DB_PASSWORD
LOCAL_HOST="127.0.0.1"
LOCAL_PORT="3306"

# BCrypt hash of "test1234" (cost factor 10, compatible with Spring BCryptPasswordEncoder)
BCRYPT_HASH='$2b$10$p4dM6.AY.mcy/MfCBV/5luQFVYeI1FVRthmLjVySkZikXwSVYuY2O'

SQL="UPDATE users SET password='${BCRYPT_HASH}';"
VERIFY_SQL="SELECT id, email, name, role FROM users;"

echo "======================================================"
echo " Resetting all user passwords to: test1234"
echo " BCrypt hash: ${BCRYPT_HASH}"
echo "======================================================"

# ── 1. Local MySQL ───────────────────────────────────────
echo ""
echo "--> Updating LOCAL database ($LOCAL_HOST:$LOCAL_PORT/$DB_NAME)..."
mysql -u$LOCAL_DB_USER -p$LOCAL_DB_PASSWORD \
    -h $LOCAL_HOST -P $LOCAL_PORT \
    --skip-ssl \
    $DB_NAME \
    -e "$SQL" && echo "  Done." || echo "  [!] Local update failed — is local MySQL running?"

echo ""
echo "--> Users in LOCAL database:"
mysql -u$LOCAL_DB_USER -p$LOCAL_DB_PASSWORD \
    -h $LOCAL_HOST -P $LOCAL_PORT \
    --skip-ssl \
    $DB_NAME \
    -e "$VERIFY_SQL" 2>/dev/null || echo "  [!] Could not query local database."

# ── 2. Cloud SQL ─────────────────────────────────────────
echo ""
echo "--> Updating CLOUD SQL database ($DB_INSTANCE_NAME/$DB_NAME)..."
gcloud sql connect $DB_INSTANCE_NAME \
    --user=$CLOUD_DB_USER \
    --project=$PROJECT_ID << EOF
USE $DB_NAME;

-- Ensure admin user exists (insert if missing, update password if exists)
INSERT INTO users (email, password, name, role)
VALUES ('admin', '${BCRYPT_HASH}', 'Admin', 'ADMIN')
ON DUPLICATE KEY UPDATE password='${BCRYPT_HASH}', name='Admin', role='ADMIN';

-- Reset all other users' passwords too
UPDATE users SET password='${BCRYPT_HASH}';

SELECT id, email, name, role FROM users;
EOF

echo ""
echo "======================================================"
echo " Done!"
echo " Admin login:  email=admin  password=test1234"
echo " All other users also reset to password: test1234"
echo "======================================================"
