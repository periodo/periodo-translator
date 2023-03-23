package periodo.translator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.ExceptionListener;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.MethodNotSupportedException;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.io.HttpRequestHandler;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.PathEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;

public class Server {

    private static int DEFAULT_PORT = 8080;

    private static String JSONLD_SUFFIX = ".jsonld";
    private static String TTL_SUFFIX = ".ttl";
    private static String CSV_SUFFIX = ".csv";
    private static String TMP_PREFIX = "translator.Server";

    private static ContentType APPLICATION_JSONLD = ContentType.create(
        "application/ld+json", StandardCharsets.UTF_8);
    private static ContentType TEXT_TURTLE = ContentType.create(
        "text/turtle", StandardCharsets.UTF_8);
    private static ContentType TEXT_CSV = ContentType.create(
        "text/csv", StandardCharsets.UTF_8);

    private static Path TO_TTL_DIR = getConfiguredPath("TO_TTL_DIR");
    private static Path TO_CSV_DIR = getConfiguredPath("TO_CSV_DIR");
    private static Path ERROR_DIR = getConfiguredPath("ERROR_DIR");
    private static Path OUTPUT_DIR = getConfiguredPath("OUTPUT_DIR");

    public static void main(final String[] args)
        throws IOException, InterruptedException
    {
        if (args.length > 1) {
            printUsage();
            System.exit(1);
        }

        final int port = (args.length == 0) ? DEFAULT_PORT : Integer.parseInt(args[0]);

        final SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final HttpServer server = ServerBootstrap.bootstrap()
            .setListenerPort(port)
            .setSocketConfig(socketConfig)
            .setExceptionListener(new LoggingExceptionListener())
            .register("*", new RequestHandler())
            .create();

        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("HTTP server shutting down");
            server.close(CloseMode.GRACEFUL);
        }));
        log("Listening on port " + port);
        server.awaitTermination(TimeValue.MAX_VALUE);
    }

    private static Path getConfiguredPath(final String var) {
        final String value = System.getenv(var);
        if (value == null) {
            err("%s is not set".formatted(var));
        }
        final Path path = Path.of(value);
        if (! Files.isDirectory(path)) {
            err("%s is not a directory".formatted(path));
        }
        if (! Files.isReadable(path)) {
            err("%s is not readable".formatted(path));
        }
        if (! Files.isWritable(path)) {
            err("%s is not writable".formatted(path));
        }
        if (! Files.isExecutable(path)) {
            err("%s is not executable".formatted(path));
        }
        return path;
    }

    static class RequestHandler implements HttpRequestHandler {

        @Override
        public void handle(
            final ClassicHttpRequest request,
            final ClassicHttpResponse response,
            final HttpContext context)
            throws HttpException, IOException {

            final Method method = Method.normalizedValueOf(request.getMethod());


            final HttpCoreContext coreContext = HttpCoreContext.adapt(context);
            final EndpointDetails endpoint = coreContext.getEndpointDetails();

            log("%s %s from %s".formatted(
                    method,
                    request.getPath(),
                    endpoint.getRemoteAddress()));

            if (Method.PUT == method) {
                handlePUT(request, response);
            } else if (Method.GET == method) {
                handleGET(request, response);
            } else {
                throw new MethodNotSupportedException(method + " is not allowed");
            }
        }

        private void handlePUT(
            final ClassicHttpRequest request,
            final ClassicHttpResponse response)
            throws HttpException, IOException {

            final String requestPath = getRequestPath(request);

            // determine where to write the JSON-LD file
            Path input = null;
            try {
                input = getInputPath(requestPath);
            } catch (IllegalArgumentException e) {
                send(response, HttpStatus.SC_BAD_REQUEST, e.getMessage());
                return;
            }

            // if a file is there already, there is a Conflict
            if (Files.exists(input)) {
                send(response,
                     HttpStatus.SC_CONFLICT,
                     "/%s is already pending translation"
                     .formatted(requestPath));
                return;
            }

            // make sure there is content
            final HttpEntity entity = request.getEntity();
            if (entity == null) {
                send(response,
                     HttpStatus.SC_BAD_REQUEST,
                     "Missing request body");
                return;
            }

            // make sure the content-type is JSON-LD
            final ContentType contentType = ContentType.parse(entity.getContentType());
            if (contentType == null
                || ! contentType.isSameMimeType(APPLICATION_JSONLD)) {

                send(response,
                     HttpStatus.SC_BAD_REQUEST,
                     "Content-Type must be %s"
                     .formatted(APPLICATION_JSONLD));
                return;
            }

            // write the file
            final Path tmp = Files.createTempFile(TMP_PREFIX, null);
            try (final OutputStream tmpStream = Files.newOutputStream(tmp)) {
                entity.writeTo(tmpStream);
            }
            Files.move(tmp, input, StandardCopyOption.REPLACE_EXISTING);
            log("Wrote to " + input);

            // return Accepted
            send(response, HttpStatus.SC_ACCEPTED, "Accepted");
        }

        private void handleGET(
            final ClassicHttpRequest request,
            final ClassicHttpResponse response)
            throws HttpException, IOException {

            final String requestPath = getRequestPath(request);

            Path input = null;
            try {
                input = getInputPath(requestPath);
            } catch (IllegalArgumentException e) {
                send(response, HttpStatus.SC_BAD_REQUEST, e.getMessage());
                return;
            }

            ContentType contentType = null;
            if (requestPath.endsWith(TTL_SUFFIX)) {
                contentType = TEXT_TURTLE;
            } else if (requestPath.endsWith(CSV_SUFFIX)) {
                contentType = TEXT_CSV;
            } else {
                send(response,
                     HttpStatus.SC_BAD_REQUEST,
                     "Request path must end in %s or %s"
                     .formatted(TTL_SUFFIX, CSV_SUFFIX));
                return;
            }

            final Path output = OUTPUT_DIR.resolve(requestPath);
            final Path error = ERROR_DIR.resolve(requestPath);

            if (Files.exists(output)) {
                if (Files.isRegularFile(output) && Files.isReadable(output)) {
                    send(response, output, contentType);
                } else {
                    send(response,
                         HttpStatus.SC_INTERNAL_SERVER_ERROR,
                         "Cannot read /%s".formatted(requestPath));
                }
            } else if (Files.exists(error)) {
                send(response, HttpStatus.SC_NOT_FOUND, "Translation failed");
            } else if (Files.exists(input)) {
                send(response, HttpStatus.SC_ACCEPTED, "Accepted");
            } else {
                send(response, HttpStatus.SC_NOT_FOUND, "Not found");
            }
        }

        private String getRequestPath(final ClassicHttpRequest request) {
            final String requestPath = request.getPath();
            return requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        }

        private Path getInputPath(final String requestPath) {
            final String inputPath = requestPath.substring(0, requestPath.lastIndexOf('.')) + JSONLD_SUFFIX;
            if (requestPath.endsWith(TTL_SUFFIX)) {
                return TO_TTL_DIR.resolve(inputPath);
            }
            if (requestPath.endsWith(CSV_SUFFIX)) {
                return TO_CSV_DIR.resolve(inputPath);
            }
            throw new IllegalArgumentException(
                "Request path must end in %s or %s".formatted(TTL_SUFFIX, CSV_SUFFIX));
        }

        private void send(final ClassicHttpResponse response, final int status, final String message) {
            final StringEntity entity = new StringEntity(message, ContentType.TEXT_HTML);
            response.setCode(status);
            response.setEntity(entity);
            log(status + " " + message);
        }

        private void send(final ClassicHttpResponse response, final Path path, final ContentType contentType) {
            final PathEntity entity = new PathEntity(path, contentType);
            response.setCode(HttpStatus.SC_OK);
            response.setEntity(entity);
            log(HttpStatus.SC_OK + " " + path);
        }
    }

    private static class LoggingExceptionListener implements ExceptionListener {
        @Override
        public void onError(final Exception e) {
            e.printStackTrace();
        }
        @Override
        public void onError(final HttpConnection conn, final Exception e) {
            if (e instanceof SocketTimeoutException) {
                log("Connection timed out");
            } else if (e instanceof ConnectionClosedException) {
                log(e.getMessage());
            } else {
                e.printStackTrace();
            }
        }
    }

    private static void printUsage() {
        System.err.println("java periodo.translator.Server [<port>]");
    }

    private static void log(final String message) {
        System.err.printf("[%s] <server> %s%n", Instant.now(), message);
    }

    private static void err(final String msg) {
        throw new RuntimeException(msg);
    }
}
