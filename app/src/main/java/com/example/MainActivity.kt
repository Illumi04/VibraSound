package com.example

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.extractor.MediaExtractor
import com.example.extractor.SearchResult
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VibraSoundTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibraSoundTheme {
                CleanSearchScreen(
                    onStartAudioStream = { url, onStarted, onError ->
                        playAudioStream(url, onStarted, onError)
                    },
                    onTogglePause = { shouldPlay ->
                        togglePlayback(shouldPlay)
                    }
                )
            }
        }
    }

    private fun playAudioStream(
        streamUrl: String,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(streamUrl)
                setOnPreparedListener { mp ->
                    mp.start()
                    onStarted()
                }
                setOnErrorListener { _, what, extra ->
                    onError("Error de reproducción ($what / $extra)")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            onError("No se pudo iniciar el reproductor: ${e.localizedMessage}")
        }
    }

    private fun togglePlayback(shouldPlay: Boolean) {
        try {
            if (shouldPlay) {
                mediaPlayer?.start()
            } else {
                mediaPlayer?.pause()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanSearchScreen(
    onStartAudioStream: (String, () -> Unit, (String) -> Unit) -> Unit = { _, _, _ -> },
    onTogglePause: (Boolean) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var songQuery by remember { mutableStateOf("Thriller") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var activeSong by remember { mutableStateOf<SearchResult?>(null) }
    var isExtractingForUrl by remember { mutableStateOf<String?>(null) }
    var isPlayingAudio by remember { mutableStateOf(false) }

    fun executeSearch() {
        if (songQuery.isBlank()) return
        keyboardController?.hide()
        isSearching = true
        errorMessage = null

        coroutineScope.launch {
            try {
                val results = MediaExtractor.search(songQuery.trim())
                searchResults = results
                if (results.isEmpty()) {
                    errorMessage = "No se encontraron canciones con ese nombre."
                }
            } catch (e: Exception) {
                errorMessage = "Error al buscar: ${e.localizedMessage ?: e.message}"
            } finally {
                isSearching = false
            }
        }
    }

    fun handlePlayClick(song: SearchResult) {
        // Si ya está activa esta canción, alternar play / pausa
        if (activeSong?.url == song.url && isExtractingForUrl == null) {
            val nextPlayState = !isPlayingAudio
            isPlayingAudio = nextPlayState
            onTogglePause(nextPlayState)
            return
        }

        activeSong = song
        isExtractingForUrl = song.url
        errorMessage = null

        coroutineScope.launch {
            try {
                val streamUrl = MediaExtractor.getAudioStreamUrl(song.url)
                if (!streamUrl.isNullOrBlank()) {
                    onStartAudioStream(
                        streamUrl,
                        {
                            isExtractingForUrl = null
                            isPlayingAudio = true
                        },
                        { err ->
                            isExtractingForUrl = null
                            errorMessage = err
                        }
                    )
                } else {
                    isExtractingForUrl = null
                    errorMessage = "No se pudo extraer el audio de la canción."
                }
            } catch (e: Exception) {
                isExtractingForUrl = null
                errorMessage = "Error al reproducir: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(NeonPurple),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "VibraSound",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "VibraSound",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 1. Campo de texto para escribir la canción
            OutlinedTextField(
                value = songQuery,
                onValueChange = { songQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("song_input_field"),
                placeholder = { Text("Escribe una canción (ej. Thriller)", color = TextSecondary) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = NeonPurple
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonPurple,
                    unfocusedBorderColor = Color(0xFF2E2948),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonPurple,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch() })
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Botón morado que dice 'BUSCAR AHORA'
            Button(
                onClick = { executeSearch() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("search_now_button"),
                enabled = !isSearching && songQuery.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8A2BE2),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF4A2075),
                    disabledContentColor = Color(0xFFB39DDB)
                )
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "BUSCANDO...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BUSCAR AHORA",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mensajes de error si los hay
            if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1520)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        fontSize = 12.sp,
                        color = Color(0xFFFFB4AB),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Barra activa de reproducción
            AnimatedVisibility(
                visible = activeSong != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                activeSong?.let { song ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .border(1.dp, NeonPurple, RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(NeonPurple.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isExtractingForUrl == song.url) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = NeonPurple,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isPlayingAudio) Icons.Default.GraphicEq else Icons.Default.Audiotrack,
                                        contentDescription = null,
                                        tint = NeonPurple,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isExtractingForUrl == song.url) "Cargando audio..." else if (isPlayingAudio) "Reproduciendo audio • ${song.artist}" else "En pausa • ${song.artist}",
                                    fontSize = 11.sp,
                                    color = if (isExtractingForUrl == song.url) NeonCyan else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { handlePlayClick(song) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlayingAudio) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Control de reproducción",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Lista de resultados limpios (Título de la canción y Artista) con botón de 'Reproducir'
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (searchResults.isEmpty() && !isSearching && errorMessage == null) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color(0xFF352F4F),
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Escribe el nombre de una canción y pulsa BUSCAR AHORA",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    items(searchResults) { song ->
                        val isThisSongExtracting = isExtractingForUrl == song.url
                        val isThisSongActive = activeSong?.url == song.url
                        val isThisSongPlaying = isThisSongActive && isPlayingAudio

                        SongResultItem(
                            song = song,
                            isExtracting = isThisSongExtracting,
                            isActive = isThisSongActive,
                            isPlaying = isThisSongPlaying,
                            onPlay = { handlePlayClick(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SongResultItem(
    song: SearchResult,
    isExtracting: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) Modifier.border(1.dp, NeonPurple, RoundedCornerShape(14.dp))
                else Modifier
            )
            .testTag("song_item_${song.url.hashCode()}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF201D33)),
                contentAlignment = Alignment.Center
            ) {
                if (song.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = song.artist,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onPlay,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) NeonPurple else Color(0xFF7B2CBF),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("play_button_${song.url.hashCode()}")
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Reproducir",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPlaying) "Pausar" else "Reproducir",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
