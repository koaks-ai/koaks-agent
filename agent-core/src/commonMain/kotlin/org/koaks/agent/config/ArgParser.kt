package org.koaks.agent.config

public object ArgParser {
    public fun parse(args: Array<String>): CliOptions {
        var options = CliOptions()
        var index = 0

        if (args.firstOrNull() == "init") {
            options = options.copy(command = CliCommand.INIT)
            index = 1
        }

        while (index < args.size) {
            val arg = args[index]
            val option = arg.substringBefore("=")

            options =
                when (option) {
                    "-h", "--help" -> options.copy(showHelp = true)
                    "--force" -> {
                        if (options.command != CliCommand.INIT) {
                            throw CliException("--force is only valid with 'koaks init'.")
                        }
                        options.copy(force = true)
                    }
                    else -> throw CliException("Unknown option: $option.")
                }
            index += 1
        }

        return options
    }
}
