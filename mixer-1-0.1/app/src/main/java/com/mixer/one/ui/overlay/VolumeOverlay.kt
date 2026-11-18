package com.mixer.one.ui.overlay

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.core.graphics.drawable.toBitmap
import com.mixer.one.data.AudioSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "VolumeOverlay"

/**
 * Nothing-OS-style volume panel.
 *
 * All geometry below is expressed in "reference pixels" measured from native
 * Nothing Phone (4a) screenshots (1224 x 2720). [refScale] converts reference
 * pixels to real pixels, so the layout is exact on any density.
 */
private object G {
    const val UNIT = 174          // pill width, column width, circle diameter
    const val GAP = 24            // gap between stacked elements
    const val PANEL_W = 696       // expanded panel / ringer bar width (4 x UNIT)
    const val PANEL_H = 828       // panel and tall pill height
    const val PANEL_RADIUS = 60   // corner radius of the expanded panel
    const val TRACK_TOP = 72      // top of dot track, from panel top
    const val TRACK_H = 480       // dot track height
    const val DOT_COUNT = 16
    const val DOT_D = 12          // dot diameter
    const val DOT_PITCH = 30
    const val DOT_FIRST = 5.5f    // centre of first dot, from track top
    const val BAR_W = 36          // white level bar width
    const val ICON_CY = 625       // icon centre, from panel top
    const val LABEL_CY = 740      // "MEHR" / chevron centre, from panel top
    const val ICON_BOX = 72       // icon box (glyph is ~60)
    const val SEGMENT = 126       // selected ringer-mode circle
    const val MIXER_MAX_W = 432   // mixer panel max width (2.5 columns)
}

private object NOS {
    val Surface = Color(0xFF303030)
    val Light = Color(0xFFF1F1F1)
    val Icon = Color(0xFFBFBFBF)
    val IconDim = Color(0xFFA2A2A2)
    val Label = Color(0xFFE2E2E2)
    val Dot = Color(0xFF474747)
    val Focus = Color(0xFFD71920)
}

private val LocalRef = compositionLocalOf { 1f }

@Composable
private fun rp(v: Number): Dp {
    val density = LocalDensity.current
    val s = LocalRef.current
    return with(density) { (v.toFloat() * s).toDp() }
}

@Composable
private fun rsp(v: Number): TextUnit {
    val density = LocalDensity.current
    val s = LocalRef.current
    return with(density) { (v.toFloat() * s).toSp() }
}

/** Value + range of one audio stream. */
data class StreamState(val value: Int, val min: Int, val max: Int) {
    val fraction: Float
        get() = if (max > min) ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
}

