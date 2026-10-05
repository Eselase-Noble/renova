package io.renova.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/** AI-related options shared by commands. API keys are deliberately not accepted as flag values. */
final class AiOptions {

    @Option(names = "--ai", paramLabel = "PROVIDER",
            description = "AI provider: none, anthropic. Default: setting ai.provider, else none.")
    String provider;

    @Option(names = "--ai-model", paramLabel = "MODEL", description = "Model id. Default: the provider's default.")
    String model;

    @Option(names = "--ai-effort", paramLabel = "LEVEL", description = "low, medium, high, xhigh or max. Default: high.")
    String effort;

    @Option(names = "--env-file", paramLabel = "FILE",
            description = "Read settings and API keys from a .env file, e.g. one you keep outside the repository.")
    Path envFile;
}
