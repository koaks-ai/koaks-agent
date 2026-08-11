package org.koaks.agent.config

internal fun parsePositiveInt(
    value: String,
    name: String,
): Int {
    val parsed =
        value.toIntOrNull()
            ?: throw CliException("$name must be a positive integer.")
    if (parsed <= 0) throw CliException("$name must be a positive integer.")
    return parsed
}

internal fun parseDouble(
    value: String,
    name: String,
): Double = value.toDoubleOrNull() ?: throw CliException("$name must be a number.")

public fun String.nonBlank(name: String): String = trim().takeIf { it.isNotEmpty() } ?: throw CliException("$name cannot be blank.")

public fun String.toBooleanFlag(): Boolean = trim().lowercase() in setOf("1", "true", "yes", "y", "on")

public fun String?.toBooleanFlagOrFalse(): Boolean = this?.toBooleanFlag() ?: false
