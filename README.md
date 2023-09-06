# periodo-translator

Service for translating PeriodO JSON-LD to Turtle and CSV.

It consists of two programs: an HTTP server and a translator daemon.

Configuration is handled via environment variables.

The daemon runs in a loop looking for `.jsonld` files in the configured
`TO_TTL_DIR` and `TO_CSV_DIR` and writing the results to `OUTPUT_DIR`.
If the file cannot be translated, the problematic JSON-LD is written
to `ERROR_DIR`.

The server handles submitting (via `PUT`) JSON-LD files and serving
Turtle and CSV files.

Typical interaction:

* `PUT` JSON-LD to `/dataset.ttl`
* Response status should be `202 Accepted`
* `GET` Turtle from `/dataset.ttl`
* If translation is still pending, return `202 Accepted`
* If translation succeeded, return the translated data with `200 OK`
* If translation failed, return `404 Not Found` with the message `Translation failed`
* If translation never started (because no JSON-LD has been submitted yet),
  return `404 Not Found` with the message `Not found`
