package com.carplayer.music.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.carplayer.music.player.AudioFx
import com.carplayer.music.player.MusicService
import com.carplayer.music.player.PlaybackStore
import com.carplayer.music.player.PlayerBus
import com.carplayer.music.scanner.BrowserItem
import com.carplayer.music.scanner.IndexCache
import com.carplayer.music.scanner.MusicIndex
import com.carplayer.music.scanner.Song
import com.carplayer.music.scanner.UsbScanner
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: BrowserAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Carpeta que se esta mostrando. null = raiz (lista de carpetas). */
    private var currentFolder: String? = null

    /** Ya intentamos restaurar la sesion anterior en esta apertura. */
    private var restored = false

    /** El usuario esta arrastrando la barra: no la pisamos con el avance real. */
    private var userSeeking = false

    private val ui = Handler(Looper.getMainLooper())

    /** Refresco de la barra de progreso: 2 veces por segundo alcanza y sobra. */
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

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
        b.recycler.itemAnimator = null

        // Diagnostico: mantener presionado el ecualizador muestra si esta usando
        // datos de audio reales o el modo sintetico de respaldo.
        b.visualizer.setOnLongClickListener {
            Toast.makeText(this, b.visualizer.debugInfo(), Toast.LENGTH_LONG).show()
            true
        }

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
                maybeRestore()
            }
        }, MoreExecutors.directExecutor())

        ui.post(progressTicker)

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
        ui.removeCallbacks(progressTicker)
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

    // ------------------------------------------------------------------ Escaneo con cache

    /**
     * Mostrar primero, verificar despues:
     *  1) Se pinta el cache al instante (arranque sin espera).
     *  2) Se reescanea el pendrive en segundo plano.
     *  3) Solo si cambio algo, se repinta y se reescribe el cache.
     */
    private fun startScan() {
        lifecycleScope.launch {
            val cached = IndexCache.load(applicationContext)
            if (cached != null) {
                MusicIndex.publish(cached)
                showRoot()
                b.txtStatus.text = getString(
                    R.string.found_tracks, cached.size, MusicIndex.byFolder.size
                )
                maybeRestore()
            } else {
                b.txtStatus.text = getString(R.string.scanning)
            }

            b.progress.visibility = View.VISIBLE
            val fresh = UsbScanner.scan(applicationContext)
            b.progress.visibility = View.GONE

            if (cached == null || !IndexCache.sameContent(cached, fresh)) {
                MusicIndex.publish(fresh)
                IndexCache.save(applicationContext, fresh)
                if (currentFolder == null) showRoot() else showFolder(currentFolder!!)
                b.txtStatus.text = getString(
                    R.string.found_tracks, fresh.size, MusicIndex.byFolder.size
                )
                maybeRestore()
            }
        }
    }

    // ------------------------------------------------------------------ Retomar sesion

    /** Reconstruye la cola guardada y salta al segundo exacto donde se corto la luz. */
    private fun maybeRestore() {
        if (restored) return
        val c = controller ?: return
        if (c.mediaItemCount > 0) {          // ya hay algo sonando, no tocamos nada
            restored = true
            return
        }
        if (MusicIndex.all.isEmpty()) return

        val source = PlaybackStore.source(this) ?: return
        val songs = if (source == PlaybackStore.SOURCE_ALL) {
            MusicIndex.all
        } else {
            folderSongs(source)
        }
        if (songs.isEmpty()) return

        restored = true
        val index = PlaybackStore.index(this).coerceIn(0, songs.size - 1)
        val pos = PlaybackStore.position(this)

        loadQueue(
            songs = songs,
            startIndex = index,
            startPositionMs = pos,
            shuffle = PlaybackStore.shuffle(this),
            autoPlay = PlaybackStore.wasPlaying(this),
            rememberSource = false
        )
        if (source != PlaybackStore.SOURCE_ALL) showFolder(source)
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
                playQueue(folderSongs(folder), item.indexInQueue, false, folder)
            }
        }
    }

    private fun folderSongs(path: String): List<Song> {
        val idx = MusicIndex.byFolder[path] ?: return emptyList()
        val all = MusicIndex.all
        return idx.map { all[it] }
    }

    // ------------------------------------------------------------------ Reproduccion

    private fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String) {
        loadQueue(songs, startIndex, 0L, shuffle, autoPlay = true, rememberSource = true, source = source)
    }

    /**
     * Carga la cola en el player.
     * El shuffle usa el DefaultShuffleOrder interno de ExoPlayer: una permutacion de
     * indices en memoria, sin clonar ni reordenar los MediaItem.
     */
    private fun loadQueue(
        songs: List<Song>,
        startIndex: Int,
        startPositionMs: Long,
        shuffle: Boolean,
        autoPlay: Boolean,
        rememberSource: Boolean,
        source: String = ""
    ) {
        val c = controller ?: return
        if (songs.isEmpty()) return

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

        if (rememberSource) PlaybackStore.setSource(this, source, shuffle)

        c.shuffleModeEnabled = shuffle
        c.repeatMode = Player.REPEAT_MODE_ALL
        c.setMediaItems(items, startIndex, startPositionMs)
        c.prepare()
        if (autoPlay) c.play()
        syncUi(c)
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
            playQueue(all, all.indices.random(), true, PlaybackStore.SOURCE_ALL)
        }

        b.btnShuffleFolder.setOnClickListener {
            val folder = currentFolder ?: return@setOnClickListener
            val songs = folderSongs(folder)
            if (songs.isEmpty()) return@setOnClickListener
            playQueue(songs, songs.indices.random(), true, folder)
        }

        b.btnBrowse.setOnClickListener { showRoot() }
        b.btnRescan.setOnClickListener {
            IndexCache.clear(applicationContext)
            b.txtStatus.text = getString(R.string.scanning)
            lifecycleScope.launch {
                b.progress.visibility = View.VISIBLE
                val fresh = UsbScanner.scan(applicationContext)
                MusicIndex.publish(fresh)
                IndexCache.save(applicationContext, fresh)
                b.progress.visibility = View.GONE
                showRoot()
                b.txtStatus.text = getString(
                    R.string.found_tracks, fresh.size, MusicIndex.byFolder.size
                )
            }
        }

        b.btnEq.setOnClickListener { showEqDialog() }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.txtPosition.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                controller?.seekTo(sb.progress.toLong())
            }
        })
    }

    // ------------------------------------------------------------------ Ecualizador

    private fun showEqDialog() {
        val names = arrayOf(
            getString(R.string.eq_flat),
            getString(R.string.eq_bass),
            getString(R.string.eq_voice),
            getString(R.string.eq_rock)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.eq_title)
            .setSingleChoiceItems(names, PlaybackStore.eqPreset(this)) { dialog, which ->
                AudioFx.apply(applicationContext, which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Progreso

    private fun updateProgress() {
        val c = controller ?: return
        val dur = c.duration
        if (dur <= 0L) {
            b.seekBar.isEnabled = false
            b.txtDuration.text = "--:--"
            return
        }
        b.seekBar.isEnabled = true
        if (b.seekBar.max.toLong() != dur) b.seekBar.max = dur.toInt()
        if (!userSeeking) {
            val pos = c.currentPosition.coerceIn(0L, dur)
            b.seekBar.progress = pos.toInt()
            b.txtPosition.text = formatTime(pos)
        }
        b.txtDuration.text = formatTime(dur)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    // ------------------------------------------------------------------ Estado del player

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            b.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            b.visualizer.setPlaying(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying(mediaItem)
            updateProgress()
        }
    }

    private fun syncUi(c: MediaController) {
        b.btnPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        b.visualizer.setPlaying(c.isPlaying)
        updateNowPlaying(c.currentMediaItem)
        updateProgress()
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
