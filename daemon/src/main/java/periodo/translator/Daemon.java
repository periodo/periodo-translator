package periodo.translator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotException;

public class Daemon {

    private static String JSONLD_SUFFIX = ".jsonld";
    private static String TTL_SUFFIX = ".ttl";
    private static String CSV_SUFFIX = ".csv";
    private static String TMP_PREFIX = "translator.Daemon";

    private static Path TO_TTL_DIR = getConfiguredPath("TO_TTL_DIR");
    private static Path TO_CSV_DIR = getConfiguredPath("TO_CSV_DIR");
    private static Path ERROR_DIR = getConfiguredPath("ERROR_DIR");
    private static Path OUTPUT_DIR = getConfiguredPath("OUTPUT_DIR");

    private static Query CSV_QUERY = QueryFactory
        .create(readString(getResourceStream("/periods-as-csv.rq")));

    public static void main(final String[] args)
        throws InterruptedException, IOException {


        while (true) {
            final Path inputPath = getOldestFile(Arrays.asList(TO_TTL_DIR, TO_CSV_DIR));

            if (inputPath != null) {
                final Path[] paths = getOutputAndErrorPaths(inputPath);
                final Path outputPath = paths[0];
                final Path errorPath = paths[1];
                Files.deleteIfExists(outputPath);
                Files.deleteIfExists(errorPath);

                translate(inputPath, outputPath, errorPath);
            }
            Thread.sleep(1000);
        }
    }

    private static Path getOldestFile(final List<Path> dirs) {

        final List<Path> result = dirs.stream()
            .flatMap(dir -> listFiles(dir))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(JSONLD_SUFFIX))
            .sorted(lastModifiedComparator)
            .limit(1)
            .collect(Collectors.toList());

        if (result.size() == 0) {
            return null;
        } else {
            return result.get(0);
        }
    }

    private static Path[] getOutputAndErrorPaths(final Path inputPath) {
        if (! inputPath.toString().endsWith(JSONLD_SUFFIX)) {
            err("unexpected input path: %s".formatted(inputPath));
        }
        if (inputPath.startsWith(TO_TTL_DIR)) {
            return getOutputAndErrorPaths(inputPath, TO_TTL_DIR, TTL_SUFFIX);
        } else if (inputPath.startsWith(TO_CSV_DIR)) {
            return getOutputAndErrorPaths(inputPath, TO_CSV_DIR, CSV_SUFFIX);
        } else {
            throw new RuntimeException("unexpected input path: %s".formatted(inputPath));
        }
    }

    private static Path[] getOutputAndErrorPaths(
        final Path inputPath,
        final Path inputDir,
        final String suffix) {

        final Path relativeInputPath = inputDir.relativize(inputPath);
        final Path relativeOutputPath = relativeInputPath
            .resolveSibling(changeSuffix(inputPath.getFileName(), suffix));
        return new Path[] {
            OUTPUT_DIR.resolve(relativeOutputPath),
            ERROR_DIR.resolve(relativeInputPath)
        };
    }

    private static void translate(final Path input, final Path output, final Path error) {
        try {
            log("Reading %s".formatted(input));
            final Model model = RDFDataMgr.loadModel(input.toUri().toString());
            final String outputPath = output.toString();

            final Path tmp = Files.createTempFile(TMP_PREFIX, null);
            try (final OutputStream tmpStream = Files.newOutputStream(tmp)) {
                if (outputPath.endsWith(TTL_SUFFIX)) {
                    RDFDataMgr.write(tmpStream, model, RDFFormat.TURTLE);
                } else if (outputPath.endsWith(CSV_SUFFIX)) {
                    writeToCSV(tmpStream, model);
                } else {
                    err("unexpected output path: %s".formatted(output));
                }
            }
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
            log("Wrote %s".formatted(output));
            try {
                Files.deleteIfExists(input);
            } catch (final IOException e) {
                log("Deleting %s failed:".formatted(input));
                e.printStackTrace();
            }
        } catch (final IOException|RiotException e) {
            log("Translation failed:");
            e.printStackTrace();
            try {
                Files.move(input, error, StandardCopyOption.REPLACE_EXISTING);
                log("See %s".formatted(error));
            } catch (final IOException ee) {
                log("Moving %s to %s failed:".formatted(input, error));
                ee.printStackTrace();
            }
        }
    }

    private static void writeToCSV(final OutputStream outputStream, final Model model) {
        final ResultSet results = QueryExecutionFactory
            .create(CSV_QUERY, model).execSelect();
        ResultSetFormatter.outputAsCSV(outputStream, results);
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

    private static Path changeSuffix(final Path filename, final String newSuffix) {
        final String fname = filename.toString();
        final String newFname = fname.substring(0, fname.lastIndexOf('.')) + newSuffix;
        return Path.of(newFname);
    }

    private static Comparator<Path> lastModifiedComparator =
        (p1, p2) -> Long.compare(p1.toFile().lastModified(),
                                 p2.toFile().lastModified());

    private static Stream<Path> listFiles(final Path dir) {
        try {
            return Files.list(dir);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readString(final InputStream input) {
        try (final Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").next();
        }
    }

    private static InputStream getResourceStream(final String name) {
        return Daemon.class.getResourceAsStream(name);
    }

    private static void log(final String message) {
        System.err.printf("[%s] <daemon> %s%n", Instant.now(), message);
    }

    private static void err(final String msg) {
        throw new RuntimeException(msg);
    }
}
