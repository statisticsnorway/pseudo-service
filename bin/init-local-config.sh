#!/usr/bin/env bash

#Create private directory
CONFIG_DIR="private/gcp"
REQ_DIR="doc/requests"

# Create directory
mkdir -p $CONFIG_DIR/sa-keys

#create sa-keys
touch $CONFIG_DIR/sa-keys/dev-dapla-pseudo-service-test-sa-key.json
gcloud secrets versions access "latest" --secret="pseudo-dev-sa-key" --project="staging-dapla-pseudo-6485" >> private/gcp/sa-keys/dev-dapla-pseudo-service-test-sa-key.json

cd $REQ_DIR

cat <<EOL > http-client.env.json
{
  "local": {
    "base_url": "localhost:10210",
    "keycloak_token": "..."
  },
  "staging": {
    "base_url": "localhost:10210",
    "keycloak_token": "..."
  },
  "prod": {
    "base_url": "localhost:10210"
  }
}

EOL

echo "Remember to update the placeholder values in $REQ_DIR/http-client.env.json with your custom keycloak token."