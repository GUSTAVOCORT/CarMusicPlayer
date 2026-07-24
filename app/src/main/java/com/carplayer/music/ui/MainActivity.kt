package com.carplayer.music.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.carplayer.music.scanner.BrowserItem
import com.carplayer.music.scanner.CoverArt
import com.carplayer.music.scanner.IndexCache
import com.carplayer.music.scanner.MusicIndex
import com.carplayer.music.scanner.Song
import com.carplayer.music.scanner.UsbScanner
import com.carplayer.music.view.AudioVisualizerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private companion object {
        const val APP_VERSION = "v4.4"
        // El canal Binder entre pantalla y servicio revienta pasado ~1 MB por
        // transaccion. 400 pistas entran holgadas: son casi 24 horas de musica.
        const val MAX_QUEUE = 400
    }

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

    // --- Modos de pantalla ---
    // 0 normal | 1 sin lista (visualizador ancho) | 2 pantalla completa
    private val setNormal = ConstraintSet()
    private val setWide = ConstraintSet()
    private val setFull = ConstraintSet()
    private var setsReady = false
    private var screenMode = 0

    /** Estamos mostrando resultados de busqueda en vez del explorador. */
    private var searching = false

    /** Contador para ignorar caratulas que llegan tarde tras cambiar de cancion. */
    private var coverToken = 0

    /** Refresco de la barra de progreso: 2 veces por segundo alcanza y sobra. */
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            // El controlador y el escaneo terminan en momentos impredecibles;
            // en vez de depender de quien llegue ultimo, se reintenta hasta lograrlo.
            if (!restored) maybeRestore()
            ui.postDelayed(this, 500)
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) copyBackgroundFromUri(uri)
    }

    /**
     * Copia la imagen elegida a un archivo propio de la app.
     * Asi el fondo sobrevive reinicios sin depender de permisos de URI, que en los
     * head units chinos suelen revocarse o no persistir.
     */
    private fun copyBackgroundFromUri(uri: Uri) {
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dest = File(filesDir, "user_background.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.exists() && dest.length() > 0
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                PlaybackStore.setBgUri(this@MainActivity, "file://${File(filesDir, "user_background.jpg").absolutePath}")
                applyBackground()
            }
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

        // Un toque sobre el ecualizador cambia de modo de pantalla.
        b.visualizer.setOnClickListener { cycleScreenMode() }

        // Diagnostico: mantener presionado el ecualizador muestra si esta usando
        // datos de audio reales o el modo sintetico de respaldo.
        b.visualizer.setOnLongClickListener {
            val applied = b.visualizer.setStyle(b.visualizer.currentStyle() + 1)
            PlaybackStore.setVisualStyle(this, applied)
            val name = when (applied) {
                AudioVisualizerView.STYLE_WAVE -> getString(R.string.style_wave)
                AudioVisualizerView.STYLE_CIRCLE -> getString(R.string.style_circle)
                else -> getString(R.string.style_bars)
            }
            Toast.makeText(this, name + "  ·  " + b.visualizer.debugInfo(), Toast.LENGTH_LONG).show()
            true
        }
        b.visualizer.setStyle(PlaybackStore.visualStyle(this))

        applyPalette(PlaybackStore.palette(this))
        applyBackground()
        applyClock()
        b.visualizer.setNeon(PlaybackStore.neon(this))
        b.clock.neon = PlaybackStore.neon(this)

        wireControls()
        requestPermissions()

        // Los ConstraintSet se capturan cuando la vista ya esta medida
        b.root.post {
            buildConstraintSets()
            applyScreenMode(PlaybackStore.screenMode(this), announce = false)
        }
    }

    // ------------------------------------------------------------------ Modos de pantalla

    private fun buildConstraintSets() {
        if (setsReady) return
        setNormal.clone(b.root)

        // Modo "sin lista": la guia se corre al 100 % y el panel derecho desaparece,
        // asi el visualizador ocupa todo el ancho.
        setWide.clone(b.root)
        setWide.setGuidelinePercent(R.id.gl_split, 1f)
        intArrayOf(
            R.id.btnBrowse, R.id.btnEq, R.id.btnRescan,
            R.id.txtFolder, R.id.txtStatus, R.id.progress, R.id.recycler
        ).forEach { setWide.setVisibility(it, ConstraintSet.GONE) }

        // Modo "pantalla completa": solo el visualizador, de borde a borde.
        setFull.clone(setWide)
        intArrayOf(
            R.id.txtTitle, R.id.txtSubtitle, R.id.seekBar,
            R.id.txtPosition, R.id.txtDuration, R.id.coverArt,
            R.id.btnPlay, R.id.btnPrev, R.id.btnNext,
            R.id.btnShuffleAll, R.id.btnShuffleFolder
        ).forEach { setFull.setVisibility(it, ConstraintSet.GONE) }
        setFull.connect(R.id.visualizer, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
        setFull.connect(R.id.visualizer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
        setFull.connect(R.id.visualizer, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
        setFull.connect(R.id.visualizer, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)

        setsReady = true
    }

    private fun cycleScreenMode() {
        applyScreenMode((screenMode + 1) % 3, announce = true)
    }

    private fun applyScreenMode(mode: Int, announce: Boolean) {
        if (!setsReady) return
        screenMode = mode
        // applyTo directo, sin TransitionManager: una animacion de layout en el T3
        // costaria mas que todo el resto de la pantalla junta.
        when (mode) {
            1 -> setWide.applyTo(b.root)
            2 -> setFull.applyTo(b.root)
            else -> setNormal.applyTo(b.root)
        }
        PlaybackStore.setScreenMode(this, mode)
        b.coverBackground.visibility =
            if (mode == 2 && b.coverBackground.drawable != null) View.VISIBLE else View.GONE
        applyClock()
        if (announce) {
            val name = when (mode) {
                1 -> R.string.mode_wide
                2 -> R.string.mode_full
                else -> R.string.mode_normal
            }
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        }
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
        // Respaldo por si el Service ya fue liquidado por el sistema
        controller?.let { c ->
            if (c.mediaItemCount > 0) {
                PlaybackStore.savePosition(
                    this,
                    c.currentMediaItemIndex,
                    c.currentPosition.coerceAtLeast(0L),
                    c.isPlaying,
                    immediate = true
                )
            }
        }
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
        when {
            screenMode != 0 -> applyScreenMode(0, announce = false)
            searching -> showRoot()
            currentFolder != null -> showRoot()
            else -> super.onBackPressed()
        }
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
                b.txtStatus.text = status(cached.size, R.string.from_cache)
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
                b.txtStatus.text = status(fresh.size, R.string.from_scan)
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

        // Cortacircuitos: si la restauracion anterior quedo a medias, fue porque
        // mato el proceso. Se descarta el estado y se arranca limpio.
        if (PlaybackStore.restorePending(this)) {
            PlaybackStore.forget(this)
            restored = true
            Toast.makeText(this, R.string.restore_discarded, Toast.LENGTH_LONG).show()
            return
        }

        val source = PlaybackStore.source(this) ?: return
        val songs = if (source == PlaybackStore.SOURCE_ALL) {
            MusicIndex.all
        } else {
            folderSongs(source)
        }
        if (songs.isEmpty()) return

        restored = true
        PlaybackStore.beginRestore(this)

        // Se busca por ruta y no por numero de posicion: si la cola se arma
        // distinta, el numero apuntaria a otra cancion.
        val savedPath = PlaybackStore.mediaId(this)
        val byPath = if (savedPath != null) songs.indexOfFirst { it.path == savedPath } else -1
        val index = if (byPath >= 0) byPath else PlaybackStore.index(this).coerceIn(0, songs.size - 1)
        val pos = PlaybackStore.position(this)

        Toast.makeText(
            this,
            getString(R.string.resuming, songs[index].title),
            Toast.LENGTH_SHORT
        ).show()

        loadQueue(
            songs = songs,
            startIndex = index,
            startPositionMs = pos,
            shuffle = PlaybackStore.shuffle(this),
            autoPlay = PlaybackStore.wasPlaying(this),
            rememberSource = false
        )
        if (source != PlaybackStore.SOURCE_ALL) showFolder(source)

        PlaybackStore.endRestore(this)
    }

    // ------------------------------------------------------------------ Navegacion

    private fun showRoot() {
        currentFolder = null
        searching = false
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
        searching = false
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
                // Sirve igual desde el explorador y desde los resultados de busqueda
                val folder = item.song.folderPath
                val songs = folderSongs(folder)
                val idx = songs.indexOfFirst { it.path == item.song.path }.coerceAtLeast(0)
                playQueue(songs, idx, false, folder)
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

        // Recorte de seguridad: nunca se manda una cola gigante por Binder.
        // Se toma una ventana alrededor de la cancion inicial.
        var list = songs
        var index = startIndex.coerceIn(0, songs.size - 1)
        if (songs.size > MAX_QUEUE) {
            val from = (index - MAX_QUEUE / 4).coerceIn(0, songs.size - MAX_QUEUE)
            list = songs.subList(from, from + MAX_QUEUE)
            index -= from
        }

        val items = ArrayList<MediaItem>(list.size)
        for (s in list) {
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
        c.setMediaItems(items, index, startPositionMs)
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
        b.btnRescan.setOnLongClickListener {
            showSettingsDialog()
            true
        }
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
                b.txtStatus.text = status(fresh.size, R.string.from_scan)
            }
        }

        b.btnEq.setOnClickListener { showEqDialog() }
        b.btnEq.setOnLongClickListener {
            showPaletteDialog()
            true
        }
        b.btnSearch.setOnClickListener { showSearchDialog() }

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

    // ------------------------------------------------------------------ Buscador

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            setPadding(40, 30, 40, 30)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 20f
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_title)
            .setView(input)
            .setPositiveButton(R.string.search_go) { _, _ ->
                runSearch(input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Filtrado directo sobre el indice que ya esta en memoria.
     * Con 3.000 pistas son 3.000 comparaciones de texto: menos de 20 ms en el T3,
     * asi que no hace falta base de datos ni indice invertido.
     */
    private fun runSearch(query: String) {
        if (query.isEmpty()) {
            showRoot()
            return
        }
        val q = query.lowercase()
        val results = ArrayList<BrowserItem>(64)

        // Primero las carpetas que coinciden, despues las canciones
        MusicIndex.byFolder.entries
            .filter { File(it.key).name.lowercase().contains(q) }
            .sortedBy { it.key }
            .forEach { (path, idx) ->
                results.add(BrowserItem.Folder(path, File(path).name.ifBlank { path }, idx.size))
            }

        var count = 0
        for (song in MusicIndex.all) {
            if (count >= 300) break     // techo sano: nadie recorre mas de 300 en un auto
            if (song.title.lowercase().contains(q) || song.artist.lowercase().contains(q)) {
                results.add(BrowserItem.Track(song, count))
                count++
            }
        }

        searching = true
        currentFolder = null
        b.txtFolder.text = getString(R.string.search_results, query, results.size)
        adapter.submitList(results)
        if (results.isEmpty()) {
            Toast.makeText(this, R.string.search_empty, Toast.LENGTH_SHORT).show()
        }
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

    // ------------------------------------------------------------------ Ajustes de pantalla

    private fun showSettingsDialog() {
        val options = arrayOf(
            getString(R.string.set_background),
            getString(R.string.set_clock),
            getString(R.string.set_neon)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBackgroundDialog()
                    1 -> showClockDialog()
                    2 -> toggleNeon()
                }
            }
            .show()
    }

    private fun showBackgroundDialog() {
        val opts = arrayOf(
            getString(R.string.bg_from_gallery),
            getString(R.string.bg_from_usb),
            getString(R.string.bg_none)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.set_background)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> pickImage.launch("image/*")
                    1 -> useUsbBackground()
                    2 -> {
                        PlaybackStore.setBgUri(this, null)
                        applyBackground()
                    }
                }
            }
            .show()
    }

    /** Busca fondo.jpg en las raices tipicas del pendrive (mismas del escaner). */
    private fun useUsbBackground() {
        val candidates = listOf(
            "/storage", "/mnt/usb_storage", "/mnt/usbhost", "/mnt/usbhost1",
            "/udisk", "/mnt/media_rw", "/mnt/extsd"
        )
        val names = listOf("fondo.jpg", "fondo.png", "background.jpg")
        lifecycleScope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (root in candidates) {
                    val base = File(root)
                    val dirs = (base.listFiles()?.filter { it.isDirectory } ?: emptyList()) + base
                    for (d in dirs) {
                        for (n in names) {
                            val f = File(d, n)
                            if (f.exists() && f.canRead()) return@withContext f.absolutePath
                        }
                    }
                }
                null
            }
            if (found != null) {
                PlaybackStore.setBgUri(this@MainActivity, "file://$found")
                applyBackground()
            } else {
                Toast.makeText(this@MainActivity, R.string.bg_not_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyBackground() {
        val uri = PlaybackStore.bgUri(this)
        if (uri == null) {
            b.appBackground.setImageDrawable(null)
            b.appBackground.visibility = View.GONE
            b.scrim.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (uri.startsWith("file://")) {
                        // Muestreo para no cargar una foto de 4000px entera en RAM
                        val path = Uri.parse(uri).path ?: return@withContext null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = calcSample(bounds.outWidth, bounds.outHeight, 1024)
                        }
                        BitmapFactory.decodeFile(path, opts)
                    } else {
                        contentResolver.openInputStream(Uri.parse(uri))?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp != null) {
                b.appBackground.setImageBitmap(bmp)
                b.appBackground.visibility = View.VISIBLE
                b.scrim.visibility = View.VISIBLE
            }
        }
    }

    private fun calcSample(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var mx = maxOf(w, h)
        while (mx / 2 >= target) {
            mx /= 2
            sample *= 2
        }
        return sample
    }

    private fun showClockDialog() {
        val opts = arrayOf(
            getString(R.string.clock_off),
            getString(R.string.clock_corner),
            getString(R.string.clock_big)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.set_clock)
            .setSingleChoiceItems(opts, PlaybackStore.clockPos(this)) { dialog, which ->
                PlaybackStore.setClockPos(this, which)
                applyClock()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyClock() {
        val pos = PlaybackStore.clockPos(this)
        b.clock.visibility = if (pos == 0) View.GONE else View.VISIBLE
        // pos 2 = grande: se agranda cuando entra a pantalla completa (ver applyScreenMode)
        val big = pos == 2 && screenMode == 2
        val h = (if (big) 90 else 44) * resources.displayMetrics.density
        b.clock.layoutParams = b.clock.layoutParams.apply { height = h.toInt() }
        b.clock.requestLayout()
    }

    private fun toggleNeon() {
        val on = !PlaybackStore.neon(this)
        PlaybackStore.setNeon(this, on)
        b.visualizer.setNeon(on)
        b.clock.neon = on
        if (on) Toast.makeText(this, R.string.neon_warn, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ Colores

    private fun showPaletteDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.palette_title)
            .setSingleChoiceItems(Palettes.names(), PlaybackStore.palette(this)) { dialog, which ->
                PlaybackStore.setPalette(this, which)
                applyPalette(which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Pinta la interfaz por codigo en vez de recrear la Activity con otro tema:
     * un recreate() obliga a volver a inflar todo el layout y en el T3 se nota.
     */
    private fun applyPalette(index: Int) {
        val p = Palettes.get(index)
        val accent = p.accent
        val tint = ColorStateList.valueOf(accent)

        // Iconos
        listOf(
            b.btnPlay, b.btnPrev, b.btnNext,
            b.btnBrowse, b.btnEq, b.btnRescan, b.btnSearch
        ).forEach { it.setColorFilter(accent, PorterDuff.Mode.SRC_IN) }

        // Iconos de los botones de mezclar (van como drawable del texto)
        listOf(b.btnShuffleAll, b.btnShuffleFolder).forEach { btn ->
            btn.compoundDrawables.filterNotNull().forEach {
                it.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            }
        }

        // Aro del boton principal, construido a mano para poder recolorear el borde
        b.btnPlay.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF10151A.toInt())
            setStroke((3 * resources.displayMetrics.density).toInt(), accent)
        }

        // Barra de progreso: riel bien visible sobre el fondo negro
        b.seekBar.progressTintList = tint
        b.seekBar.thumbTintList = tint
        b.seekBar.progressBackgroundTintList = ColorStateList.valueOf(0xFF3A404D.toInt())

        b.txtSubtitle.setTextColor(accent)
        b.progress.indeterminateTintList = tint

        b.visualizer.setPalette(p.bars, p.reactive)
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

    /** Linea de estado con la version: sirve para saber que APK quedo instalado. */
    private fun status(tracks: Int, originRes: Int): String = getString(
        R.string.status_line,
        APP_VERSION,
        tracks,
        MusicIndex.byFolder.size,
        getString(originRes)
    )

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
            setCover(null)
            return
        }
        b.txtTitle.text = item.mediaMetadata.title ?: item.mediaId
        b.txtSubtitle.text = item.mediaMetadata.albumTitle ?: ""
        adapter.playingPath = item.mediaId
        loadCoverFor(item.mediaId)
    }

    /**
     * Lee la caratula de la cancion actual bajo demanda y con cache.
     * El token evita que una imagen que tardo en cargar pise a la de la
     * cancion siguiente si el usuario paso rapido de tema.
     */
    private fun loadCoverFor(path: String?) {
        val token = ++coverToken
        if (path.isNullOrEmpty()) {
            setCover(null)
            return
        }
        lifecycleScope.launch {
            val bmp = CoverArt.load(applicationContext, path)
            if (token == coverToken) setCover(bmp)
        }
    }

    private fun setCover(bmp: Bitmap?) {
        if (bmp != null) {
            b.coverArt.setImageBitmap(bmp)
            b.coverBackground.setImageBitmap(bmp)
        } else {
            b.coverArt.setImageResource(R.drawable.ic_album_placeholder)
            b.coverBackground.setImageDrawable(null)
        }
        // El fondo solo se ve en pantalla completa y si hay imagen real
        b.coverBackground.visibility =
            if (bmp != null && screenMode == 2) View.VISIBLE else View.GONE
    }
}
