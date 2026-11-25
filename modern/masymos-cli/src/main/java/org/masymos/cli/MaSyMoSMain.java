package org.masymos.cli;

import picocli.CommandLine;

public final class MaSyMoSMain {
    private MaSyMoSMain() {
    }

    public static void main(String[] args) {
        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }

    /**
     * The CLI exactly as `main` runs it: the root command plus the handler that turns an
     * unreachable service into one line instead of a stack trace (CLI1-1). Tests build their
     * command line here, so what they exercise is what a user runs.
     */
    static CommandLine commandLine() {
        return commandLine(CommandLine.defaultFactory());
    }

    /**
     * The same root command line, built with `factory` - the seam a test uses to hand a subcommand
     * a recording sender. {@link CommandLine#defaultFactory()} gives production behaviour.
     */
    static CommandLine commandLine(CommandLine.IFactory factory) {
        var commandLine = new CommandLine(new MaSyMoSCommand(), factory);
        commandLine.setExecutionExceptionHandler(new ServiceUnreachableExceptionHandler());
        return commandLine;
    }
}
