package dev.allan.workoutapp.data.db

import androidx.room.TypeConverter

/** ASCII unit separator — cannot appear in user text, safe list delimiter. */
private const val SEP = '\u001F'

class Converters {
    @TypeConverter
    fun intListToString(value: List<Int>): String = value.joinToString(",")

    @TypeConverter
    fun stringToIntList(value: String): List<Int> =
        if (value.isEmpty()) emptyList() else value.split(",").map { it.toInt() }

    @TypeConverter
    fun stringListToString(value: List<String>): String = value.joinToString(SEP.toString())

    @TypeConverter
    fun stringToStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(SEP)
}
