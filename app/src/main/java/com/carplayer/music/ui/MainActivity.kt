package com.carplayer.music.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.carplayer.music.R
import com.carplayer.music.databinding.ActivityMainBinding
import com.carplayer.music.player.MusicService
import com.carplayer.music.player.PlayerBus
import com.carplayer.music.scanner.BrowserItem
import com.carplayer.music.scanner.MusicIndex
import com.carplayer.music.scanner.Song
import com.carplayer.music.scanner.UsbScanner
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: BrowserAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Carpeta que se esta mostrando. null = raiz (lista de carpetas). */
    private var currentFolder: String? = null

    /** Cola actualmente cargada en el player, para saber que resaltar. */
    private var queue: List<Song> = emptyList()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[readPerm()] == true || hasPerm(readPerm())) {
            startScan()
        } else {
            b.txtStatus.text = getString(R.string.no_permission)
        }
    }

    // ------------------------------------------------------------------ Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        adapter = BrowserAdapter(::onItemClick)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.recycler.setHasFixedSize(true)
        b.recycler.setItemViewCacheSize(12)
        // Lista corta y rapida: no animamos cambios (ahorra CPU en el T3)
        b.recycler.itemAnimator = null

        wireControls()
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(playerListener)
                syncUi(c)
            }
        }, MoreExecutors.directExecutor())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerBus.audioSessionId.collect { id ->
                    if (id != 0 && hasPerm(Manifest.permission.RECORD_AUDIO)) {
                        b.visualizer.attach(id)
                    }
                }
            }
        }
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        b.visualizer.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (currentFolder != null) showRoot() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ Permisos

    private fun readPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val needed = mutableListOf(readPerm(), Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filterNot { hasPerm(it) }
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    // ------------------------------------------------------------------ Escaneo

    private fun startScan() {
        b.progress.visibility = View.VISIBLE
        b.txtStatus.text = getString(R.string.scanning)
        lifecycleScope.launch {
            val songs = UsbScanner.scan(applicationContext)   // Dispatchers.IO adentro
            MusicIndex.publish(songs)
            b.progress.visibility = View.GONE
            b.txtStatus.text = getString(R.string.found_tracks, songs.size, MusicIndex.byFolder.size)
            showRoot()
        }
    }

    // ------------------------------------------------------------------ Navegacion

    private fun showRoot() {
        currentFolder = null
        b.txtFolder.text = getString(R.string.all_folders)
        val items = MusicIndex.byFolder.entries
            .sortedBy { it.key }
            .map { (path, idx) ->
                BrowserItem.Folder(path, File(path).name.ifBlank { path }, idx.size)
            }
        adapter.submitList(items)
    }

    private fun showFolder(path: String) {
        currentFolder = path
        b.txtFolder.text = File(path).name
        val idx = MusicIndex.byFolder[path] ?: IntArray(0)
        val all = MusicIndex.all
        val items = ArrayList<BrowserItem>(idx.size + 1)
        items.add(BrowserItem.UpDir)
        idx.forEachIndexed { i, globalIndex ->
            items.add(BrowserItem.Track(all[globalIndex], i))
        }
        adapter.submitList(items)
    }

    private fun onItemClick(item: BrowserItem) {
        when (item) {
            is BrowserItem.UpDir -> showRoot()
            is BrowserItem.Folder -> showFolder(item.path)
            is BrowserItem.Track -> {
                val folder = currentFolder ?: return
                playQueue(folderSongs(folder), item.indexInQueue, shuffle = false)
            }
        }
    }

    private fun folderSongs(path: String): List<Song> {
        val idx = MusicIndex.byFolder[path] ?: return emptyList()
        val all = MusicIndex.all
        return idx.map { all[it] }
    }

    // ------------------------------------------------------------------ Reproduccion

    /**
     * Carga la cola en el player.
     * El shuffle usa el DefaultShuffleOrder interno de ExoPlayer, que es exactamente
     * una permutacion de indices en memoria: no clona ni reordena los MediaItem.
     */
    private fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        queue = songs

        val items = ArrayList<MediaItem>(songs.size)
        for (s in songs) {
            items.add(
                MediaItem.Builder()
                    .setMediaId(s.path)
                    .setUri(File(s.path).toURI().toString())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.title)
                            .setArtist(s.artist.ifBlank { s.folderName })
                            .setAlbumTitle(s.folderName)
                            .build()
                    )
                    .build()
            )
        }

        c.shuffleModeEnabled = shuffle
        c.repeatMode = Player.REPEAT_MODE_ALL
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    private fun wireControls() {
        b.btnPlay.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        b.btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        b.btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }

        b.btnShuffleAll.setOnClickListener {
            val all = MusicIndex.all
            if (all.isEmpty()) return@setOnClickListener
            playQueue(all, (0 until all.size).random(), shuffle = true)
        }

        b.btnShuffleFolder.setOnClickListener {
            val folder = currentFolder ?: return@setOnClickListener
            val songs = folderSongs(folder)
            if (songs.isEmpty()) return@setOnClickListener
            playQueue(songs, songs.indices.random(), shuffle = true)
        }

        b.btnBrowse.setOnClickListener { showRoot() }
        b.btnRescan.setOnClickListener { startScan() }
    }

    // ------------------------------------------------------------------ Estado del player

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            b.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isPlaying) b.visualizer.start() else b.visualizer.stop()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying(mediaItem)
        }
    }

    private fun syncUi(c: MediaController) {
        b.btnPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateNowPlaying(c.currentMediaItem)
    }

    private fun updateNowPlaying(item: MediaItem?) {
        if (item == null) {
            b.txtTitle.text = getString(R.string.nothing_playing)
            b.txtSubtitle.text = ""
            adapter.playingPath = null
            return
        }
        b.txtTitle.text = item.mediaMetadata.title ?: item.mediaId
        b.txtSubtitle.text = item.mediaMetadata.albumTitle ?: ""
        adapter.playingPath = item.mediaId
    }
}
