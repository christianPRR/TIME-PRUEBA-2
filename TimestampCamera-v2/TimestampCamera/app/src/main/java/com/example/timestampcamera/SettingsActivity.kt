package com.example.timestampcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var logoStatus: TextView

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) saveLogo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        buildScreen()
    }

    private fun buildScreen() {
        addTitle("Ajustes del sello")

        addSpinner("Posición del sello", Prefs.POSITIONS, prefs.position) { prefs.position = it }
        addSeek("Tamaño del texto", 1, 10, prefs.textSizeLevel) { prefs.textSizeLevel = it }
        addSeek("Opacidad del fondo (%)", 0, 100, prefs.bgOpacity) { prefs.bgOpacity = it }
        addSpinner("Color del texto", Prefs.COLOR_NAMES, prefs.colorIndex) { prefs.colorIndex = it }

        val now = Date()
        val examples = Prefs.DATE_FORMATS.map {
            SimpleDateFormat(it, Locale.getDefault()).format(now)
        }
        addSpinner("Formato de fecha y hora", examples, prefs.dateFormatIndex) {
            prefs.dateFormatIndex = it
        }

        addTitle("Datos que se muestran")
        addCheck("Dirección escrita (calle y ciudad)", prefs.showAddress) { prefs.showAddress = it }
        addCheck("Coordenadas GPS", prefs.showCoords) { prefs.showCoords = it }
        addCheck("Altitud", prefs.showAltitude) { prefs.showAltitude = it }
        addCheck("Brújula (rumbo)", prefs.showCompass) { prefs.showCompass = it }
        addCheck("Mini mapa (necesita internet)", prefs.showMap) { prefs.showMap = it }
        addCheck("Logo", prefs.showLogo) { prefs.showLogo = it }

        addTitle("Textos fijos")
        addEdit("Nombre del proyecto", prefs.project) { prefs.project = it }
        addEdit("Usuario", prefs.user) { prefs.user = it }

        addTitle("Logo")
        logoStatus = addLabel(logoText())
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pick = Button(this).apply {
            text = "Elegir logo"
            transformationMethod = null
            setOnClickListener { pickLogo.launch("image/*") }
        }
        val remove = Button(this).apply {
            text = "Quitar logo"
            transformationMethod = null
            setOnClickListener {
                File(filesDir, "logo.png").delete()
                logoStatus.text = logoText()
            }
        }
        buttons.addView(pick)
        buttons.addView(remove)
        root.addView(buttons)
    }

    private fun logoText(): String =
        if (File(filesDir, "logo.png").exists()) "Logo cargado" else "Sin logo"

    private fun saveLogo(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp: Bitmap? = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp == null) {
                Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_LONG).show()
                return
            }
            FileOutputStream(File(filesDir, "logo.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            logoStatus.text = logoText()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar el logo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Ayudas para armar la pantalla ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addTitle(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(20), 0, dp(6))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(tv)
    }

    private fun addLabel(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(10), 0, dp(2))
        }
        root.addView(tv)
        return tv
    }

    private fun addSpinner(label: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) {
        addLabel(label)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(selected.coerceIn(0, options.size - 1))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onChange(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(sp)
    }

    private fun addSeek(label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val tv = addLabel("$label: $value")
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (value - min).coerceIn(0, max - min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                tv.text = "$label: $v"
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(sb)
    }

    private fun addCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val cb = CheckBox(this)
        cb.text = label
        cb.isChecked = value
        cb.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        root.addView(cb)
    }

    private fun addEdit(label: String, value: String, onChange: (String) -> Unit) {
        addLabel(label)
        val et = EditText(this)
        et.setText(value)
        et.setSingleLine(true)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        root.addView(et)
    }
}
