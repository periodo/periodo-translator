FLY_API_HOSTNAME := https://api.machines.dev
APP := $(shell jq -r .app.app_name machines.json)
IMAGE_TAG := $(shell jq -r .machine.config.image machines.json)
JARS := daemon/build/libs/daemon-all.jar server/build/libs/server-all.jar

HOST ?= http://localhost:8080

all: $(JARS)

$(JARS):
	./gradlew -q shadowJar

.PHONY: clean mkdirs run_daemon run_server put_ttl get_ttl put_csv get_csv create_app push_image

clean:
	rm -f $(JARS)

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

# can also just do `fly apps create`
create_app:
	curl -i -X POST \
	-H "Authorization: Bearer $(shell fly auth token)" \
	-H "Content-Type: application/json" \
	-d '$(shell jq -c .app machines.json)' \
	"$(FLY_API_HOSTNAME)/v1/apps"

allocate_ip:
	fly ips allocate-v6 -a $(APP)

build_image: clean $(JARS)
	docker build -t $(IMAGE_TAG) .

push_image: build_image
	docker push $(IMAGE_TAG)

create_machine:
	curl -i -X POST \
	-H "Authorization: Bearer $(shell fly auth token)" \
	-H "Content-Type: application/json" \
	-d '$(shell jq -c .machine machines.json)' \
	"$(FLY_API_HOSTNAME)/v1/apps/$(APP)/machines"
