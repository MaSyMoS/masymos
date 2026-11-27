package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/**
 * Turns an unreachable-service failure into one readable line (CLI1-1).
 *
 * <p>Every service-backed command declares `throws IOException`, so an unreachable host reaches
 * picocli as an execution exception and its default handler prints a 32-line stack trace whose
 * {@code ConnectException} message names the host but never the base URL that was tried. This
 * handler recognises an {@link IOException} anywhere in the cause chain, prints exactly one line
 * naming the normalised service URL and the underlying message, and returns exit code 1. Every
 * other throwable is handed back to picocli's default handler, so no existing behaviour changes.
 */
final class ServiceUnreachableExceptionHandler implements IExecutionExceptionHandler {
    /** Exit code of a run that could not reach the service; the same code a service error returns. */
    static final int SERVICE_UNREACHABLE_EXIT_CODE = 1;

    /**
     * Runs picocli's default execution-exception behaviour: the message, then the stack trace, on
     * the command line's error writer, and the command's exit code for an execution exception.
     * Reproduced here because {@code IExecutionExceptionHandler} exposes no accessor returning
     * picocli's own default handler. The one deliberate difference is that the flush is left to
     * picocli, which flushes {@code getErr()} after the handler returns.
     */
    private static int printStackAndFail(
            Exception exception, CommandLine commandLine, ParseResult parseResult) {
        var err = commandLine.getErr();
        err.println(exception.getMessage());
        exception.printStackTrace(err);
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    @Override
    public int handleExecutionException(
            Exception exception, CommandLine commandLine, ParseResult parseResult) {
        if (findIoException(exception) != null) {
            var err = new PrintWriter(commandLine.getErr(), true);
            err.println(serviceUnreachableMessage(exception, serviceUrl(commandLine)));
            return SERVICE_UNREACHABLE_EXIT_CODE;
        }
        return printStackAndFail(exception, commandLine, parseResult);
    }

    /** The single line printed when the service could not be reached. */
    static String serviceUnreachableMessage(Throwable throwable, String serviceUrl) {
        var cause = findIoException(throwable);
        var reason = cause.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = cause.getClass().getSimpleName();
        }
        return "cannot reach MaSyMoS service at " + serviceUrl + ": " + reason;
    }

    /** The base URL as the root command normalised it, or the default when it cannot be read. */
    private static String serviceUrl(CommandLine commandLine) {
        var root = commandLine.getCommandSpec().root().userObject();
        if (root instanceof MaSyMoSCommand masymos && masymos.apiUrl != null) {
            return masymos.apiUrl;
        }
        return MaSyMoSCommand.DEFAULT_API_URL;
    }

    private static IOException findIoException(Throwable throwable) {
        var seen = new java.util.HashSet<Throwable>();
        for (var current = throwable; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof IOException ioException) {
                return ioException;
            }
        }
        return null;
    }
}
