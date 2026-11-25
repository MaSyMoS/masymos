package org.masymos.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "masymos",
        mixinStandardHelpOptions = true,
        version = "masymos 2.0.0-SNAPSHOT",
        description = "Modern MaSyMoS command line interface.",
        subcommands = {
                IndexCommand.class,
                QueryCommand.class,
                MaintenanceCommand.class,
                DeleteCommand.class,
                StatsCommand.class,
                AnalyzeCommand.class
        }
)
public class MaSyMoSCommand implements Callable<Integer> {
    /** The base URL used when `--api-url` is absent or holds nothing but slashes. */
    static final String DEFAULT_API_URL = "http://localhost:8080";

    private static final String API_URL_DESCRIPTION = "Base URL of the MaSyMoS service.";

    /**
     * The base URL the subcommands join their `/api/v1/...` path to. Always normalised, so a
     * trailing slash on the option does not produce `//api/v1/...` (which the service answers
     * with 404). Subcommands read this field directly; the normalisation happens here, where the
     * user's value enters, so the join sites stay untouched.
     */
    String apiUrl = DEFAULT_API_URL;

    @Option(names = "--api-url", description = API_URL_DESCRIPTION)
    void apiUrl(String value) {
        apiUrl = normalise(value);
    }

    /**
     * How long a connection attempt may take, in seconds. Every subcommand reads it through {@link
     * ServiceHttp#connectTimeout}, so one declaration bounds every client the CLI builds.
     */
    @Option(names = "--connect-timeout", description = "Seconds to wait for a connection (default: ${DEFAULT-VALUE}).")
    int connectTimeoutSeconds = 5;

    /**
     * How long one request may take, in seconds. Every subcommand reads it through {@link
     * ServiceHttp#requestTimeout}, so a service that accepts a connection and then stops answering
     * cannot hold a command for ever.
     */
    @Option(names = "--request-timeout", description = "Seconds to wait for a response (default: ${DEFAULT-VALUE}).")
    int requestTimeoutSeconds = 60;

    /**
     * The shared secret the service requires, when it requires one (decision 48, finding C04-3). Every
     * subcommand reads it through {@link ServiceHttp#requestBuilder}, so one declaration covers every
     * request the CLI sends. An unset environment variable leaves the field null, which sends no header.
     */
    @Option(names = "--token", description = "Shared secret sent as " + ServiceHttp.TOKEN_HEADER
            + " (default: MASYMOS_SERVICE_TOKEN).")
    String token = System.getenv("MASYMOS_SERVICE_TOKEN");

    /**
     * Strips trailing slashes so that `base + "/api/v1/..."` has exactly one separator. A value
     * consisting only of slashes would normalise to the empty string - an unusable base URL - and
     * falls back to the default instead.
     */
    static String normalise(String value) {
        if (value == null) {
            return DEFAULT_API_URL;
        }
        var normalised = value;
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return normalised.isEmpty() ? DEFAULT_API_URL : normalised;
    }

    @Override
    public Integer call() {
        System.out.printf("MaSyMoS service: %s%n", apiUrl);
        return 0;
    }
}
