package ru.epbloger.trachu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Only the genuine editor opened by an active walkthrough opts into hints. */
internal val LocalGuidedEditorHint = compositionLocalOf { false }

internal enum class TutorialStep(val label: String, val instruction: String) {
    EXPENSE("Расход", "Нажмите подсвеченную кнопку «Расход». Откроется настоящая форма покупки: укажите сумму и категорию, если хотите записать свой расход, или нажмите «Отмена», чтобы продолжить обучение."),
    INCOME("Доход", "Нажмите «Доход», чтобы увидеть, как записать поступление денег. Чтобы продолжить без записи, нажмите «Отмена» в открывшейся форме."),
    OPERATIONS("Операции", "Нажмите «Операции». Здесь находятся записи: их можно найти, открыть и исправить."),
    PLAN("План", "Нажмите «План». Здесь можно создавать бюджеты, цели, долги и будущие платежи."),
    REPORTS("Аналитика", "Нажмите «Аналитика», чтобы увидеть структуру доходов и расходов за нужный период."),
    MENU("Открыть меню", "Нажмите кнопку меню. Здесь находятся счета, категории, конвертер валют, резервные копии и настройки защиты."),
    CATEGORIES("Категории", "Нажмите «Категории». Здесь можно создавать свои категории расходов и доходов, выбирать их цвет и значок. Затем перейдём к конвертеру."),
    MENU_CONVERTER("Меню", "Вы на экране категорий. Здесь можно изменить существующие категории или добавить свои. Нажмите меню ещё раз, чтобы перейти к конвертеру."),
    CONVERTER("Конвертер валют", "Нажмите «Конвертер валют» в открытом меню. Здесь можно пересчитать сумму в другой валюте без создания операции."),
    DONE("Готово", "Теперь вы знаете, где находятся основные функции. Обучение можно повторить из меню в любое время.");

    val next: TutorialStep? get() = entries.getOrNull(ordinal + 1)
}

internal data class TutorialBoundsSample(val bounds: Rect, val revision: Long)

/** Test-only geometry channel. It does not add semantics to the protected controls. */
internal object TutorialBoundsProbe {
    private val revision = AtomicLong()
    private val samples = ConcurrentHashMap<String, TutorialBoundsSample>()

    fun sample(key: String): TutorialBoundsSample? = samples[key]
    fun record(key: String, bounds: Rect) {
        samples[key] = TutorialBoundsSample(bounds, revision.incrementAndGet())
    }
    fun clear(key: String) {
        samples.remove(key)
    }
}

/** Measured from the actual control, not from a hard-coded screen coordinate. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.tutorialAnchor(active: Boolean, probeKey: String, onBounds: (Rect) -> Unit): Modifier =
    if (!active) this else composed {
        val requester = remember { BringIntoViewRequester() }
        val report by rememberUpdatedState(onBounds)
        DisposableEffect(probeKey) {
            onDispose { TutorialBoundsProbe.clear(probeKey) }
        }
        LaunchedEffect(requester) {
            withFrameNanos { }
            requester.bringIntoView()
        }
        this.bringIntoViewRequester(requester).onGloballyPositioned {
            val bounds = it.boundsInRoot()
            TutorialBoundsProbe.record(probeKey, bounds)
            report(bounds)
        }
    }

/** A guide lives in the same window as the real controls, so it follows density,
 * layout, window insets and rotation. The transparent target invokes the exact
 * normal action; other touches are blocked while the guide is visible. */
@Composable
internal fun TutorialSpotlight(step: TutorialStep, anchor: Rect?, advance: () -> Unit, skip: () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.primaryContainer
    BoxWithConstraints(Modifier.fillMaxSize().testTag("tutorial-overlay")
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(Unit) {
            // This full-screen hit-test node blocks sibling controls, but does
            // not consume events needed by its own clickable/scrollable children.
            awaitPointerEventScope {
                while (true) awaitPointerEvent()
            }
        }) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val hole = anchor?.let {
            Rect((it.left - origin.x).coerceIn(0f, width), (it.top - origin.y).coerceIn(0f, height),
                (it.right - origin.x).coerceIn(0f, width), (it.bottom - origin.y).coerceIn(0f, height))
        }?.takeIf { it.width > 1f && it.height > 1f && step != TutorialStep.DONE }
        Canvas(Modifier.fillMaxSize()) {
            val scrim = Color.Black.copy(alpha = .72f)
            if (hole == null) drawRect(scrim) else {
                fun shade(left: Float, top: Float, right: Float, bottom: Float) {
                    if (right > left && bottom > top) drawRect(scrim, Offset(left, top), Size(right - left, bottom - top))
                }
                shade(0f, 0f, size.width, hole.top)
                shade(0f, hole.bottom, size.width, size.height)
                shade(0f, hole.top, hole.left, hole.bottom)
                shade(hole.right, hole.top, size.width, hole.bottom)
                drawRoundRect(outline, Offset(hole.left, hole.top), Size(hole.width, hole.height),
                    CornerRadius(12.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
        if (hole != null) {
            Box(Modifier.absoluteOffset(with(density) { hole.left.toDp() }, with(density) { hole.top.toDp() })
                .size(with(density) { hole.width.toDp() }, with(density) { hole.height.toDp() })
                .testTag("tutorial-target-${step.name.lowercase()}")
                .clickable(role = Role.Button, onClick = advance)
                .semantics { contentDescription = "Нажать «${step.label}»" })
        }
        val left = with(density) { (hole?.left ?: 0f).toDp() }
        val right = with(density) { (width - (hole?.right ?: width)).toDp() }
        val above = with(density) { (hole?.top ?: 0f).toDp() }
        val below = with(density) { (height - (hole?.bottom ?: height)).toDp() }
        val side = hole != null && maxWidth > maxHeight && maxOf(left, right) >= 240.dp
        val placement = when {
            hole == null -> Alignment.Center
            side && right >= left -> Alignment.CenterEnd
            side -> Alignment.CenterStart
            above >= below -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }
        val cardWidth = if (side) (maxOf(left, right) - 24.dp).coerceIn(216.dp, 380.dp) else minOf(380.dp, maxWidth - 24.dp)
        val cardHeight = if (hole == null || side) maxHeight - 24.dp else
            (maxOf(above, below) - 32.dp).coerceIn(minOf(120.dp, maxHeight), maxHeight)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), contentAlignment = placement) {
            Surface(Modifier.widthIn(max = cardWidth).fillMaxWidth().heightIn(max = cardHeight),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Как пользоваться ТРАЧУ.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("Шаг ${step.ordinal + 1} из ${TutorialStep.entries.size}", style = MaterialTheme.typography.labelMedium)
                        Text(step.label, style = MaterialTheme.typography.titleLarge)
                        Text(step.instruction, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (step == TutorialStep.DONE) {
                        Button(onClick = advance, modifier = Modifier.fillMaxWidth().testTag("tutorial-next")) { Text("Готово") }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = skip, modifier = Modifier.weight(1f).testTag("tutorial-skip")) { Text("Пропустить") }
                            Button(onClick = advance, modifier = Modifier.weight(1f).testTag("tutorial-next")) { Text("Далее") }
                        }
                    }
                }
            }
        }
    }
}
