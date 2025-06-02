LOCAL_APP_DIR := private/gcp/sa-keys
LOCAL_APP_SA_KEY_FILE := private/gcp/sa-keys/dev-dapla-pseudo-service-test-sa-key.json
LOCAL_ENV_FILE := doc/requests/http-client.env.json

.PHONY: default
default: | help

.PHONY: build-all
build-all: build-mvn build-docker ## Build all and create docker image

.PHONY: build-mvn
build-mvn: ## Build project and install to you local maven repo
	mvn clean install -Dmaven.test.skip

.PHONY: build-docker
build-docker: ## Build dev docker image
	docker build -t pseudo-service:dev -f Dockerfile .

.PHONY: init-local-config
init-local-config: ## Initialize the private folder to hold your local offline untracked configuration
	@bin/init-local-config.sh

.PHONY: validate-local-config
validate-local-config: ## Validate and echo local app config
	@test -f $(LOCAL_ENV_FILE) || (echo "Missing file: $(LOCAL_ENV_FILE). Make sure to run 'make init-local-config' first." && exit 1)
	@echo "Environment variables ... file://$(realpath $(LOCAL_ENV_FILE))"
	@echo "Application config ...... file://$(realpath $(LOCAL_APP_SA_KEY_FILE))"

.PHONY: run-local
run-local: validate-local-config
	java ${JAVA_OPTS} --enable-preview -Dmicronaut.config.files=conf/application-local.yml  -Dmicronaut.environments=local,local-sid -jar target/pseudo-service-*-SNAPSHOT.jar

.PHONY: release-dryrun
release-dryrun: ## Simulate a release in order to detect any issues
	mvn release:prepare release:perform -Darguments="-Dmaven.deploy.skip=true" -DdryRun=true

.PHONY: help
help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'