@Composable
fun VolumeOverlay(
    currentVolume: Int,
    maxVolume: Int,
    isMuted: Boolean = false,
    iconType: String = "MUSIC",
    sessions: List<AudioSession> = emptyList(),
    focusedApp: AudioSession? = null,
    onVolumeChange: (Int) -> Unit = {},
    onSessionVolumeChange: (Int, Float) -> Unit = { _, _ -> },
    onMuteToggle: () -> Unit = {},
    onInteraction: () -> Unit = {},
    onTouchStart: () -> Unit = {},
    onTouchEnd: () -> Unit = {},
    refScale: Float = 1f,
    alarm: StreamState = StreamState(0, 0, 1),
    ring: StreamState = StreamState(0, 0, 1),
    call: StreamState = StreamState(0, 0, 1),
    ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
    onStreamVolumeChange: (Int, Int) -> Unit = { _, _ -> },
    onRingerModeChange: (Int) -> Unit = {}
) {
    CompositionLocalProvider(LocalRef provides refScale) {
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        var expanded by remember { mutableStateOf(false) }
        var mixerOpen by remember { mutableStateOf(false) }
        val ordered = remember(sessions, focusedApp) {
            sessions.sortedByDescending { it.uid == focusedApp?.uid }
        }
        val progress by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "expand"
        )

        val music = StreamState(currentVolume, 0, maxVolume)
        val mediaIcon = when {
            iconType == "BLUETOOTH" -> Icons.Outlined.Bluetooth
            iconType == "HEADPHONE" -> Icons.Outlined.Headphones
            currentVolume == 0 -> Icons.Outlined.MusicOff
            else -> Icons.Outlined.MusicNote
        }
        val mediaTint = if (currentVolume == 0) NOS.IconDim else NOS.Icon

        // Silent mode toggles Do Not Disturb, which Android only allows with DND access.
        // Without it, open the system page where it can be granted once.
        val requestRingerMode: (Int) -> Unit = { mode ->
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mode == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open DND access settings", e)
                }
            } else {
                onRingerModeChange(mode)
            }
        }

        fun streamSetter(stream: Int, state: StreamState): (Float) -> Unit = { f ->
            val v = state.min + (f * (state.max - state.min)).roundToInt()
            if (v != state.value) {
                onInteraction()
                onStreamVolumeChange(stream, v)
            }
        }
        val onMusicFraction: (Float) -> Unit = { f ->
            val v = (f * maxVolume).roundToInt().coerceIn(0, maxVolume)
            if (v != currentVolume) {
                onInteraction()
                onVolumeChange(v)
            }
        }

        Row(
            modifier = Modifier
                .wrapContentSize()
                .trackTouch(onTouchStart, onTouchEnd),
            verticalAlignment = Alignment.Top
        ) {
            // Mixer panel: opens to the LEFT of the system panel, top-aligned with it
            AnimatedVisibility(
                visible = expanded && mixerOpen,
                enter = fadeIn(tween(200)) + expandHorizontally(
                    expandFrom = Alignment.End,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(tween(150)) + shrinkHorizontally(
                    shrinkTowards = Alignment.End,
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                )
            ) {
                Row {
                    Column {
                        Spacer(Modifier.height(rp(G.UNIT + G.GAP)))
                        MixerPanel(
                            sessions = ordered,
                            focusedApp = focusedApp,
                            onSessionVolumeChange = { id, v ->
                                onInteraction()
                                onSessionVolumeChange(id, v)
                            },
                            onTouch = onInteraction
                        )
                    }
                    Spacer(Modifier.width(rp(G.GAP)))
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                TopBar(
                    progress = progress,
                    ringerMode = ringerMode,
                    onMode = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onInteraction()
                        requestRingerMode(it)
                    },
                    onCycle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onInteraction()
                        requestRingerMode(
                            when (ringerMode) {
                                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                                else -> AudioManager.RINGER_MODE_NORMAL
                            }
                        )
                    }
                )
                Spacer(Modifier.height(rp(G.GAP)))

                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        (fadeIn(tween(120)) togetherWith fadeOut(tween(100)))
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    contentAlignment = Alignment.TopEnd,
                    label = "body"
                ) { isExpanded ->
                    if (!isExpanded) {
                        MediaPill(
                            music = music,
                            mediaIcon = mediaIcon,
                            mediaTint = mediaTint,
                            onFraction = onMusicFraction,
                            onTouch = onInteraction,
                            onMuteToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onInteraction()
                                onMuteToggle()
                            },
                            onExpand = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                expanded = true
                                onInteraction()
                            }
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            SystemPanel(
                                alarm = alarm,
                                ring = ring,
                                call = call,
                                music = music,
                                mediaIcon = mediaIcon,
                                mediaTint = mediaTint,
                                onAlarm = streamSetter(AudioManager.STREAM_ALARM, alarm),
                                onRing = streamSetter(AudioManager.STREAM_RING, ring),
                                onCall = streamSetter(AudioManager.STREAM_VOICE_CALL, call),
                                onMusic = onMusicFraction,
                                onMuteToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onInteraction()
                                    onMuteToggle()
                                },
                                onTouch = onInteraction,
                                onMore = {
                                    onInteraction()
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SOUND_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Could not open sound settings", e)
                                    }
                                }
                            )
                        }
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(rp(G.GAP)))
                    MixerButton(
                        active = mixerOpen,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            mixerOpen = !mixerOpen
                            onInteraction()
                        }
                    )
                }
            }
        }
    }
}

