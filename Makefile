GRADLE_VERSION := 8.4
JARS := daemon/build/libs/daemon-all.jar server/build/libs/server-all.jar

HOST ?= http://localhost:8080

all: $(JARS)

$(JARS):
	./gradlew -q shadowJar

.PHONY: clean update_wrapper mkdirs run_daemon run_server put_ttl get_ttl put_csv \
get_csv stage publish

clean:
	rm -f $(JARS)

update_wrapper:
	gradle help --warning-mode=all
	gradle wrapper --gradle-version $(GRADLE_VERSION)

mkdirs:
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

stage: APP_CONFIG = fly.stage.toml

publish: APP_CONFIG = fly.publish.toml

stage publish: clean $(JARS)
	fly deploy --config $(APP_CONFIG)
