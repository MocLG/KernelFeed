package dev.lukag.lkml.data.local

import androidx.room.TypeConverter

/**
 * `References` is the only collection stored inline.
 *
 * Newline-joining rather than JSON: Message-IDs cannot contain a newline (RFC 5322 folds
 * them away), the encoding is unambiguous, and it avoids a JSON dependency plus a parse
 * on every row read in a list that can be thousands of rows long.
 */
object Converters {

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>?): String = value.orEmpty().joinToString("\n")

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