// ───────────────────────── Top bar (ringer mode) ─────────────────────────

private fun ringerIcon(mode: Int): ImageVector = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> Icons.Outlined.VolumeOff
    AudioManager.RINGER_MODE_VIBRATE -> Icons.Outlined.Vibration
    else -> Icons.Outlined.VolumeUp
}

@Composable
private fun TopBar(
    progress: Float,
    ringerMode: Int,
    onMode: (Int) -> Unit,
    onCycle: () -> Unit
) {
    val width = rp(G.UNIT) + (rp(G.PANEL_W) - rp(G.UNIT)) * progress
    Box(
        modifier = Modifier
            .size(width, rp(G.UNIT))
            .background(NOS.Surface, RoundedCornerShape(50))
    ) {
        // Collapsed: single circle button showing the current mode
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(rp(G.UNIT), rp(G.UNIT))
                .alpha(1f - progress)
                .then(if (progress < 0.5f) Modifier.tap(onCycle) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            NIcon(ringerIcon(ringerMode), NOS.Light)
        }

        // Expanded: three-segment selector (silent / vibrate / sound)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .alpha(progress)
                .padding(horizontal = rp(87 - G.SEGMENT / 2)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                AudioManager.RINGER_MODE_SILENT,
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_NORMAL
            ).forEach { mode ->
                val selected = mode == ringerMode
                Box(
                    modifier = Modifier
                        .size(rp(G.SEGMENT))
                        .clip(CircleShape)
                        .background(if (selected) NOS.Light else Color.Transparent)
                        .then(if (progress > 0.5f) Modifier.tap { onMode(mode) } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    NIcon(ringerIcon(mode), if (selected) NOS.Surface else NOS.Light)
                }
            }
        }
    }
}

// ───────────────────────── Collapsed media pill ─────────────────────────

@Composable
private fun MediaPill(
    music: StreamState,
    mediaIcon: ImageVector,
    mediaTint: Color,
    onFraction: (Float) -> Unit,
    onTouch: () -> Unit,
    onMuteToggle: () -> Unit,
    onExpand: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(rp(G.UNIT), rp(G.PANEL_H))
            .background(NOS.Surface, RoundedCornerShape(50))
    ) {
        DotSlider(music.fraction, onFraction, onTouch, Modifier.offset(y = rp(G.TRACK_TOP)))
        IconSlot(G.ICON_CY, G.UNIT, Modifier.tap(onMuteToggle)) { NIcon(mediaIcon, mediaTint) }
        IconSlot(G.LABEL_CY, G.UNIT, Modifier.tap(onExpand)) {
            NIcon(Icons.Outlined.ChevronLeft, NOS.Label)
        }
    }
}

// ───────────────────────── Expanded system panel ─────────────────────────

