package org.masymos.cli;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The one place the CLI decides how an {@link HttpClient} is made.
 *
 * <p>Every command that talks to the MaSyMoS service builds its client through {@link #client()}, so the
 * connect and request timeouts are declared once instead of in each command. A request that is given
 * {@link #REQUEST_TIMEOUT} cannot hang a command for ever when the service accepts a connection and then
 * stops answering.
 *
 * <p>The redirect policy is deliberately not configured here: it stays whatever the JDK default gives,
 * which is what the commands had before this class existed. Do not add {@code followRedirects} - it
 * would change the commands' behaviour.
 *
 * <p>The request builder exists for the same reason as the timeouts: the shared secret is added in one
 * place, so no command can forget it.
 */
final class ServiceHttp {
    /**
     * The shared secret the service requires, when it requires one (decision 48, finding {@code
     * C04-3}). {@code --token} or {@code MASYMOS_SERVICE_TOKEN} sets it; {@code null} means no header.
     */
    static final String TOKEN_HEADER = "X-MaSyMoS-Token";

    /** How long a connection attempt may take before the command gives up. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** How long one request may take, from connect to the last body byte, before the command gives up. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private ServiceHttp() {}

    /**
     * The client for the default connect timeout.
     *
     * @return a new client with {@link #CONNECT_TIMEOUT} as its connect timeout and the JDK default
     *     redirect policy
     */
    static HttpClient client() {
        return client(CONNECT_TIMEOUT);
    }

    /**
     * The client for the connect timeout the root command was given.
     *
     * @param connectTimeout the connect timeout to use
     * @return a new client with that connect timeout and the JDK default redirect policy
     */
    static HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /**
     * The client for a command that runs on a command line: its connect timeout is the one the root
     * command declares.
     *
     * @param spec the running command's spec, or {@code null} outside picocli
     * @return a new client with the declared connect timeout, or the default one without a root command
     */
    static HttpClient client(CommandSpec spec) {
        return client(connectTimeout(spec));
    }

    /**
     * The connect timeout the root command declares - {@code --connect-timeout} - or {@link
     * #CONNECT_TIMEOUT} when the command runs outside a command line.
     */
    static Duration connectTimeout(CommandSpec spec) {
        var root = rootCommand(spec);
        return root == null ? CONNECT_TIMEOUT : Duration.ofSeconds(Math.max(1, root.connectTimeoutSeconds));
    }

    /**
     * The request timeout the root command declares - {@code --request-timeout} - or {@link
     * #REQUEST_TIMEOUT} when the command runs outside a command line. A request that is given it cannot
     * hang a command for ever when the service accepts a connection and then stops answering.
     */
    static Duration requestTimeout(CommandSpec spec) {
        var root = rootCommand(spec);
        return root == null ? REQUEST_TIMEOUT : Duration.ofSeconds(Math.max(1, root.requestTimeoutSeconds));
    }

    /**
     * The builder every command builds its request on. The caller still sets the URI, the timeout and the
     * method; the shared secret - when the root command was given one - is added here, so no command can
     * forget it and a new command cannot ship without it.
     */
    static HttpRequest.Builder requestBuilder(CommandSpec spec) {
        var builder = HttpRequest.newBuilder();
        var token = token(spec);
        return token == null || token.isBlank() ? builder : builder.header(TOKEN_HEADER, token);
    }

    /**
     * The secret the root command declares - {@code --token}, or {@code MASYMOS_SERVICE_TOKEN} - or
     * {@code null} outside a command line.
     */
    static String token(CommandSpec spec) {
        var root = rootCommand(spec);
        return root == null ? null : root.token;
    }

    /** The root command of the line {@code spec} belongs to, or {@code null} when there is none. */
    private static MaSyMoSCommand rootCommand(CommandSpec spec) {
        if (spec == null || spec.root() == null) {
            return null;
        }
        return spec.root().userObject() instanceof MaSyMoSCommand root ? root : null;
    }
}
