package com.example.timestampcamera

import android.content.Context
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.Locale

/** Ajustes guardados de la app (SharedPreferences). */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var position: Int
        get() = sp.getInt("position", 4)
        set(v) { sp.edit().putInt("position", v).apply() }

    var textSizeLevel: Int
        get() = sp.getInt("textSizeLevel", 4)
        set(v) { sp.edit().putInt("textSizeLevel", v).apply() }

    var bgOpacity: Int
        get() = sp.getInt("bgOpacity", 55)
        set(v) { sp.edit().putInt("bgOpacity", v).apply() }

    var colorIndex: Int
        get() = sp.getInt("colorIndex", 0)
        set(v) { sp.edit().putInt("colorIndex", v).apply() }

    var dateFormatIndex: Int
        get() = sp.getInt("dateFormatIndex", 0)
        set(v) { sp.edit().putInt("dateFormatIndex", v).apply() }

    var showAddress: Boolean
        get() = sp.getBoolean("showAddress", true)
        set(v) { sp.edit().putBoolean("showAddress", v).apply() }

    var showCoords: Boolean
        get() = sp.getBoolean("showCoords", true)
        set(v) { sp.edit().putBoolean("showCoords", v).apply() }

    var showAltitude: Boolean
        get() = sp.getBoolean("showAltitude", false)
        set(v) { sp.edit().putBoolean("showAltitude", v).apply() }

    var showCompass: Boolean
        get() = sp.getBoolean("showCompass", false)
        set(v) { sp.edit().putBoolean("showCompass", v).apply() }

    var showMap: Boolean
        get() = sp.getBoolean("showMap", true)
        set(v) { sp.edit().putBoolean("showMap", v).apply() }

    var showLogo: Boolean
        get() = sp.getBoolean("showLogo", true)
        set(v) { sp.edit().putBoolean("showLogo", v).apply() }

    var project: String
        get() = sp.getString("project", "") ?: ""
        set(v) { sp.edit().putString("project", v).apply() }

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) { sp.edit().putString("user", v).apply() }

    fun textColor(): Int = COLORS[colorIndex.coerceIn(0, COLORS.size - 1)]

    fun dateFormat(): SimpleDateFormat =
        SimpleDateFormat(DATE_FORMATS[dateFormatIndex.coerceIn(0, DATE_FORMATS.size - 1)], Locale.getDefault())

    companion object {
        val POSITIONS = listOf(
            "Abajo a la izquierda",
            "Abajo a la derecha",
            "Arriba a la izquierda",
            "Arriba a la derecha",
            "Abajo, ancho completo"
        )

        val COLOR_NAMES = listOf("Blanco", "Amarillo", "Verde", "Rojo", "Celeste")

        val COLORS = intArrayOf(
            Color.WHITE,
            Color.parseColor("#FFEB3B"),
            Color.parseColor("#76FF03"),
            Color.parseColor("#FF5252"),
            Color.parseColor("#40C4FF")
        )

        val DATE_FORMATS = listOf(
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy hh:mm:ss a",
            "yyyy-MM-dd HH:mm:ss",
            "MM/dd/yyyy hh:mm a",
            "EEE dd MMM yyyy HH:mm"
        )
    }
}