@Composable
private fun SystemPanel(
    alarm: StreamState,
    ring: StreamState,
    call: StreamState,
    music: StreamState,
    mediaIcon: ImageVector,
    mediaTint: Color,
    onAlarm: (Float) -> Unit,
    onRing: (Float) -> Unit,
    onCall: (Float) -> Unit,
    onMusic: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onTouch: () -> Unit,
    onMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(rp(G.PANEL_W), rp(G.PANEL_H))
            .background(NOS.Surface, RoundedCornerShape(rp(G.PANEL_RADIUS)))
    ) {
        Row {
            StreamColumn(alarm, Icons.Outlined.Alarm, NOS.Icon, onAlarm, onTouch)
            StreamColumn(ring, Icons.Outlined.NotificationsActive, NOS.Icon, onRing, onTouch)
            StreamColumn(call, Icons.Outlined.Call, NOS.Icon, onCall, onTouch)
            StreamColumn(music, mediaIcon, mediaTint, onMusic, onTouch, onMuteToggle)
        }
        Box(
            modifier = Modifier
                .offset(y = rp(G.LABEL_CY - 36))
                .size(rp(G.PANEL_W), rp(72))
                .tap(onMore),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "MEHR",
                color = NOS.Label,
                fontSize = rsp(48),
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StreamColumn(
    state: StreamState,
    icon: ImageVector,
    iconTint: Color,
    onFraction: (Float) -> Unit,
    onTouch: () -> Unit,
    onIconClick: (() -> Unit)? = null
) {
    Box(Modifier.size(rp(G.UNIT), rp(G.PANEL_H))) {
        DotSlider(state.fraction, onFraction, onTouch, Modifier.offset(y = rp(G.TRACK_TOP)))
        IconSlot(
            G.ICON_CY, G.UNIT,
            if (onIconClick != null) Modifier.tap(onIconClick) else Modifier
        ) { NIcon(icon, iconTint) }
    }
}

// ───────────────────────── Mixer (per-app volumes) ─────────────────────────

@Composable
private fun MixerButton(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(rp(G.UNIT))
            .background(if (active) NOS.Light else NOS.Surface, CircleShape)
            .tap(onClick),
        contentAlignment = Alignment.Center
    ) {
        NIcon(Icons.Outlined.Equalizer, if (active) NOS.Surface else NOS.Label)
    }
}

@Composable
private fun MixerPanel(
    sessions: List<AudioSession>,
    focusedApp: AudioSession?,
    onSessionVolumeChange: (Int, Float) -> Unit,
    onTouch: () -> Unit
) {
    val width = when {
        sessions.isEmpty() -> 2 * G.UNIT // empty state needs room for the hint
        sessions.size >= 3 -> G.MIXER_MAX_W
        else -> sessions.size * G.UNIT
    }
    val shape = RoundedCornerShape(rp(G.PANEL_RADIUS))
    Box(
        modifier = Modifier
            .size(rp(width), rp(G.PANEL_H))
            .background(NOS.Surface, shape)
    ) {
        if (sessions.isEmpty()) {
            // Empty state: same panel, same label, short hint where the sliders would be
            Box(
                modifier = Modifier
                    .offset(y = rp(G.TRACK_TOP))
                    .size(rp(width), rp(G.TRACK_H))
                    .padding(horizontal = rp(24)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Keine App spielt gerade Audio ab",
                    color = NOS.Icon,
                    fontSize = rsp(40),
                    fontFamily = FontFamily.Default,
                    textAlign = TextAlign.Center
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .horizontalScroll(rememberScrollState())
        ) {
            sessions.forEach { session ->
                key(session.sessionId) {
                    AppColumn(
                        session = session,
                        isFocused = focusedApp?.uid == session.uid,
                        onVolumeChange = { v -> onSessionVolumeChange(session.sessionId, v) },
                        onTouch = onTouch
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .offset(y = rp(G.LABEL_CY - 36))
                .size(rp(width), rp(72)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "APPS",
                color = NOS.Label,
                fontSize = rsp(48),
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AppColumn(
    session: AudioSession,
    isFocused: Boolean,
    onVolumeChange: (Float) -> Unit,
    onTouch: () -> Unit
) {
    var local by remember(session.sessionId) { mutableFloatStateOf(session.volume) }

    // Debounce: only push to the Shizuku backend after 80 ms without movement
    LaunchedEffect(local) {
        delay(80L)
        Log.d(TAG, "App volume: ${session.appName} = $local")
        onVolumeChange(local)
    }

    val icon = remember(session.packageName) {
        session.appIcon?.toBitmap(144, 144)?.asImageBitmap()
    }

    Box(Modifier.size(rp(G.UNIT), rp(G.PANEL_H))) {
        DotSlider(local, { local = it }, onTouch, Modifier.offset(y = rp(G.TRACK_TOP)))
        IconSlot(G.ICON_CY, G.UNIT, Modifier) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = session.appName,
                    modifier = Modifier
                        .size(rp(66))
                        .clip(RoundedCornerShape(rp(18)))
                )
            } else {
                NIcon(Icons.Outlined.MusicNote, NOS.Icon)
            }
        }
        if (isFocused) {
            IconSlot(G.ICON_CY + 62, G.UNIT, Modifier) {
                Box(
                    Modifier
                        .size(rp(12))
                        .background(NOS.Focus, CircleShape)
                )
            }
        }
    }
}

// ───────────────────────── Building blocks ─────────────────────────

/**
 * Vertical dot track with a white level bar growing from the bottom.
 *
 * While the finger is down the bar follows it continuously; the callback still
 * receives the raw fraction and the caller decides on discrete volume steps.
 * After release (or when the value changes from outside) the bar glides to the
 * real value instead of jumping.
 */
@Composable
private fun DotSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val s = LocalRef.current
    val scope = rememberCoroutineScope()
    val currentOnFraction by rememberUpdatedState(onFraction)
    val currentOnTouch by rememberUpdatedState(onTouch)
    var lastStep by remember { mutableIntStateOf(-1) }

    var dragging by remember { mutableStateOf(false) }
    var dragLevel by remember { mutableFloatStateOf(0f) }
    val level = remember { Animatable(fraction) }
    LaunchedEffect(fraction, dragging) {
        // Spring (not tween): when the target moves again while still animating, e.g. while a
        // volume key is held and the value steps every ~150 ms, the velocity is carried over,
        // so the bar glides continuously instead of restarting at every step.
        if (!dragging) {
            level.animateTo(
                targetValue = fraction,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.0005f
                )
            )
        }
    }

    val emit: (Float) -> Unit = { f ->
        val step = (f * G.DOT_COUNT).roundToInt()
        if (step != lastStep) {
            lastStep = step
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        currentOnTouch()
        currentOnFraction(f)
    }
    val finishDrag: () -> Unit = {
        scope.launch {
            level.snapTo(dragLevel)
            dragging = false
        }
    }

    Canvas(
        modifier = modifier
            .size(rp(G.UNIT), rp(G.TRACK_H))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    emit(1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val f = 1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                        dragLevel = f
                        dragging = true
                        emit(f)
                    },
                    onDragEnd = { finishDrag() },
                    onDragCancel = { finishDrag() },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val f = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        dragLevel = f
                        emit(f)
                    }
                )
            }
    ) {
        val radius = G.DOT_D / 2f * s
        for (k in 0 until G.DOT_COUNT) {
            drawCircle(
                color = NOS.Dot,
                radius = radius,
                center = Offset(size.width / 2f, (G.DOT_FIRST + k * G.DOT_PITCH) * s)
            )
        }
        val shown = if (dragging) dragLevel else level.value
        if (shown > 0.002f) {
            val w = G.BAR_W * s
            val h = (shown * G.TRACK_H).coerceAtLeast(G.BAR_W.toFloat()) * s
            drawRoundRect(
                color = NOS.Light,
                topLeft = Offset((size.width - w) / 2f, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f)
            )
        }
    }
}

/** Centres [content] vertically on [centerY] (reference px from panel top). */
@Composable
private fun IconSlot(
    centerY: Int,
    width: Int,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .offset(y = rp(centerY - G.ICON_BOX / 2))
            .size(rp(width), rp(G.ICON_BOX))
            .then(modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun NIcon(vector: ImageVector, tint: Color) {
    Icon(
        imageVector = vector,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(rp(G.ICON_BOX))
    )
}

@Composable
private fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

/** Reports touch start/end on the whole overlay (pauses the auto-hide timer). */
private fun Modifier.trackTouch(onStart: () -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onStart()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            onEnd()
        }
    }
