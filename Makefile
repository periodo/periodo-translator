.PHONY: mkdirs run_daemon run_server

mkdirs:
	mkdir -p data/to_ttl
	mkdir -p data/to_csv
	mkdir -p data/err
	mkdir -p data/out

run_daemon: mkdirs
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
	curl -X PUT \
	--data-binary @$< \
	-H "Host: localhost" \
	-H "Content-Type: application/ld+json; charset=utf-8" \
	-D - \
	'http://[0:0:0:0:0:0:0:0]:8080/dataset.ttl'

get_ttl:
	curl -X GET \
	-H "Host: localhost" \
	-D - \
	'http://[0:0:0:0:0:0:0:0]:8080/dataset.ttl'

put_csv: dataset.jsonld
	curl -X PUT \
	--data-binary @$< \
	-H "Host: localhost" \
	-H "Content-Type: application/ld+json; charset=utf-8" \
	-D - \
	'http://[0:0:0:0:0:0:0:0]:8080/dataset.csv'

get_csv:
	curl -X GET \
	-H "Host: localhost" \
	-D - \
	'http://[0:0:0:0:0:0:0:0]:8080/dataset.ttl'
