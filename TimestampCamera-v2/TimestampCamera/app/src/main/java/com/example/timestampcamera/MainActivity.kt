package com.example.timestampcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var previewView: PreviewView
    private lateinit var infoText: TextView
    private lateinit var noteInput: EditText
    private lateinit var captureButton: Button
    private lateinit var flashButton: Button

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    @Volatile private var lastLocation: Location? = null
    @Volatile private var cachedAddress: String? = null
    @Volatile private var lastGeocodeTime = 0L
    @Volatile private var geocoding = false
    @Volatile private var azimuth = -1f

    private var tileKey = ""
    private var tileBitmap: Bitmap? = null

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val geoExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            maybeRefreshAddress(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val note = noteInput.text.toString().trim()
            infoText.text = buildLines(Date(), lastLocation, cachedAddress, azimuth, note)
                .joinToString("\n")
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) {
                startCamera()
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        previewView = findViewById(R.id.previewView)
        infoText = findViewById(R.id.infoText)
        noteInput = findViewById(R.id.noteInput)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.btnFlash)

        captureButton.setOnClickListener { takePhoto() }

        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }
        flashButton.setOnClickListener { cycleFlash() }

        // Zoom con dos dedos y enfoque con un toque.
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(current * detector.scaleFactor)
                    return true
                }
            })
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val cam = camera ?: return true
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (hasCameraPermission()) {
            startCamera()
            startLocationUpdates()
        } else {
            val perms = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        geoExecutor.shutdown()
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    // ---------- Brújula ----------

    override fun onSensorChanged(event: SensorEvent) {
        val r = FloatArray(9)
        val r2 = FloatArray(9)
        val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Teléfono en posición vertical, como al tomar una foto.
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
        SensorManager.getOrientation(r2, o)
        azimuth = ((Math.toDegrees(o[0].toDouble()) + 360.0) % 360.0).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun cardinal(az: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return dirs[((az + 22.5f) / 45f).toInt() % 8]
    }

    // ---------- Permisos, cámara y ubicación ----------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, capture)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir esa cámara", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "Flash: Auto"
            ImageCapture.FLASH_MODE_ON -> "Flash: Sí"
            else -> "Flash: No"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let {
                        lastLocation = it
                        maybeRefreshAddress(it)
                    }
                    lm.requestLocationUpdates(provider, 2000L, 0f, locationListener)
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Dirección ----------

    private fun maybeRefreshAddress(loc: Location) {
        val now = System.currentTimeMillis()
        if (!prefs.showAddress || geocoding || now - lastGeocodeTime < 15000L) return
        geocoding = true
        val copy = Location(loc)
        geoExecutor.execute {
            val addr = geocode(copy)
            if (addr != null) cachedAddress = addr
            lastGeocodeTime = System.currentTimeMillis()
            geocoding = false
        }
    }

    @Suppress("DEPRECATION")
    private fun geocode(loc: Location): String? {
        return try {
            if (!Geocoder.isPresent()) {
                null
            } else {
                val list = Geocoder(this, Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                if (list != null && list.isNotEmpty()) list[0].getAddressLine(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Texto del sello ----------

    private fun buildLines(
        date: Date,
        loc: Location?,
        address: String?,
        az: Float,
        note: String
    ): List<String> {
        val lines = mutableListOf<String>()
        if (prefs.project.isNotBlank()) lines.add("Proyecto: " + prefs.project.trim())
        if (prefs.user.isNotBlank()) lines.add("Usuario: " + prefs.user.trim())
        lines.add(prefs.dateFormat().format(date))
        if (prefs.showAddress && !address.isNullOrBlank()) lines.add(address)
        if (prefs.showCoords) {
            lines.add(
                if (loc != null) {
                    String.format(Locale.US, "Lat: %.6f  Lon: %.6f", loc.latitude, loc.longitude)
                } else {
                    "Ubicación: buscando..."
                }
            )
        }
        if (prefs.showAltitude && loc != null && loc.hasAltitude()) {
            lines.add(String.format(Locale.US, "Altitud: %.0f m", loc.altitude))
        }
        if (prefs.showCompass && az >= 0f) {
            lines.add("Rumbo: ${az.roundToInt()}° ${cardinal(az)}")
        }
        if (note.isNotBlank()) lines.add(note)
        return lines
    }

    // ---------- Tomar la foto ----------

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // Datos del instante exacto del disparo (hora del sistema del teléfono).
        val date = Date()
        val loc = lastLocation?.let { Location(it) }
        val az = azimuth
        val note = noteInput.text.toString().trim()

        captureButton.isEnabled = false

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val original = image.toBitmap()

                    val address = if (prefs.showAddress && loc != null) {
                        geocode(loc) ?: cachedAddress
                    } else {
                        null
                    }
                    val lines = buildLines(date, loc, address, az, note)
                    val map = if (prefs.showMap && loc != null) fetchMap(loc.latitude, loc.longitude) else null
                    val logo = if (prefs.showLogo) loadLogo() else null

                    val stamped = stamp(original, rotation, lines, map, logo)
                    val saved = saveToGallery(stamped)
                    stamped.recycle()

                    runOnUiThread {
                        captureButton.isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            if (saved) "Foto guardada en Galería (Pictures/TimestampCamera)"
                            else "No se pudo guardar la foto",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        captureButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    captureButton.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Error al capturar: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    private fun loadLogo(): Bitmap? {
        val f = File(filesDir, "logo.png")
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    // ---------- Mini mapa (OpenStreetMap) ----------

    private fun fetchMap(lat: Double, lon: Double): Bitmap? {
        return try {
            val z = 17
            val n = 1 shl z
            val xf = (lon + 180.0) / 360.0 * n
            val latRad = Math.toRadians(lat)
            val yf = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
            val xt = xf.toInt()
            val yt = yf.toInt()

            val tile = getTile(z, xt, yt) ?: return null
            val out = tile.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val px = ((xf - xt) * out.width).toFloat()
            val py = ((yf - yt) * out.height).toFloat()

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawCircle(px, py, 9f, fill)
            canvas.drawCircle(px, py, 9f, ring)

            val attribution = "© OpenStreetMap"
            val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 11f
            }
            val tw = ap.measureText(attribution)
            val strip = Paint().apply { color = Color.argb(180, 255, 255, 255) }
            canvas.drawRect(out.width - tw - 6f, out.height - 16f, out.width.toFloat(), out.height.toFloat(), strip)
            canvas.drawText(attribution, out.width - tw - 3f, out.height - 4f, ap)
            out
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun getTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        val cached = tileBitmap
        if (key == tileKey && cached != null) return cached

        val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", "TimestampCameraApp/1.0 (uso personal)")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return null
            val bmp = BitmapFactory.decodeStream(conn.inputStream) ?: return null
            tileKey = key
            tileBitmap = bmp
            return bmp
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Dibujar el sello ----------

    private class Block(
        val ts: Float,
        val pad: Float,
        val lineH: Float,
        val textH: Float,
        val mapSize: Float,
        val logoW: Float,
        val logoH: Float,
        val blockW: Float,
        val blockH: Float
    )

    private fun computeBlock(paint: Paint, ts: Float, lines: List<String>, hasMap: Boolean, logo: Bitmap?): Block {
        paint.textSize = ts
        val pad = ts * 0.6f
        val lineH = ts * 1.25f
        var textW = 0f
        for (l in lines) textW = max(textW, paint.measureText(l))
        val textH = lineH * lines.size
        val mapSize = if (hasMap) max(textH, ts * 6f) else 0f
        val logoH = if (logo != null) ts * 3f else 0f
        val logoW = if (logo != null) logoH * logo.width / logo.height else 0f

        var blockW = pad + textW + pad
        if (hasMap) blockW += mapSize + pad
        if (logo != null) blockW += logoW + pad
        val inner = max(textH, max(mapSize, logoH))
        return Block(ts, pad, lineH, textH, mapSize, logoW, logoH, blockW, inner + pad * 2)
    }

    private fun stamp(src: Bitmap, rotation: Int, lines: List<String>, map: Bitmap?, logo: Bitmap?): Bitmap {
        var base = src
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            base = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (base !== src) src.recycle()
        }
        val bmp = if (base.isMutable) base else {
            val copy = base.copy(Bitmap.Config.ARGB_8888, true)
            base.recycle()
            copy
        }

        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val canvas = Canvas(bmp)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = prefs.textColor()
            setShadowLayer(3f, 2f, 2f, Color.BLACK)
        }

        var ts = w * (0.015f + prefs.textSizeLevel * 0.005f)
        var b = computeBlock(paint, ts, lines, map != null, logo)
        if (b.blockW > w * 0.98f) {
            ts *= (w * 0.98f / b.blockW)
            b = computeBlock(paint, ts, lines, map != null, logo)
        }

        val position = prefs.position
        val fullWidth = position == 4
        val margin = if (fullWidth) 0f else b.pad
        val blockW = if (fullWidth) w else b.blockW
        val left = if (position == 1 || position == 3) w - blockW - margin else margin
        val top = if (position == 2 || position == 3) margin else h - b.blockH - margin

        val bg = Paint().apply { color = Color.argb(prefs.bgOpacity * 255 / 100, 0, 0, 0) }
        val radius = if (fullWidth) 0f else b.pad
        canvas.drawRoundRect(RectF(left, top, left + blockW, top + b.blockH), radius, radius, bg)

        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val inner = b.blockH - 2 * b.pad
        var x = left + b.pad

        if (map != null) {
            val my = top + b.pad + (inner - b.mapSize) / 2
            canvas.drawBitmap(map, null, RectF(x, my, x + b.mapSize, my + b.mapSize), bitmapPaint)
            x += b.mapSize + b.pad
        }

        val textTop = top + b.pad + (inner - b.textH) / 2
        lines.forEachIndexed { i, text ->
            canvas.drawText(text, x, textTop + b.lineH * (i + 0.8f), paint)
        }

        if (logo != null) {
            val lx = left + blockW - b.pad - b.logoW
            val ly = top + b.pad + (inner - b.logoH) / 2
            canvas.drawBitmap(logo, null, RectF(lx, ly, lx + b.logoW, ly + b.logoH), bitmapPaint)
        }
        return bmp
    }

    private fun saveToGallery(bitmap: Bitmap): Boolean {
        val name = "TS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TimestampCamera")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: return false
        return true
    }
}
