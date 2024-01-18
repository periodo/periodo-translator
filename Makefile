GRADLE_VERSION := 8.4
JARS := daemon/build/libs/daemon-all.jar server/build/libs/server-all.jar

HOST ?= http://localhost:8080

all: $(JARS)

$(JARS):
	./gradlew -q shadowJar

.PHONY: clean superclean update_wrapper mkdirs run_daemon test_daemon \
run_server put_ttl get_ttl put_csv get_csv stage publish

clean:
	rm -f $(JARS)

superclean: clean
	rm -rf .gradle .classpath .project .settings bin build

update_wrapper:
	gradle help --warning-mode=all
	gradle wrapper --gradle-version $(GRADLE_VERSION)

mkdirs:
	rm -rf data
	mkdir -p data/to_ttl
	mkdir -p data/to_csv
	mkdir -p data/err
	mkdir -p data/out

run_daemon: mkdirs
	IDLE_TIMEOUT=600 \
	TO_TTL_DIR=data/to_ttl \
	TO_CSV_DIR=data/to_csv \
	ERROR_DIR=data/err \
	OUTPUT_DIR=data/out \
	./gradlew :daemon:run

test_daemon: mkdirs
	mkdir -p data/expect
	curl -L https://n2t.net/ark:/99152/p0dataset.jsonld > data/expect/dataset.jsonld
	curl -L https://n2t.net/ark:/99152/p0dataset.ttl > data/expect/dataset.ttl
	curl -L https://n2t.net/ark:/99152/p0dataset.csv > data/expect/dataset.csv
	cp data/expect/dataset.jsonld data/to_ttl/
	cp data/expect/dataset.jsonld data/to_csv/
	IDLE_TIMEOUT=30 \
	TO_TTL_DIR=data/to_ttl \
	TO_CSV_DIR=data/to_csv \
	ERROR_DIR=data/err \
	OUTPUT_DIR=data/out \
	./gradlew :daemon:run
	[ -f data/out/dataset.ttl ] || echo "TTL conversion failed"
	[ -f data/out/dataset.csv ] || echo "CSV conversion failed"
	[ -z "$$(ls -A data/err)" ] || \
	(echo "Errors in data/err:" && ls data/err && exit 1)

run_server: mkdirs
	TO_TTL_DIR=data/to_ttl \
	TO_CSV_DIR=data/to_csv \
	ERROR_DIR=data/err \
	OUTPUT_DIR=data/out \
	./gradlew :server:run

put_ttl: dataset.jsonld
	curl -i -X PUT \
	--data-binary @$< \
	-H "Content-Type: application/ld+json; charset=utf-8" \
	'$(HOST)/dataset.ttl'

get_ttl:
	curl -i -X GET \
	'$(HOST)/dataset.ttl'

put_csv: dataset.jsonld
	curl -i -X PUT \
	--data-binary @$< \
	-H "Content-Type: application/ld+json; charset=utf-8" \
	'$(HOST)/dataset.csv'

get_csv:
	curl -i -X GET \
	'$(HOST)/dataset.csv'

stage: APP_NAME = periodo-translator-dev
stage: APP_CONFIG = fly.stage.toml

publish: APP_NAME = periodo-translator
publish: APP_CONFIG = fly.publish.toml

stage publish: clean $(JARS)
	fly deploy \
	--config $(APP_CONFIG) \
	--vm-size=performance-1x \
	--ha=false
	@echo "\nSending test request...\n"
	@curl -i \
	-X PUT \
	-H 'content-type: application/ld+json; charset=UTF-8' \
	-d '{"@context":{"k":"http://ex.org/k"},"k":"v"}' \
	http://$(APP_NAME).flycast/test.ttl \
	&& sleep 2 \
	&& echo && echo \
	&& curl -i http://$(APP_NAME).flycast/test.ttl
