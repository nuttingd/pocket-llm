#!/usr/bin/env bash
set -euo pipefail

# Generates a release keystore and pushes signing secrets to GitHub.
# Run once after cloning the template. Requires: keytool, gh, base64.

KEYSTORE_FILE="release.jks"
KEY_ALIAS="release"

if [[ -f "$KEYSTORE_FILE" ]]; then
    echo "Keystore $KEYSTORE_FILE already exists. Delete it first to regenerate."
    exit 1
fi

echo "Generating release keystore..."
read -rsp "Keystore password: " STORE_PASSWORD
echo
read -rsp "Confirm password: " STORE_PASSWORD_CONFIRM
echo

if [[ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]]; then
    echo "Passwords do not match."
    exit 1
fi

keytool -genkeypair -v \
    -keystore "$KEYSTORE_FILE" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -alias "$KEY_ALIAS" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$STORE_PASSWORD" \
    -dname "CN=Release"

echo
echo "Pushing secrets to GitHub..."
KEYSTORE_BASE64=$(base64 < "$KEYSTORE_FILE")
gh secret set RELEASE_KEYSTORE_BASE64 --body "$KEYSTORE_BASE64"
gh secret set RELEASE_KEYSTORE_PASSWORD --body "$STORE_PASSWORD"
gh secret set RELEASE_KEY_ALIAS --body "$KEY_ALIAS"
gh secret set RELEASE_KEY_PASSWORD --body "$STORE_PASSWORD"

echo
echo "Creating keystore.properties for local signing..."
cat > keystore.properties <<PROPS
storeFile=$KEYSTORE_FILE
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$STORE_PASSWORD
PROPS

echo
echo "Done."
echo "  GitHub secrets: pushed"
echo "  keystore.properties: created (gitignored)"
echo "  $KEYSTORE_FILE: created (gitignored)"
echo
echo "Keep $KEYSTORE_FILE safe — you need it to sign updates to the same app."
