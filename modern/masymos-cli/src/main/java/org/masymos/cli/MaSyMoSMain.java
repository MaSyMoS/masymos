package org.masymos.cli;

import picocli.CommandLine;

public final class MaSyMoSMain {
    private MaSyMoSMain() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MaSyMoSCommand()).execute(args);
        System.exit(exitCode);
    }
}
