package com.example.screencast

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.button.MaterialButton

class MainActivity : FragmentActivity() {
    private lateinit var castContext: CastContext

    private var mediaServer: MediaServerService? = null
    private var pickedVideo: Uri? = null
    private var pickedName: String? = null

    private val serverConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mediaServer = (binder as? MediaServerService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaServer = null
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedVideo = uri
        pickedName = displayNameOf(uri)
        showPickedVideo()
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        castContext = CastContext.getSharedInstance(this)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext,
            findViewById<MediaRouteButton>(R.id.google_cast_button)
        )

        bindService(
            Intent(this, MediaServerService::class.java),
            serverConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        // Unbind only. Once started in the foreground the service outlives this activity, which is
        // what lets the screen turn off without cutting the stream.
        unbindService(serverConnection)
        super.onDestroy()
    }

    fun openCastSettings(@Suppress("UNUSED_PARAMETER") view: View) {
        // Android owns Miracast discovery and pairing; this opens its Cast panel.
        val castIntent = Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.ACTION_CAST_SETTINGS
            } else {
                ACTION_WIFI_DISPLAY_SETTINGS
            }
        )

        try {
            startActivity(castIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cast_settings_unavailable, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun castVideo(@Suppress("UNUSED_PARAMETER") view: View) {
        val url = findViewById<EditText>(R.id.media_url).text.toString().trim()
        val uri = Uri.parse(url)
        if (url.isEmpty() || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, R.string.invalid_media_url, Toast.LENGTH_LONG).show()
            return
        }

        load(url, contentTypeFor(uri), uri.lastPathSegment ?: getString(R.string.media_title))
    }

    fun pickLocalVideo(@Suppress("UNUSED_PARAMETER") view: View) {
        pickVideo.launch(arrayOf("video/*"))
    }

    fun castLocalVideo(@Suppress("UNUSED_PARAMETER") view: View) {
        val uri = pickedVideo ?: run {
            Toast.makeText(this, R.string.pick_video_first, Toast.LENGTH_LONG).show()
            return
        }

        val server = mediaServer ?: run {
            Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_LONG).show()
            return
        }

        if (LocalMediaServer.wifiAddress() == null) {
            Toast.makeText(this, R.string.wifi_required, Toast.LENGTH_LONG).show()
            return
        }

        val url = server.publish(uri) ?: run {
            Toast.makeText(this, R.string.local_serve_failed, Toast.LENGTH_LONG).show()
            return
        }

        if (!load(url, contentResolver.getType(uri) ?: "video/mp4", pickedName.orEmpty())) return

        askForNotifications()
        ContextCompat.startForegroundService(this, MediaServerService.startIntent(this, pickedName))
        findViewById<TextView>(R.id.local_video_name).setText(R.string.local_serving)
    }

    /** Sends [url] to the connected receiver. Returns false when no session is connected. */
    private fun load(url: String, contentType: String, title: String): Boolean {
        val remoteMediaClient = castContext.sessionManager.currentCastSession?.remoteMediaClient
        if (remoteMediaClient == null) {
            Toast.makeText(this, R.string.connect_google_cast_first, Toast.LENGTH_LONG).show()
            return false
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(
                MediaMetadata.KEY_TITLE,
                title.ifBlank { getString(R.string.media_title) }
            )
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()

        remoteMediaClient.load(MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).build())
        return true
    }

    /** The stop control lives in the notification, so ask before the service posts it. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showPickedVideo() {
        findViewById<TextView>(R.id.local_video_name).apply {
            text = pickedName ?: getString(R.string.local_video_selected)
            visibility = View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.cast_local_button).isEnabled = true
    }

    private fun displayNameOf(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun contentTypeFor(uri: Uri): String = when {
        uri.lastPathSegment.orEmpty().endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        uri.lastPathSegment.orEmpty().endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
        else -> "video/mp4"
    }

    private companion object {
        const val ACTION_WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"
    }
}
