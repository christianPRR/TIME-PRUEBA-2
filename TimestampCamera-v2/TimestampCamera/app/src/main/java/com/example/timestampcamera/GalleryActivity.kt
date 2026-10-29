package com.example.timestampcamera

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private val ids = mutableListOf<Long>()
    private val loader = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var grid: GridView

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = ids.size
        override fun getItem(position: Int): Any = ids[position]
        override fun getItemId(position: Int): Long = ids[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val size = resources.displayMetrics.widthPixels / 3
            val iv = (convertView as? ImageView) ?: ImageView(this@GalleryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
                setBackgroundColor(Color.LTGRAY)
            }
            val id = ids[position]
            iv.tag = id
            iv.setImageBitmap(null)
            loader.execute {
                val bmp = loadThumb(id)
                ui.post {
                    if (iv.tag == id && bmp != null) iv.setImageBitmap(bmp)
                }
            }
            return iv
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = 4
            verticalSpacing = 4
            setPadding(4, 4, 4, 4)
        }
        setContentView(grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> openPhoto(ids[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            sharePhoto(ids[pos])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        loadIds()
        adapter.notifyDataSetChanged()
        if (ids.isEmpty()) {
            Toast.makeText(this, "Aún no hay fotos. Toma una primero.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Toca para abrir. Mantén presionado para compartir.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdown()
    }

    private fun loadIds() {
        ids.clear()
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("TS_%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            while (it.moveToNext()) ids.add(it.getLong(0))
        }
    }

    @Suppress("DEPRECATION")
    private fun loadThumb(id: Long): Bitmap? {
        return try {
            val uri = uriFor(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(300, 300), null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun uriFor(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun openPhoto(id: Long) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(id), "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la foto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePhoto(id: Long) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uriFor(id))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Compartir foto"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir la foto", Toast.LENGTH_SHORT).show()
        }
    }
}
