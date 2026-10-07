package com.ouyue.ji

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.tasks.Task
import com.ouyue.ji.data.TaskDatabase
import com.ouyue.ji.data.TaskEntity
import com.ouyue.ji.data.TaskRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private var sharedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingImageShare(intent)

        val database = TaskDatabase.getDatabase(applicationContext)
        val repository = TaskRepository(database.taskDao())
        val taskViewModel = ViewModelProvider(
            this,
            TaskViewModel.Factory(repository)
        )[TaskViewModel::class.java]

        setContent {
            JiApp(
                viewModel = taskViewModel,
                sharedImageUri = sharedImageUri,
                onClearSharedImage = { sharedImageUri = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingImageShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingImageShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) return

        sharedImageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri
    }
}

private const val JiTaskLogTag = "JiTaskFlow"
private const val JiPrivacyPrefs = "ji_privacy"
private const val JiPrivacyAcknowledgedKey = "privacy_acknowledged_v1"

private inline fun jiDebugLog(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d(JiTaskLogTag, message())
    }
}

private enum class JiScreen {
    Home,
    AddTask,
    TaskDetail,
    Account,
    Privacy
}

private enum class AddTaskMode(val title: String, val subtitle: String) {
    Manual("手动添加", "自己填写任务信息"),
    Paste("粘贴识别", "粘贴通知、作业、面试安排"),
    Image("图片识别", "从相册选择截图，自动整理待办"),
    MultiImage("多图批量识别", "一次导入多张截图，高精度整理")
}

private enum class OcrStatus {
    Recognizing,
    Success,
    Failed
}

private enum class ImportTaskStage(
    val title: String,
    val hint: String,
    val progress: Float
) {
    Received(
        title = "已接收截图",
        hint = "正在准备截图预览",
        progress = 0.10f
    ),
    OptimizingImage(
        title = "正在优化图片",
        hint = "正在压缩截图并保留文字清晰度",
        progress = 0.24f
    ),
    RecognizingText(
        title = "正在识别文字...",
        hint = "正在从截图中读取关键信息，请稍等",
        progress = 0.40f
    ),
    ExtractingTasks(
        title = "正在提取任务...",
        hint = "正在筛出标题、时间、状态和要求",
        progress = 0.58f
    ),
    Classifying(
        title = "正在判断分类和重要性...",
        hint = "正在区分学习、工作、生活以及优先级",
        progress = 0.72f
    ),
    CheckingDeadline(
        title = "正在核对截止时间",
        hint = "正在检查时间范围、完成状态和截止信息",
        progress = 0.84f
    ),
    ArrangingCards(
        title = "正在整理任务卡片...",
        hint = "正在合并通知信息并拆分独立任务",
        progress = 0.92f
    ),
    Completed(
        title = "整理完成",
        hint = "已整理出可确认的任务卡片",
        progress = 1f
    )
}

private enum class TaskDashboardCategory(val title: String) {
    Study("学习"),
    Work("工作"),
    Life("生活"),
    Other("其他")
}

private enum class HomeTaskTab(val title: String) {
    Priority("优先"),
    Study("学习"),
    Work("工作"),
    Life("生活"),
    Other("其他")
}

private enum class TaskDueBucket(val priority: Int) {
    Today(0),
    Tomorrow(1),
    Soon(2),
    Dated(3),
    None(4),
    Overdue(5)
}

private enum class TaskArchiveKind {
    Overdue,
    Completed
}

private data class TaskDueInfo(
    val bucket: TaskDueBucket,
    val date: LocalDate? = null
)

private data class RankedTask(
    val task: TaskEntity,
    val completionRank: Int,
    val priorityRank: Int,
    val dueInfo: TaskDueInfo
)

private data class TaskCategorySummary(
    val category: TaskDashboardCategory,
    val tasks: List<TaskEntity>,
    val unfinishedTasks: List<TaskEntity>,
    val importantTask: TaskEntity?,
    val urgentText: String
)

private data class TaskGroupSummary(
    val label: String,
    val tasks: List<TaskEntity>
)

private data class FloatingPanelAnchor(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
)

private fun LayoutCoordinates.toFloatingPanelAnchorOrNull(): FloatingPanelAnchor? {
    if (!isAttached) return null
    val position = positionInWindow()
    return FloatingPanelAnchor(
        centerX = position.x + size.width / 2f,
        centerY = position.y + size.height / 2f,
        width = size.width.toFloat(),
        height = size.height.toFloat()
    )
}

private data class JiTone(
    val color: Color,
    val background: Color
)

private val JiBackground = PageBackground
private val JiCardBackground = CardBackground
private val JiPrimaryBlue = BrandBlue
private val JiPrimaryBlueDark = BrandBlueDark
private val JiLightBlue = NormalTaskBackground
private val JiPrimaryText = PrimaryText
private val JiSecondaryText = SecondaryText
private val JiTertiaryText = TertiaryText
private val JiBorder = CardBorder
private val JiCompletedGreen = Completed
private val JiDeleteRed = HighImportance
private val JiHighImportanceRed = HighImportance
private val JiUrgentOrange = Urgent
private val JiSoftPurple = Study
private val JiMintGreen = Life

private val JiCardShape = RoundedCornerShape(30.dp)
private val JiDashboardCardShape = RoundedCornerShape(32.dp)
private val JiCompactCardShape = RoundedCornerShape(26.dp)
private val JiSheetShape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
private val JiButtonShape = RoundedCornerShape(50.dp)
private val JiTagShape = RoundedCornerShape(50.dp)
private const val JiAnimationDuration = 220
private const val JiFastAnimationDuration = 150
private const val JiPanelAnimationDuration = 260
private const val PreviewMaxDimension = 1600

@Composable
private fun rememberJiPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(durationMillis = JiFastAnimationDuration, easing = FastOutSlowInEasing),
        label = "ji-press-scale"
    )
    return scale
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun JiTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    titleContentColor = MaterialTheme.colorScheme.onBackground,
    navigationIconContentColor = MaterialTheme.colorScheme.primary
)

@Composable
private fun JiCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
private fun JiCardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun JiCardElevation() = CardDefaults.cardElevation(defaultElevation = 1.dp)

@Composable
private fun JiPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun JiSecondaryTextButtonColors() = ButtonDefaults.buttonColors(
    containerColor = TabInactiveBackground,
    contentColor = SecondaryText
)

@Composable
private fun JiDeleteTextButtonColors() = ButtonDefaults.textButtonColors(
    containerColor = Color.Transparent,
    contentColor = HighImportance.copy(alpha = 0.68f)
)

@Composable
private fun JiOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    errorLabelColor = MaterialTheme.colorScheme.error,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    errorTextColor = MaterialTheme.colorScheme.onSurface
)

@Composable
private fun taskStatusColor(status: String): Color =
    if (normalizeDraftStatus(status) == "已完成") {
        Completed
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

private data class OcrParseResult(
    val recognizedText: String,
    val drafts: List<ParsedTaskDraft>,
    val usage: AuthState? = null,
    val proHint: ProHint? = null,
    val serverMessage: String = ""
)

private suspend fun parseSharedImageTask(
    context: Context,
    imageUri: Uri,
    imageTaskParser: ImageTaskParser,
    taskParser: TaskParser,
    onStageChange: (ImportTaskStage) -> Unit = {}
): OcrParseResult {
    val flowStartedAt = System.currentTimeMillis()
    onStageChange(ImportTaskStage.OptimizingImage)
    val aiImageDrafts = runCatching {
        imageTaskParser.parse(context, imageUri)
    }.getOrNull()
    val aiParser = imageTaskParser as? AiImageTaskParser

    if (!aiImageDrafts.isNullOrEmpty()) {
        onStageChange(ImportTaskStage.ExtractingTasks)
        val renderStartedAt = System.currentTimeMillis()
        val unfinishedDrafts = withContext(Dispatchers.Default) {
            normalizeParsedDrafts(aiImageDrafts.filter { it.isUnfinishedTaskDraft() })
        }
        onStageChange(ImportTaskStage.Classifying)
        onStageChange(ImportTaskStage.CheckingDeadline)

        onStageChange(ImportTaskStage.ArrangingCards)
        if (BuildConfig.DEBUG) {
            Log.d(
                "JiTaskFlow",
                "[OCR_TIMING] renderMs=${System.currentTimeMillis() - renderStartedAt} " +
                    "totalMs=${System.currentTimeMillis() - flowStartedAt} tasks=${unfinishedDrafts.size}"
            )
        }
        return OcrParseResult(
            recognizedText = aiImageDrafts.firstNotNullOfOrNull { draft ->
                draft.rawText.takeIf { it.isNotBlank() }
            } ?: "AI 图片解析成功",
            drafts = unfinishedDrafts,
            usage = aiParser?.lastUsage,
            proHint = aiParser?.lastProHint,
            serverMessage = aiParser?.lastServerMessage.orEmpty()
        )
    }

    if (aiParser?.lastResponseReceived == true) {
        return OcrParseResult(
            recognizedText = aiParser.lastServerMessage.ifBlank { "未发现需要新建的任务" },
            drafts = emptyList(),
            usage = aiParser.lastUsage,
            proHint = aiParser.lastProHint,
            serverMessage = aiParser.lastServerMessage
        )
    }

    return recognizeAndParseTask(context, imageUri, taskParser, onStageChange)
}

private suspend fun recognizeAndParseTask(
    context: Context,
    imageUri: Uri,
    taskParser: TaskParser,
    onStageChange: (ImportTaskStage) -> Unit = {}
): OcrParseResult {
    onStageChange(ImportTaskStage.RecognizingText)
    val rawText = recognizeImageText(context, imageUri)

    onStageChange(ImportTaskStage.ExtractingTasks)
    val recognizedText = withContext(Dispatchers.Default) {
        LocalTaskParser.cleanOcrText(rawText)
    }
    if (recognizedText.isBlank()) {
        return OcrParseResult(
            recognizedText = recognizedText,
            drafts = emptyList()
        )
    }

    onStageChange(ImportTaskStage.Classifying)
    val parsedDrafts = withContext(Dispatchers.Default) {
        taskParser.parseMany(recognizedText).filter { it.isUnfinishedTaskDraft() }
    }
    onStageChange(ImportTaskStage.CheckingDeadline)
    onStageChange(ImportTaskStage.ArrangingCards)
    val drafts = withContext(Dispatchers.Default) {
        normalizeParsedDrafts(parsedDrafts)
    }

    return OcrParseResult(
        recognizedText = recognizedText,
        drafts = drafts
    )
}

private suspend fun parseLocalTaskListFromRecognizedText(recognizedText: String): List<ParsedTaskDraft> =
    withContext(Dispatchers.Default) {
        normalizeParsedDrafts(
            LocalTaskParser.parseLocalMany(recognizedText)
                .filter { it.isUnfinishedTaskDraft() }
        )
    }

private suspend fun tryRescueTaskListFromLocalOcr(
    context: Context,
    imageUri: Uri,
    currentDrafts: List<ParsedTaskDraft>,
    onStageChange: (ImportTaskStage) -> Unit = {}
): OcrParseResult? {
    onStageChange(ImportTaskStage.RecognizingText)
    val rawText = runCatching {
        recognizeImageText(context, imageUri)
    }.getOrNull().orEmpty()
    onStageChange(ImportTaskStage.ExtractingTasks)
    val recognizedText = withContext(Dispatchers.Default) {
        LocalTaskParser.cleanOcrText(rawText)
    }
    if (!LocalTaskParser.looksLikeCleanTaskListPage(recognizedText)) return null

    onStageChange(ImportTaskStage.Classifying)
    val localDrafts = parseLocalTaskListFromRecognizedText(recognizedText)
    onStageChange(ImportTaskStage.ArrangingCards)
    if (!shouldPreferLocalTaskListRescue(localDrafts, currentDrafts)) return null

    return OcrParseResult(
        recognizedText = recognizedText,
        drafts = localDrafts.take(10)
    )
}

private fun normalizeParsedDrafts(drafts: List<ParsedTaskDraft>): List<ParsedTaskDraft> {
    val usableDrafts = drafts
        .map { draft -> sanitizeParsedDraft(draft) }
        .filterNot { draft -> shouldDropParsedDraft(draft) }
        .distinctBy { draft ->
            listOf(draft.title, draft.dueTime, draft.displayContextValue())
                .joinToString("|")
                .lowercase()
        }

    if (usableDrafts.isEmpty()) return emptyList()
    if (looksLikeIndependentTaskListDrafts(usableDrafts)) return usableDrafts.take(10)
    if (usableDrafts.size <= 3 || !looksLikeSingleEventDrafts(usableDrafts)) return usableDrafts

    return listOf(mergeSingleEventDrafts(usableDrafts))
}

private fun sanitizeParsedDraft(draft: ParsedTaskDraft): ParsedTaskDraft {
    val cleanedTitle = cleanupParsedDraftTitle(draft.title)
    val cleanedMaterials = draft.materials
        .split("、", "；", ";")
        .map { value -> value.trim() }
        .filter { value -> value.isNotBlank() && value != "附件" }
        .distinct()
        .joinToString("、")

    return repairInferredDeadline(
        draft.copy(
            title = cleanedTitle.ifBlank { draft.title.trim() },
            materials = cleanedMaterials
        )
    )
}

private data class ParsedDeadlineCandidate(
    val rawText: String,
    val dateTime: LocalDateTime,
    val hasExplicitYear: Boolean
)

private val DeadlineWithYearRegex =
    Regex("""(?<!\d)(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")

private val DeadlineWithoutYearRegex =
    Regex("""(?<![\d-])(\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")

private val AcademicTermRegex =
    Regex("""(20\d{2})\s*[-–—]\s*(20\d{2})\s*[-–—]\s*([12])""")

private fun repairInferredDeadline(draft: ParsedTaskDraft): ParsedTaskDraft {
    val sourceText = listOf(
        draft.rawText,
        draft.note,
        draft.dueTime,
        draft.time,
        draft.contextValue,
        draft.course,
        draft.title
    ).joinToString("\n")
    val candidate = extractDeadlineCandidate(sourceText, draft.dueTime, draft.time) ?: return draft
    val termYear = inferAcademicTermYear(sourceText, candidate.dateTime.monthValue)
    val repaired = repairDeadlineYear(candidate, termYear)
    val finalDeadline = formatDeadline(repaired)

    if (finalDeadline == draft.dueTime && finalDeadline == draft.time) return draft

    if (BuildConfig.DEBUG) {
        Log.d(
            "JiDateInfer",
            "[DATE_INFER] rawTime=${candidate.rawText.take(120)} " +
                "inferredYear=${repaired.year} deadline=$finalDeadline"
        )
    }

    return draft.copy(
        time = finalDeadline,
        dueTime = finalDeadline,
        note = replaceMatchingDeadlineYears(draft.note, repaired.year),
        rawText = replaceMatchingDeadlineYears(draft.rawText, repaired.year)
    )
}

private fun extractDeadlineCandidate(
    sourceText: String,
    dueTime: String,
    time: String
): ParsedDeadlineCandidate? {
    extractRangeDeadline(sourceText)?.let { return it }
    parseExplicitDeadline(dueTime)?.let { return it }
    parseExplicitDeadline(time)?.let { return it }
    return extractLatestDeadline(sourceText)
}

private fun extractRangeDeadline(sourceText: String): ParsedDeadlineCandidate? {
    val explicitMatches = DeadlineWithYearRegex.findAll(sourceText).toList()
    if (explicitMatches.size >= 2 &&
        looksLikeRange(sourceText, explicitMatches[explicitMatches.lastIndex - 1], explicitMatches.last())
    ) {
        return parseExplicitDeadline(explicitMatches.last().value)
    }

    val noYearMatches = DeadlineWithoutYearRegex.findAll(sourceText).toList()
    if (noYearMatches.size >= 2 &&
        looksLikeRange(sourceText, noYearMatches[noYearMatches.lastIndex - 1], noYearMatches.last())
    ) {
        return parseNoYearDeadline(noYearMatches.last().value)
    }
    return null
}

private fun looksLikeRange(sourceText: String, start: MatchResult, end: MatchResult): Boolean {
    val from = (start.range.last + 1).coerceAtMost(sourceText.length)
    val to = end.range.first.coerceIn(from, sourceText.length)
    val between = sourceText.substring(from, to)
    return listOf("至", "到", "-", "—", "–", "~").any { marker -> between.contains(marker) }
}

private fun extractLatestDeadline(sourceText: String): ParsedDeadlineCandidate? =
    DeadlineWithYearRegex.findAll(sourceText).lastOrNull()?.value?.let { parseExplicitDeadline(it) }
        ?: DeadlineWithoutYearRegex.findAll(sourceText).lastOrNull()?.value?.let { parseNoYearDeadline(it) }

private fun parseExplicitDeadline(value: String): ParsedDeadlineCandidate? {
    val match = DeadlineWithYearRegex.find(value.trim()) ?: return null
    return ParsedDeadlineCandidate(
        rawText = match.value,
        dateTime = safeDeadline(
            year = match.groupValues[1].toIntOrNull() ?: return null,
            month = match.groupValues[2].toIntOrNull() ?: return null,
            day = match.groupValues[3].toIntOrNull() ?: return null,
            hour = match.groupValues[4].toIntOrNull() ?: return null,
            minute = match.groupValues[5].toIntOrNull() ?: return null,
            second = match.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
        ) ?: return null,
        hasExplicitYear = true
    )
}

private fun parseNoYearDeadline(value: String): ParsedDeadlineCandidate? {
    val match = DeadlineWithoutYearRegex.find(value.trim()) ?: return null
    val currentYear = LocalDate.now().year
    return ParsedDeadlineCandidate(
        rawText = match.value,
        dateTime = safeDeadline(
            year = currentYear,
            month = match.groupValues[1].toIntOrNull() ?: return null,
            day = match.groupValues[2].toIntOrNull() ?: return null,
            hour = match.groupValues[3].toIntOrNull() ?: return null,
            minute = match.groupValues[4].toIntOrNull() ?: return null,
            second = match.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
        ) ?: return null,
        hasExplicitYear = false
    )
}

private fun safeDeadline(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int
): LocalDateTime? =
    runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull()

private fun inferAcademicTermYear(sourceText: String, month: Int): Int? {
    val match = AcademicTermRegex.find(sourceText) ?: return null
    val startYear = match.groupValues[1].toIntOrNull() ?: return null
    val endYear = match.groupValues[2].toIntOrNull() ?: return null
    return when (match.groupValues[3]) {
        "2" -> endYear
        "1" -> if (month >= 8) startYear else endYear
        else -> null
    }
}

private fun repairDeadlineYear(candidate: ParsedDeadlineCandidate, termYear: Int?): LocalDateTime {
    val today = LocalDate.now()
    val preferredYear = termYear ?: today.year
    if (!candidate.hasExplicitYear) return candidate.dateTime.withYearSafe(preferredYear)

    if (!candidate.dateTime.toLocalDate().isBefore(today.minusDays(30))) return candidate.dateTime

    val termCandidate = termYear?.let { candidate.dateTime.withYearSafe(it) }
    val currentCandidate = candidate.dateTime.withYearSafe(today.year)
    return listOfNotNull(termCandidate, currentCandidate)
        .firstOrNull { !it.toLocalDate().isBefore(today.minusDays(30)) }
        ?: termCandidate
        ?: currentCandidate
}

private fun LocalDateTime.withYearSafe(year: Int): LocalDateTime =
    runCatching { withYear(year) }.getOrDefault(this)

private fun replaceMatchingDeadlineYears(value: String, year: Int): String {
    if (value.isBlank()) return value
    return DeadlineWithYearRegex.replace(value) { match ->
        val month = match.groupValues[2].toIntOrNull() ?: return@replace match.value
        val day = match.groupValues[3].toIntOrNull() ?: return@replace match.value
        val hour = match.groupValues[4].toIntOrNull() ?: return@replace match.value
        val minute = match.groupValues[5].toIntOrNull() ?: return@replace match.value
        val second = match.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
        formatDeadline(year, month, day, hour, minute, second)
    }
}

private fun formatDeadline(dateTime: LocalDateTime): String =
    formatDeadline(
        dateTime.year,
        dateTime.monthValue,
        dateTime.dayOfMonth,
        dateTime.hour,
        dateTime.minute,
        dateTime.second
    )

private fun formatDeadline(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
    "${year.toString().padStart(4, '0')}-" +
        "${month.toString().padStart(2, '0')}-" +
        "${day.toString().padStart(2, '0')} " +
        "${hour.toString().padStart(2, '0')}:" +
        "${minute.toString().padStart(2, '0')}:" +
        second.toString().padStart(2, '0')

private fun cleanupParsedDraftTitle(title: String): String =
    title
        .replace("请输入关键字查询", "")
        .replace(Regex("""作业\s*[|｜]\s*考试"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '，', '。', ',', '.', '；', ';', ':', '|', '｜')

private fun shouldPreferLocalTaskListRescue(
    localDrafts: List<ParsedTaskDraft>,
    currentDrafts: List<ParsedTaskDraft>
): Boolean {
    if (localDrafts.isEmpty()) return false

    val localLooksLikeHomeworkList = looksLikeIndependentTaskListDrafts(localDrafts) ||
        localDrafts.any { draft -> looksLikeNumberedHomeworkTitle(draft.title) }
    if (!localLooksLikeHomeworkList) return false

    if (currentDrafts.isEmpty()) return true
    if (localDrafts.size > currentDrafts.size && looksLikeIndependentTaskListDrafts(localDrafts)) return true

    val currentBadCount = currentDrafts.count { draft -> shouldDropParsedDraft(draft) }
    val currentGoodHomeworkCount = currentDrafts.count { draft ->
        looksLikeNumberedHomeworkTitle(draft.title) && !isUiNoiseDraft(draft)
    }

    return currentBadCount > 0 || currentGoodHomeworkCount == 0
}

private fun shouldDropParsedDraft(draft: ParsedTaskDraft): Boolean {
    if (draft.title.isBlank()) return true
    if (isUiNoiseDraft(draft)) return true
    if (isClearlyFinishedDraft(draft)) return true
    return false
}

private fun isUiNoiseDraft(draft: ParsedTaskDraft): Boolean {
    val compactTitle = draft.title.replace(Regex("""\s+"""), "")
    val compactText = listOf(draft.title, draft.note, draft.rawText)
        .joinToString(" ")
        .replace(Regex("""\s+"""), "")

    if (compactTitle.contains("请输入关键字查询")) return true
    if (compactTitle == "作业考试" || compactTitle == "作业|考试" || compactTitle == "作业｜考试") return true
    if (compactTitle.contains("课程介绍")) return true
    if (compactTitle in listOf("全部", "班级学习", "课件", "课堂", "成绩", "搜索", "查看")) return true

    return compactTitle.length <= 3 && !looksLikeNumberedHomeworkTitle(compactText)
}

private fun isClearlyFinishedDraft(draft: ParsedTaskDraft): Boolean {
    if (normalizeDraftStatus(draft.status) == "已完成") return true

    val focusedText = listOf(draft.title, draft.note, draft.rawText)
        .joinToString(" ")
        .replace(Regex("""\s+"""), "")
    val finishedSignals = listOf("已结束", "已批阅", "已完成", "已提交", "已评价", "已关闭", "得分", "查看答案", "历史记录", "查看")
    val unfinishedSignals = listOf("进行中", "未完成", "未做", "前往作业", "待提交", "待完成")
    val hasFinishedSignal = finishedSignals.any { signal -> focusedText.contains(signal) }
    val hasUnfinishedSignal = unfinishedSignals.any { signal -> focusedText.contains(signal) }

    return hasFinishedSignal && !hasUnfinishedSignal
}

private fun looksLikeSingleEventDrafts(drafts: List<ParsedTaskDraft>): Boolean {
    val text = drafts.joinToString(" ") { draft ->
        listOf(
            draft.title,
            draft.category,
            draft.subType,
            draft.contextValue,
            draft.time,
            draft.location,
            draft.materials,
            draft.note,
            draft.rawText
        ).joinToString(" ")
    }
    if (LocalTaskParser.looksLikeTaskListPage(text)) return false
    if (looksLikeIndependentTaskListDrafts(drafts)) return false

    val singleEventSignals = listOf(
        "面试",
        "通知面试",
        "会议",
        "考试",
        "报名",
        "活动通知",
        "宣讲",
        "签到",
        "录取",
        "入职"
    )
    if (!singleEventSignals.any { text.contains(it, ignoreCase = true) }) return false

    val categories = drafts.map { it.normalizedCategory() }.filter { it.isNotBlank() }.distinct()
    val contextValues = drafts.map { it.displayContextValue() }.filter { it.isNotBlank() }.distinct()
    val locations = drafts.map { it.location }.filter { it.isNotBlank() }.distinct()
    val times = drafts.map { it.dueTime.ifBlank { it.time } }.filter { it.isNotBlank() }.distinct()

    return categories.size <= 2 &&
        contextValues.size <= 3 &&
        locations.size <= 3 &&
        times.size <= 3
}

private fun looksLikeIndependentTaskListDrafts(drafts: List<ParsedTaskDraft>): Boolean {
    if (drafts.size < 2) return false

    val titles = drafts
        .map { draft -> normalizeIndependentDraftTitle(draft.title) }
        .filter { title -> title.isNotBlank() && looksLikeNumberedHomeworkTitle(title) }
        .distinct()

    val combinedText = drafts.joinToString(" ") { draft ->
        listOf(draft.title, draft.subType, draft.note, draft.rawText).joinToString(" ")
    }

    return titles.size >= 2 ||
        LocalTaskParser.looksLikeTaskListPage(combinedText) ||
        countTextOccurrences(combinedText, "前往作业") >= 2 ||
        countTextOccurrences(combinedText, "作业时间") >= 2
}

private fun looksLikeNumberedHomeworkTitle(text: String): Boolean =
    Regex("""第[一二三四五六七八九十百千万\d]+[章节]?.{0,24}作业\s*\d+""").containsMatchIn(text) ||
        Regex("""作业\s*\d+""").containsMatchIn(text)

private fun normalizeIndependentDraftTitle(title: String): String =
    title.replace(Regex("""\s+"""), "")
        .replace("进行中", "")
        .replace("未做", "")
        .replace("已结束", "")
        .replace("已批阅", "")
        .trim()

private fun countTextOccurrences(text: String, word: String): Int =
    Regex(Regex.escape(word)).findAll(text).count()

private fun mergeSingleEventDrafts(drafts: List<ParsedTaskDraft>): ParsedTaskDraft {
    val allText = drafts.joinToString("\n") { draft ->
        listOf(draft.title, draft.note, draft.materials, draft.location, draft.rawText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
    val category = drafts.firstNotNullOfOrNull { draft ->
        draft.normalizedCategory().takeIf { it.isNotBlank() }
    } ?: "其他"
    val subType = when {
        allText.contains("面试") -> "面试"
        allText.contains("考试") -> "考试"
        allText.contains("会议") -> "会议"
        allText.contains("报名") -> "报名"
        else -> drafts.firstNotNullOfOrNull { it.displaySubType().takeIf { type -> type.isNotBlank() } }.orEmpty()
    }
    val title = drafts.firstOrNull { draft ->
        listOf("面试", "考试", "会议", "报名", "通知", "活动").any { draft.title.contains(it) }
    }?.title ?: drafts.firstOrNull()?.title.orEmpty()
    val normalizedTitle = when {
        subType == "面试" && !title.contains("面试") -> "参加面试"
        subType == "考试" && !title.contains("考试") -> "参加考试"
        subType == "会议" && !title.contains("会议") -> "参加会议"
        else -> title
    }
    val contextLabel = drafts.firstNotNullOfOrNull { it.displayContextLabel().takeIf { label -> label.isNotBlank() } }.orEmpty()
    val contextValue = drafts.firstNotNullOfOrNull { it.displayContextValue().takeIf { value -> value.isNotBlank() } }.orEmpty()
    val time = drafts.firstNotNullOfOrNull { it.dueTime.ifBlank { it.time }.takeIf { value -> value.isNotBlank() } }.orEmpty()
    val location = drafts.map { it.location }.filter { it.isNotBlank() }.distinct().joinToString("；").take(80)
    val materials = drafts.map { it.materials }.filter { it.isNotBlank() }.distinct().joinToString("；").take(120)
    val note = drafts.flatMap { draft ->
        listOf(draft.note, draft.materials, draft.location, draft.title)
    }
        .map { it.trim() }
        .filter { it.isNotBlank() && it != normalizedTitle }
        .distinct()
        .joinToString("\n")
        .take(700)
    val rawText = drafts.firstNotNullOfOrNull { it.rawText.takeIf { raw -> raw.isNotBlank() } }
        ?: allText

    return ParsedTaskDraft(
        title = normalizedTitle.ifBlank { "待处理通知" },
        category = category,
        subType = subType,
        importance = "高",
        contextLabel = contextLabel,
        contextValue = contextValue,
        status = "未完成",
        time = time,
        dueTime = time,
        location = location,
        platform = drafts.firstNotNullOfOrNull { it.platform.takeIf { platform -> platform.isNotBlank() } }.orEmpty(),
        materials = materials,
        course = drafts.firstNotNullOfOrNull { it.course.takeIf { course -> course.isNotBlank() } }.orEmpty(),
        note = note,
        rawText = rawText
    )
}

private suspend fun recognizeImageText(context: Context, imageUri: Uri): String {
    val image = withContext(Dispatchers.IO) {
        InputImage.fromFilePath(context, imageUri)
    }
    val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    return try {
        recognizer.process(image).awaitResult().text
    } finally {
        recognizer.close()
    }
}

private suspend fun loadPreviewBitmap(context: Context, imageUri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        loadScaledBitmap(context, imageUri, PreviewMaxDimension)
    }

private fun loadScaledBitmap(context: Context, imageUri: Uri, maxDimension: Int): Bitmap? {
    val resolver = context.contentResolver
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    resolver.openInputStream(imageUri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, boundsOptions)
    }

    val width = boundsOptions.outWidth
    val height = boundsOptions.outHeight
    if (width <= 0 || height <= 0) {
        return resolver.openInputStream(imageUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(width, height, maxDimension)
    }

    return resolver.openInputStream(imageUri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, decodeOptions)
    }
}

private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    val longestSide = max(width, height)

    while (longestSide / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    return sampleSize
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.resumeWithException(CancellationException("Task was canceled"))
            }
        }
    }

@Composable
private fun JiApp(
    viewModel: TaskViewModel,
    sharedImageUri: Uri?,
    onClearSharedImage: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val tasks by viewModel.tasks.collectAsState(initial = emptyList())
    var currentScreen by remember { mutableStateOf(JiScreen.Home) }
    var selectedTaskId by remember { mutableStateOf<Long?>(null) }
    var selectedAddMode by remember { mutableStateOf(AddTaskMode.Manual) }
    var showAddTaskMenu by remember { mutableStateOf(false) }
    val authManager = remember {
        AuthManager(appContext, BuildConfig.AI_IMAGE_TASK_PARSER_URL)
    }
    var authState by remember { mutableStateOf(authManager.cachedState()) }
    val taskParser = remember {
        FallbackTaskParser(
            primary = AiTaskParser(BuildConfig.AI_TASK_PARSER_URL),
            fallback = LocalTaskParser
        )
    }
    val imageTaskParser = remember {
        AiImageTaskParser(BuildConfig.AI_IMAGE_TASK_PARSER_URL, authManager)
    }
    val privacyPrefs = remember {
        appContext.getSharedPreferences(JiPrivacyPrefs, Context.MODE_PRIVATE)
    }
    var showPrivacyNotice by remember {
        mutableStateOf(!privacyPrefs.getBoolean(JiPrivacyAcknowledgedKey, false))
    }

    LaunchedEffect(authManager) {
        authManager.ensureSession()
        authState = authManager.fetchMe() ?: authManager.cachedState()
    }

    val saveDraftsToRoom: (List<ParsedTaskDraft>) -> Unit = { drafts ->
        drafts.filter { it.isUnfinishedTaskDraft() }.forEach { draft ->
            val storageCourse = draft.courseForStorage()
            val storageNote = draft.noteForStorage()
            jiDebugLog {
                "[SAVE_TASK_ENTITY] category=${draft.normalizedCategory()}, " +
                    "type=${draft.displaySubType()}, hasCourse=${storageCourse.isNotBlank()}, " +
                    "hasTime=${draft.dueTime.isNotBlank()}, hasLocation=${draft.location.isNotBlank()}, " +
                    "noteChars=${storageNote.length}"
            }
            viewModel.addTask(
                title = draft.title,
                course = storageCourse,
                dueTime = draft.dueTime,
                note = storageNote
            )
        }
    }

    JiTheme {
        if (sharedImageUri != null) {
            ImportImageScreen(
                imageUri = sharedImageUri,
                taskParser = taskParser,
                imageTaskParser = imageTaskParser,
                onSaveTasks = { drafts ->
                    saveDraftsToRoom(drafts)
                    selectedTaskId = null
                    currentScreen = JiScreen.Home
                    onClearSharedImage()
                },
                onSaveTask = { draft ->
                    saveDraftsToRoom(listOf(draft))
                },
                onManualAdd = {
                    selectedTaskId = null
                    selectedAddMode = AddTaskMode.Manual
                    currentScreen = JiScreen.AddTask
                    onClearSharedImage()
                },
                onReturnHome = {
                    selectedTaskId = null
                    currentScreen = JiScreen.Home
                    onClearSharedImage()
                }
            )
        } else when (currentScreen) {
            JiScreen.Home -> HomeScreen(
                tasks = tasks,
                onAddClick = { showAddTaskMenu = true },
                onOpenTask = { task ->
                    selectedTaskId = task.id
                    currentScreen = JiScreen.TaskDetail
                },
                onToggleTaskStatus = viewModel::toggleTaskStatus,
                onDeleteTask = viewModel::deleteTask,
                onClearArchivedTasks = viewModel::deleteTasks,
                onAccountClick = { currentScreen = JiScreen.Account }
            )

            JiScreen.AddTask -> AddTaskScreen(
                initialMode = selectedAddMode,
                authState = authState,
                taskParser = taskParser,
                imageTaskParser = imageTaskParser,
                onBack = { currentScreen = JiScreen.Home },
                onSave = { title, course, dueTime, note ->
                    viewModel.addTask(
                        title = title,
                        course = course,
                        dueTime = dueTime,
                        note = note
                    )
                    currentScreen = JiScreen.Home
                },
                onSaveDrafts = { drafts ->
                    saveDraftsToRoom(drafts)
                    currentScreen = JiScreen.Home
                }
            )

            JiScreen.TaskDetail -> {
                val selectedTask = tasks.firstOrNull { task -> task.id == selectedTaskId }

                if (selectedTask == null) {
                    TaskMissingScreen(
                        onBack = { currentScreen = JiScreen.Home }
                    )
                } else {
                    TaskDetailScreen(
                        task = selectedTask,
                        onBack = { currentScreen = JiScreen.Home },
                        onSave = { updatedTask ->
                            viewModel.updateTask(updatedTask)
                            currentScreen = JiScreen.Home
                        },
                        onDelete = { task ->
                            viewModel.deleteTask(task)
                            currentScreen = JiScreen.Home
                        }
                    )
                }
            }

            JiScreen.Account -> AccountScreen(
                authManager = authManager,
                authState = authState,
                onAuthStateChange = { state -> authState = state },
                onBack = { currentScreen = JiScreen.Home },
                onPrivacyClick = { currentScreen = JiScreen.Privacy }
            )

            JiScreen.Privacy -> PrivacySecurityScreen(
                onBack = { currentScreen = JiScreen.Account }
            )
        }

        if (showAddTaskMenu) {
            AddTaskActionSheet(
                isPro = authState.isPro,
                onDismiss = { showAddTaskMenu = false },
                onSelectMode = { mode ->
                    selectedAddMode = mode
                    showAddTaskMenu = false
                    currentScreen = JiScreen.AddTask
                }
            )
        }

        if (showPrivacyNotice) {
            PrivacyIntroDialog(
                onConfirm = {
                    privacyPrefs.edit().putBoolean(JiPrivacyAcknowledgedKey, true).apply()
                    showPrivacyNotice = false
                }
            )
        }
    }
}

private fun buildCategorySummary(
    category: TaskDashboardCategory,
    tasks: List<TaskEntity>
): TaskCategorySummary {
    val categoryTasks = tasks.filter { task -> inferTaskCategory(task) == category }
    val unfinishedTasks = categoryTasks
        .filter { task -> normalizeDraftStatus(task.status) == "未完成" }
        .sortedByImportance()
    val importantTask = unfinishedTasks.firstOrNull()

    return TaskCategorySummary(
        category = category,
        tasks = categoryTasks,
        unfinishedTasks = unfinishedTasks,
        importantTask = importantTask,
        urgentText = buildUrgentText(unfinishedTasks)
    )
}

private fun buildUrgentText(tasks: List<TaskEntity>): String {
    val dueInfos = tasks.map { task -> taskDueInfo(task) }
    val todayCount = dueInfos.count { it.bucket == TaskDueBucket.Today }
    val tomorrowCount = dueInfos.count { it.bucket == TaskDueBucket.Tomorrow }
    val soonCount = dueInfos.count { it.bucket == TaskDueBucket.Soon }
    val overdueCount = dueInfos.count { it.bucket == TaskDueBucket.Overdue }

    return when {
        todayCount > 0 -> "$todayCount 个今天截止"
        tomorrowCount > 0 -> "$tomorrowCount 个明天截止"
        soonCount > 0 -> "$soonCount 个临期"
        overdueCount > 0 -> "$overdueCount 个已逾期"
        tasks.isNotEmpty() -> "保持推进"
        else -> "暂无待办"
    }
}

private fun List<TaskEntity>.sortedByImportance(): List<TaskEntity> =
    map { task ->
        val dueInfo = taskDueInfo(task)
        RankedTask(
            task = task,
            completionRank = taskCompletionRank(task),
            priorityRank = taskPriorityRank(task, dueInfo),
            dueInfo = dueInfo
        )
    }
        .sortedWith(
            compareBy<RankedTask> { ranked -> ranked.completionRank }
                .thenBy { ranked -> ranked.priorityRank }
                .thenBy { ranked -> ranked.dueInfo.date ?: LocalDate.MAX }
                .thenByDescending { ranked -> ranked.task.id }
        )
        .map { ranked -> ranked.task }

private fun buildTaskGroups(
    category: TaskDashboardCategory,
    tasks: List<TaskEntity>
): List<TaskGroupSummary> {
    return tasks
        .filter { task -> normalizeDraftStatus(task.status) == "未完成" }
        .groupBy { task -> taskGroupLabel(category, task) }
        .map { (label, groupTasks) ->
            TaskGroupSummary(
                label = label,
                tasks = groupTasks.sortedByImportance()
            )
        }
        .sortedWith(
            compareBy<TaskGroupSummary> { group ->
                group.tasks.firstOrNull()?.let { taskPriorityRank(it, taskDueInfo(it)) } ?: TaskDueBucket.None.priority
            }.thenBy { group -> group.label }
        )
}

private fun inferTaskCategory(task: TaskEntity): TaskDashboardCategory {
    val text = taskSearchText(task)
    val explicitCategory = extractTaskField(task.note, "分类")
    if (explicitCategory.isNotBlank()) {
        return when (normalizeDraftCategory(explicitCategory, extractTaskField(task.note, "类型"))) {
            "学习" -> TaskDashboardCategory.Study
            "工作" -> TaskDashboardCategory.Work
            "生活" -> TaskDashboardCategory.Life
            else -> TaskDashboardCategory.Other
        }
    }

    val hasWorkSignal = containsAny(
        text,
        listOf("工作", "面试", "实习", "简历", "会议", "项目", "材料提交", "入职", "培训", "申请", "hr", "公司", "岗位", "offer")
    )
    val hasLifeSignal = containsAny(
        text,
        listOf("生活", "快递", "缴费", "水电费", "取件", "购物", "出行", "健康", "运动", "生活提醒", "预约", "还款", "花呗", "支付宝", "微信")
    )
    return when {
        hasWorkSignal -> TaskDashboardCategory.Work
        hasLifeSignal -> TaskDashboardCategory.Life
        containsAny(
            text,
            listOf(
                "学习", "课程", "课程名", "课件", "作业", "题库作业", "手动出题", "前往作业",
                "章节测试", "测试", "考试", "成绩", "论文", "ppt", "报告", "学习通", "智慧职教", "班级学习", "雨课堂"
            )
        ) || (task.course.isNotBlank() && !hasWorkSignal && !hasLifeSignal) -> TaskDashboardCategory.Study
        else -> TaskDashboardCategory.Other
    }
}

private fun taskGroupLabel(category: TaskDashboardCategory, task: TaskEntity): String {
    return when (category) {
        TaskDashboardCategory.Study -> task.course
            .ifBlank { extractTaskField(task.note, "课程") }
            .ifBlank { "未分类课程" }
        TaskDashboardCategory.Work -> firstNonBlank(
            extractTaskField(task.note, "公司/岗位"),
            extractTaskField(task.note, "公司"),
            extractTaskField(task.note, "项目"),
            inferSubType(task, category).ifBlank { "工作任务" }
        )
        TaskDashboardCategory.Life -> firstNonBlank(
            extractTaskField(task.note, "购买清单"),
            extractTaskField(task.note, "缴费项目"),
            extractTaskField(task.note, "事项"),
            inferSubType(task, category).ifBlank { "生活事项" }
        )
        TaskDashboardCategory.Other -> inferSubType(task, category).ifBlank { "未分类" }
    }.take(32)
}

private fun inferSubType(task: TaskEntity, category: TaskDashboardCategory): String {
    val text = taskSearchText(task)
    return when (category) {
        TaskDashboardCategory.Study -> when {
            containsAny(text, listOf("作业", "题库作业", "手动出题", "前往作业", "未做", "作业时间", "课程任务")) -> "作业"
            containsAny(text, listOf("考试", "测验")) -> "考试"
            containsAny(text, listOf("论文")) -> "论文"
            containsAny(text, listOf("ppt", "汇报")) -> "PPT"
            containsAny(text, listOf("小组", "课题")) -> "小组任务"
            containsAny(text, listOf("实训")) -> "实训"
            containsAny(text, listOf("报告")) -> "报告"
            else -> ""
        }
        TaskDashboardCategory.Work -> when {
            containsAny(text, listOf("面试", "笔试", "群面", "终面", "hr", "岗位")) -> "面试"
            containsAny(text, listOf("会议", "开会", "讨论", "例会", "项目会")) -> "会议"
            containsAny(text, listOf("项目")) -> "项目"
            containsAny(text, listOf("实习", "入职")) -> "实习"
            containsAny(text, listOf("材料提交", "简历", "材料")) -> "材料提交"
            else -> ""
        }
        TaskDashboardCategory.Life -> when {
            containsAny(text, listOf("缴费", "支付", "付款", "费用", "报名费", "水电费", "欠费", "还款", "花呗")) -> "缴费"
            containsAny(text, listOf("买", "购物", "清单")) -> "购物"
            containsAny(text, listOf("快递", "取件")) -> "快递"
            containsAny(text, listOf("提醒", "预约", "出行", "健康", "运动")) -> "提醒"
            else -> ""
        }
        TaskDashboardCategory.Other -> extractTaskField(task.note, "类型").ifBlank { "提醒" }
    }
}

private fun taskSupportText(task: TaskEntity): String {
    val platformText = extractTaskField(task.note, "平台").let { platform ->
        if (platform.isBlank()) "" else "平台：$platform"
    }
    val materialsText = extractTaskField(task.note, "材料").let { materials ->
        if (materials.isBlank()) "" else "材料：$materials"
    }
    val locationText = extractTaskField(task.note, "地点").let { location ->
        if (location.isBlank()) "" else "地点：$location"
    }
    val plainNote = task.note.lineSequence()
        .map { line -> line.trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                !line.startsWith("分类：") &&
                !line.startsWith("类型：") &&
                !line.startsWith("重要性：")
        }
        .orEmpty()

    return when (inferTaskCategory(task)) {
        TaskDashboardCategory.Study -> firstNonBlank(
            task.course,
            extractTaskField(task.note, "课程"),
            platformText,
            materialsText,
            locationText,
            plainNote
        )
        TaskDashboardCategory.Work -> firstNonBlank(
            extractTaskField(task.note, "公司/岗位"),
            extractTaskField(task.note, "公司"),
            extractTaskField(task.note, "项目"),
            task.course,
            locationText,
            materialsText,
            platformText,
            plainNote
        )
        TaskDashboardCategory.Life -> firstNonBlank(
            extractTaskField(task.note, "购买清单"),
            extractTaskField(task.note, "缴费项目"),
            extractTaskField(task.note, "事项"),
            extractTaskField(task.note, "来源/事项"),
            locationText,
            platformText,
            plainNote
        )
        TaskDashboardCategory.Other -> firstNonBlank(
            platformText,
            materialsText,
            locationText,
            plainNote,
            task.course
        )
    }.take(42)
}

private fun taskSearchText(task: TaskEntity): String =
    listOf(task.title, task.course, task.dueTime, task.note)
        .joinToString(" ")
        .lowercase()

private fun taskCompletionRank(task: TaskEntity): Int =
    if (normalizeDraftStatus(task.status) == "已完成") 1 else 0

private fun isHighImportanceTask(task: TaskEntity): Boolean {
    val explicitImportance = extractTaskField(task.note, "重要性")
    return normalizeDraftImportance(explicitImportance, taskSearchText(task)) == "高"
}

private fun shouldHighlightTopPriorityTask(task: TaskEntity): Boolean {
    val dueBucket = taskDueInfo(task).bucket
    return dueBucket == TaskDueBucket.Today ||
        dueBucket == TaskDueBucket.Overdue ||
        (isHighImportanceTask(task) && dueBucket in listOf(TaskDueBucket.Tomorrow, TaskDueBucket.Soon))
}

private fun taskPriorityRank(task: TaskEntity, dueInfo: TaskDueInfo): Int {
    val isHighImportance = isHighImportanceTask(task)
    return when {
        dueInfo.bucket == TaskDueBucket.Today -> 0
        isHighImportance && dueInfo.bucket in listOf(TaskDueBucket.Tomorrow, TaskDueBucket.Soon) -> 1
        dueInfo.bucket in listOf(TaskDueBucket.Tomorrow, TaskDueBucket.Soon) -> 2
        isHighImportance -> 3
        dueInfo.bucket == TaskDueBucket.Dated -> 4
        dueInfo.bucket == TaskDueBucket.None -> 5
        dueInfo.bucket == TaskDueBucket.Overdue -> 6
        else -> 5
    }
}

private fun containsAny(text: String, words: List<String>): Boolean =
    words.any { word -> text.contains(word.lowercase()) }

private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }.orEmpty()

private fun extractTaskField(note: String, label: String): String {
    val normalizedLabel = "$label："
    val asciiLabel = "$label:"
    val line = note
        .lineSequence()
        .map { line -> line.trim() }
        .firstOrNull { line -> line.startsWith(normalizedLabel) || line.startsWith(asciiLabel) }
        ?: return ""

    return when {
        line.startsWith(normalizedLabel) -> line.removePrefix(normalizedLabel).trim()
        line.startsWith(asciiLabel) -> line.removePrefix(asciiLabel).trim()
        else -> ""
    }
}

private fun taskDueInfo(task: TaskEntity): TaskDueInfo {
    val dueDate = parseTaskDueDate(task.dueTime.ifBlank { task.note }.ifBlank { task.title })
        ?: return TaskDueInfo(TaskDueBucket.None)
    val today = LocalDate.now()

    return when {
        dueDate.isBefore(today) -> TaskDueInfo(TaskDueBucket.Overdue, dueDate)
        dueDate == today -> TaskDueInfo(TaskDueBucket.Today, dueDate)
        dueDate == today.plusDays(1) -> TaskDueInfo(TaskDueBucket.Tomorrow, dueDate)
        !dueDate.isAfter(today.plusDays(3)) -> TaskDueInfo(TaskDueBucket.Soon, dueDate)
        else -> TaskDueInfo(TaskDueBucket.Dated, dueDate)
    }
}

private fun parseTaskDueDate(text: String): LocalDate? {
    val today = LocalDate.now()
    if (containsAny(text, listOf("今天", "今晚"))) return today
    if (text.contains("明天")) return today.plusDays(1)

    Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
        .find(text)
        ?.let { match ->
            return runCatching {
                LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt()
                )
            }.getOrNull()
        }

    Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?""")
        .find(text)
        ?.let { match ->
            return runCatching {
                LocalDate.of(today.year, match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }.getOrNull()
        }

    Regex("""(?<!\d)(\d{1,2})[.](\d{1,2})(?!\d)""")
        .find(text)
        ?.let { match ->
            return runCatching {
                LocalDate.of(today.year, match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }.getOrNull()
        }

    return null
}

@Composable
private fun categoryAccentColor(category: TaskDashboardCategory): Color =
    categoryTone(category).color

@Composable
private fun categoryTone(category: TaskDashboardCategory): JiTone =
    when (category) {
        TaskDashboardCategory.Study -> JiTone(Study, StudyBackground)
        TaskDashboardCategory.Work -> JiTone(Work, WorkBackground)
        TaskDashboardCategory.Life -> JiTone(Life, LifeBackground)
        TaskDashboardCategory.Other -> JiTone(Other, OtherBackground)
    }

private fun groupTone(category: TaskDashboardCategory, task: TaskEntity?): JiTone {
    return when (category) {
        TaskDashboardCategory.Study -> JiTone(Study, StudyBackground)
        TaskDashboardCategory.Work -> JiTone(Work, WorkBackground)
        TaskDashboardCategory.Life -> JiTone(Life, LifeBackground)
        TaskDashboardCategory.Other -> JiTone(Other, OtherBackground)
    }
}

private fun HomeTaskTab.toDashboardCategory(): TaskDashboardCategory? =
    when (this) {
        HomeTaskTab.Priority -> null
        HomeTaskTab.Study -> TaskDashboardCategory.Study
        HomeTaskTab.Work -> TaskDashboardCategory.Work
        HomeTaskTab.Life -> TaskDashboardCategory.Life
        HomeTaskTab.Other -> TaskDashboardCategory.Other
    }

private fun taskUrgencyLabel(task: TaskEntity): String =
    when (taskDueInfo(task).bucket) {
        TaskDueBucket.Today -> "今天截止"
        TaskDueBucket.Tomorrow, TaskDueBucket.Soon -> "临期"
        TaskDueBucket.Overdue -> "已逾期"
        else -> ""
    }

private fun draftDueInfo(draft: ParsedTaskDraft): TaskDueInfo {
    val dueDate = parseTaskDueDate(draft.dueTime.ifBlank { draft.time }.ifBlank { draft.rawText })
        ?: return TaskDueInfo(TaskDueBucket.None)
    val today = LocalDate.now()

    return when {
        dueDate.isBefore(today) -> TaskDueInfo(TaskDueBucket.Overdue, dueDate)
        dueDate == today -> TaskDueInfo(TaskDueBucket.Today, dueDate)
        dueDate == today.plusDays(1) -> TaskDueInfo(TaskDueBucket.Tomorrow, dueDate)
        !dueDate.isAfter(today.plusDays(3)) -> TaskDueInfo(TaskDueBucket.Soon, dueDate)
        else -> TaskDueInfo(TaskDueBucket.Dated, dueDate)
    }
}

private fun draftUrgencyLabel(draft: ParsedTaskDraft): String =
    when (draftDueInfo(draft).bucket) {
        TaskDueBucket.Today -> "今天截止"
        TaskDueBucket.Tomorrow, TaskDueBucket.Soon -> "临期"
        TaskDueBucket.Overdue -> "已逾期"
        else -> ""
    }

@Composable
private fun taskUrgencyColor(task: TaskEntity): Color =
    taskStatusTone(task).color

@Composable
private fun draftUrgencyColor(draft: ParsedTaskDraft): Color =
    when (draftDueInfo(draft).bucket) {
        TaskDueBucket.Today -> HighImportance
        TaskDueBucket.Tomorrow, TaskDueBucket.Soon -> Urgent
        TaskDueBucket.Overdue -> HighImportance
        else -> NormalTask
    }

@Composable
private fun taskAccentColor(task: TaskEntity): Color {
    return taskStatusTone(task).color
}

private fun taskStatusTone(task: TaskEntity): JiTone {
    val urgency = taskDueInfo(task).bucket
    return when {
        normalizeDraftStatus(task.status) == "已完成" -> JiTone(Completed, CompletedBackground)
        urgency == TaskDueBucket.Overdue -> JiTone(HighImportance, HighImportanceBackground)
        urgency == TaskDueBucket.Today -> JiTone(HighImportance, HighImportanceBackground)
        urgency in listOf(TaskDueBucket.Tomorrow, TaskDueBucket.Soon) -> JiTone(Urgent, UrgentBackground)
        isHighImportanceTask(task) -> JiTone(Important, ImportantBackground)
        else -> JiTone(NormalTask, NormalTaskBackground)
    }
}

private fun taskPrimaryStatusLabel(task: TaskEntity): String {
    if (normalizeDraftStatus(task.status) == "已完成") return "已完成"
    val urgencyLabel = taskUrgencyLabel(task)
    return when {
        urgencyLabel.isNotBlank() -> urgencyLabel
        isHighImportanceTask(task) -> "高重要"
        else -> "普通"
    }
}

@Composable
private fun JiSoftTag(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    if (text.isBlank()) return

    Surface(
        modifier = modifier,
        shape = JiTagShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrivacyHintText(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = JiButtonShape,
        color = NormalTaskBackground.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.72f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    tasks: List<TaskEntity>,
    onAddClick: () -> Unit,
    onOpenTask: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onClearArchivedTasks: (List<TaskEntity>) -> Unit,
    onAccountClick: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(HomeTaskTab.Priority) }
    val completedTasks = remember(tasks) {
        tasks
            .filter { task -> normalizeDraftStatus(task.status) == "已完成" }
            .sortedByDescending { task -> task.id }
    }
    val overdueTasks = remember(tasks) {
        tasks
            .filter { task ->
                normalizeDraftStatus(task.status) == "未完成" &&
                    taskDueInfo(task).bucket == TaskDueBucket.Overdue
            }
            .sortedWith(
                compareBy<TaskEntity> { task -> taskDueInfo(task).date ?: LocalDate.MIN }
                    .thenByDescending { task -> task.id }
            )
    }
    val activeTasks = remember(tasks, overdueTasks) {
        val overdueIds = overdueTasks.map { task -> task.id }.toSet()
        tasks.filterNot { task -> task.id in overdueIds }
    }
    val categorySummaries = remember(activeTasks) {
        val startedAt = System.currentTimeMillis()
        val result = TaskDashboardCategory.values().map { category ->
            buildCategorySummary(category, activeTasks)
        }
        jiDebugLog { "[HOME_RECALC] categoryMs=${System.currentTimeMillis() - startedAt}, tasks=${activeTasks.size}" }
        result
    }
    val priorityTasks = remember(activeTasks) {
        val startedAt = System.currentTimeMillis()
        val result = activeTasks
            .filter { task -> normalizeDraftStatus(task.status) == "未完成" }
            .sortedByImportance()
            .take(12)
        jiDebugLog { "[HOME_RECALC] priorityMs=${System.currentTimeMillis() - startedAt}, tasks=${activeTasks.size}" }
        result
    }
    val primaryPriorityTask = remember(priorityTasks) { priorityTasks.firstOrNull() }
    val secondaryPriorityTasks = remember(priorityTasks) { priorityTasks.drop(1).take(2) }
    val nextPriorityTasks = remember(priorityTasks) { priorityTasks.drop(3) }
    val tabCounts = remember(categorySummaries, priorityTasks) {
        mapOf(
            HomeTaskTab.Priority to priorityTasks.take(5).size,
            HomeTaskTab.Study to (categorySummaries.firstOrNull { it.category == TaskDashboardCategory.Study }?.unfinishedTasks?.size ?: 0),
            HomeTaskTab.Work to (categorySummaries.firstOrNull { it.category == TaskDashboardCategory.Work }?.unfinishedTasks?.size ?: 0),
            HomeTaskTab.Life to (categorySummaries.firstOrNull { it.category == TaskDashboardCategory.Life }?.unfinishedTasks?.size ?: 0),
            HomeTaskTab.Other to (categorySummaries.firstOrNull { it.category == TaskDashboardCategory.Other }?.unfinishedTasks?.size ?: 0)
        )
    }
    val selectedCategory = selectedTab.toDashboardCategory()
    val selectedCategoryGroups = remember(selectedTab, categorySummaries) {
        selectedCategory?.let { category ->
            categorySummaries
                .firstOrNull { summary -> summary.category == category }
                ?.let { summary -> buildTaskGroups(category, summary.tasks) }
                .orEmpty()
        }.orEmpty()
    }
    val selectedCategoryRows = remember(selectedCategoryGroups) { selectedCategoryGroups.chunked(2) }
    var selectedPanelCategory by remember { mutableStateOf<TaskDashboardCategory?>(null) }
    var selectedPanelGroup by remember { mutableStateOf<TaskGroupSummary?>(null) }
    var selectedPanelAnchor by remember { mutableStateOf<FloatingPanelAnchor?>(null) }
    var selectedArchiveKind by remember { mutableStateOf<TaskArchiveKind?>(null) }
    var detailSheetTaskId by remember { mutableStateOf<Long?>(null) }
    val detailSheetTask = remember(detailSheetTaskId, tasks) {
        detailSheetTaskId?.let { taskId ->
            tasks.firstOrNull { task -> task.id == taskId }
        }
    }

    LaunchedEffect(tasks.size) {
        jiDebugLog { "[ROOM_TASKS_LOADED] count=${tasks.size}" }
    }

    selectedPanelGroup?.let { group ->
        TaskFloatingPanel(
            category = selectedPanelCategory,
            group = group,
            anchor = selectedPanelAnchor,
            selectedTask = null,
            onDismiss = {
                selectedPanelCategory = null
                selectedPanelGroup = null
                selectedPanelAnchor = null
            },
            onShowTaskDetail = { task -> detailSheetTaskId = task.id },
            onBackToList = {},
            onEditTask = { task ->
                selectedPanelCategory = null
                selectedPanelGroup = null
                selectedPanelAnchor = null
                detailSheetTaskId = null
                onOpenTask(task)
            },
            onToggleTaskStatus = onToggleTaskStatus,
            onDeleteTask = { task ->
                onDeleteTask(task)
                if (detailSheetTaskId == task.id) {
                    detailSheetTaskId = null
                }
            }
        )
    }

    detailSheetTask?.let { task ->
        TaskDetailBottomSheet(
            task = task,
            onDismiss = { detailSheetTaskId = null },
            onEdit = {
                detailSheetTaskId = null
                selectedPanelCategory = null
                selectedPanelGroup = null
                selectedPanelAnchor = null
                onOpenTask(task)
            },
            onToggleStatus = { onToggleTaskStatus(task) },
            onDelete = {
                onDeleteTask(task)
                detailSheetTaskId = null
            }
        )
    }

    selectedArchiveKind?.let { kind ->
        val archiveTasks = when (kind) {
            TaskArchiveKind.Overdue -> overdueTasks
            TaskArchiveKind.Completed -> completedTasks
        }
        TaskArchiveBottomSheet(
            kind = kind,
            tasks = archiveTasks,
            onDismiss = { selectedArchiveKind = null },
            onShowTaskDetail = { task ->
                selectedArchiveKind = null
                detailSheetTaskId = task.id
            },
            onToggleTaskStatus = onToggleTaskStatus,
            onDeleteTask = onDeleteTask,
            onClearTasks = {
                onClearArchivedTasks(archiveTasks)
                selectedArchiveKind = null
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "记",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    AccountTopButton(onClick = onAccountClick)
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 138.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "home-tabs", contentType = "home-tabs") {
                    HomeTabBar(
                        selectedTab = selectedTab,
                        counts = tabCounts,
                        onSelected = {
                            jiDebugLog { "[HOME_TAB_SWITCH] to=${it.title}" }
                            selectedTab = it
                        }
                    )
                }

                when (selectedTab) {
                    HomeTaskTab.Priority -> {
                        item(key = "priority-header", contentType = "home-section-header") {
                            HomeSectionHeader(
                                title = "优先",
                                subtitle = "最重要的任务优先处理"
                            )
                        }
                        if (priorityTasks.isEmpty()) {
                            item(key = "priority-empty", contentType = "empty-card") {
                                EmptyHomeCard(text = "现在没有需要优先处理的任务")
                            }
                        } else {
                            primaryPriorityTask?.let { task ->
                                item(key = "priority-main-${task.id}", contentType = "priority-main") {
                                    PriorityTaskCard(
                                        task = task,
                                        highlightUrgent = shouldHighlightTopPriorityTask(task),
                                        onOpen = { detailSheetTaskId = task.id },
                                        onToggleStatus = { onToggleTaskStatus(task) },
                                        onDelete = { onDeleteTask(task) }
                                    )
                                }
                            }
                            items(
                                items = secondaryPriorityTasks,
                                key = { task -> "priority-compact-${task.id}" },
                                contentType = { "priority-compact" }
                            ) { task ->
                                PriorityTaskCard(
                                    task = task,
                                    compact = true,
                                    onOpen = { detailSheetTaskId = task.id },
                                    onToggleStatus = { onToggleTaskStatus(task) },
                                    onDelete = { onDeleteTask(task) }
                                )
                            }
                            if (nextPriorityTasks.isNotEmpty()) {
                                item(key = "priority-next", contentType = "next-stack") {
                                    NextTasksStackCard(
                                        tasks = nextPriorityTasks,
                                        onClick = { anchor ->
                                            selectedPanelCategory = TaskDashboardCategory.Other
                                            selectedPanelGroup = TaskGroupSummary(
                                                label = "接下来要处理",
                                                tasks = nextPriorityTasks
                                            )
                                            selectedPanelAnchor = anchor
                                        }
                                    )
                                }
                            }
                        }
                        item(key = "priority-hint", contentType = "priority-hint") {
                            PriorityTabCategoryHint()
                        }
                        if (overdueTasks.isNotEmpty()) {
                            item(key = "overdue-overview", contentType = "archive-overview") {
                                ArchiveOverviewCard(
                                    kind = TaskArchiveKind.Overdue,
                                    count = overdueTasks.size,
                                    onClick = { selectedArchiveKind = TaskArchiveKind.Overdue }
                                )
                            }
                        }
                        item(key = "completed-overview", contentType = "archive-overview") {
                            ArchiveOverviewCard(
                                kind = TaskArchiveKind.Completed,
                                count = completedTasks.size,
                                onClick = { selectedArchiveKind = TaskArchiveKind.Completed }
                            )
                        }
                    }

                    else -> {
                        val category = selectedCategory ?: TaskDashboardCategory.Study
                        item(key = "category-header-${selectedTab.name}", contentType = "home-section-header") {
                            HomeSectionHeader(
                                title = selectedTab.title,
                                subtitle = when (category) {
                                    TaskDashboardCategory.Study -> "按课程分类，轻松管理学习任务"
                                    TaskDashboardCategory.Work -> "按公司 / 项目 / 场景分类，高效推进"
                                    TaskDashboardCategory.Life -> "按购物、缴费、提醒等事项查看"
                                    TaskDashboardCategory.Other -> "按类型归拢，减少打扰"
                                }
                            )
                        }
                        if (selectedCategoryGroups.isEmpty()) {
                            item(key = "category-empty-${selectedTab.name}", contentType = "empty-card") {
                                EmptyHomeCard(text = "这个分类暂时清爽")
                            }
                        } else {
                            items(
                                items = selectedCategoryRows,
                                key = { rowGroups -> rowGroups.joinToString("|") { group -> group.label } },
                                contentType = { "category-row" }
                            ) { rowGroups ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowGroups.forEach { group ->
                                        CategoryGroupSummaryCard(
                                            category = category,
                                            group = group,
                                            modifier = Modifier.weight(1f),
                                            onClick = { anchor ->
                                                selectedPanelCategory = category
                                                selectedPanelGroup = group
                                                selectedPanelAnchor = anchor
                                            }
                                        )
                                    }
                                    if (rowGroups.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingAddTaskButton(
                onClick = onAddClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun AccountTopButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(end = 12.dp)
            .clickable(onClick = onClick),
        shape = JiButtonShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        shadowElevation = 1.dp
    ) {
        Text(
            text = "我的",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FloatingAddTaskButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberJiPressScale(interactionSource)
    val anchorCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }

    Surface(
        modifier = modifier
            .width(206.dp)
            .height(60.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 5.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "+ 添加任务",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailBottomSheet(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val category = inferTaskCategory(task)
    val subType = inferSubType(task, category).ifBlank { extractTaskField(task.note, "类型") }.ifBlank { "任务" }
    val tone = taskStatusTone(task)
    val detailRows = remember(task) { taskDetailRows(task) }
    val detailText = remember(task) { taskReadableDetailText(task) }

    LaunchedEffect(task.id) {
        jiDebugLog { "[TASK_DETAIL_SHEET_OPEN] id=${task.id}" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.18f),
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(44.dp)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JiSoftTag(
                                text = taskPrimaryStatusLabel(task),
                                contentColor = tone.color,
                                containerColor = tone.background
                            )
                            JiSoftTag(
                                text = category.title,
                                contentColor = categoryAccentColor(category),
                                containerColor = categoryTone(category).background
                            )
                            JiSoftTag(
                                text = subType,
                                contentColor = MaterialTheme.colorScheme.primary,
                                containerColor = NormalTaskBackground
                            )
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "关闭")
                    }
                }

                if (detailRows.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = JiCompactCardShape,
                        colors = JiCardColors(),
                        border = JiCardBorder(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "任务信息",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            detailRows.forEach { (label, value) ->
                                DetailInfoRow(label = label, value = value)
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = JiCompactCardShape,
                    colors = JiCardColors(),
                    border = JiCardBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "详情说明",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = detailText.ifBlank { "暂无补充说明" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (detailText.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = "编辑")
                }
                Button(
                    onClick = onToggleStatus,
                    modifier = Modifier.weight(1f),
                    shape = JiButtonShape,
                    colors = JiPrimaryButtonColors()
                ) {
                    Text(text = if (normalizeDraftStatus(task.status) == "未完成") "完成" else "设为未完成")
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(0.8f),
                    shape = JiButtonShape,
                    colors = JiDeleteTextButtonColors()
                ) {
                    Text(text = "删除")
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun taskDetailRows(task: TaskEntity): List<Pair<String, String>> {
    val category = inferTaskCategory(task)
    val rows = mutableListOf<Pair<String, String>>()
    fun add(label: String, value: String) {
        val cleanValue = value.trim()
        if (label.isNotBlank() && cleanValue.isNotBlank() && rows.none { it.first == label && it.second == cleanValue }) {
            rows += label to cleanValue
        }
    }

    add("时间", task.dueTime)

    when (category) {
        TaskDashboardCategory.Study -> add(
            "课程",
            firstNonBlank(task.course, extractTaskField(task.note, "课程"))
        )
        TaskDashboardCategory.Work -> add(
            "公司/岗位",
            firstNonBlank(
                extractTaskField(task.note, "公司/岗位"),
                extractTaskField(task.note, "公司"),
                extractTaskField(task.note, "项目"),
                task.course
            )
        )
        TaskDashboardCategory.Life -> add(
            "事项",
            firstNonBlank(
                extractTaskField(task.note, "购买清单"),
                extractTaskField(task.note, "缴费项目"),
                extractTaskField(task.note, "事项"),
                extractTaskField(task.note, "来源/事项"),
                task.course
            )
        )
        TaskDashboardCategory.Other -> add(
            "来源",
            firstNonBlank(extractTaskField(task.note, "来源/事项"), task.course)
        )
    }

    add("平台", extractTaskField(task.note, "平台"))
    add("地点", extractTaskField(task.note, "地点"))
    add("材料", extractTaskField(task.note, "材料"))
    add("重要性", extractTaskField(task.note, "重要性"))

    return rows
}

private fun taskReadableDetailText(task: TaskEntity): String {
    val metaLabels = setOf(
        "分类",
        "类型",
        "课程",
        "公司/岗位",
        "公司",
        "项目",
        "事项",
        "来源",
        "来源/事项",
        "购买清单",
        "缴费项目",
        "平台",
        "地点",
        "材料",
        "重要性",
        "状态"
    )
    return task.note
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }
        .filterNot { line ->
            val label = line.substringBefore("：").substringBefore(":").trim()
            label in metaLabels
        }
        .joinToString("\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskActionSheet(
    isPro: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: (AddTaskMode) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(50.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(
                text = "添加任务",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "选择一种方式，把待办整理进「记」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AddTaskActionItem(
                title = AddTaskMode.Manual.title,
                description = AddTaskMode.Manual.subtitle,
                accent = BrandBlue,
                containerColor = Color(0xFFFBFDFF),
                onClick = { onSelectMode(AddTaskMode.Manual) }
            )
            AddTaskActionItem(
                title = AddTaskMode.Paste.title,
                description = AddTaskMode.Paste.subtitle,
                accent = Important,
                containerColor = Color(0xFFFAF7FF),
                onClick = { onSelectMode(AddTaskMode.Paste) }
            )
            AddTaskActionItem(
                title = AddTaskMode.Image.title,
                description = AddTaskMode.Image.subtitle,
                accent = Completed,
                containerColor = Color(0xFFF2FCF7),
                onClick = { onSelectMode(AddTaskMode.Image) }
            )
            AddTaskActionItem(
                title = AddTaskMode.MultiImage.title,
                description = if (isPro) AddTaskMode.MultiImage.subtitle else "升级 Pro 后可一次整理多张截图",
                accent = ProGold,
                containerColor = Color(0xFFFFFAEE),
                badge = "Pro",
                onClick = { onSelectMode(AddTaskMode.MultiImage) }
            )
        }
    }
}

@Composable
private fun AddTaskActionItem(
    title: String,
    description: String,
    accent: Color,
    containerColor: Color,
    badge: String = "",
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    val iconBackground = accent.copy(alpha = 0.11f)
    val iconColor = accent
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberJiPressScale(interactionSource)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(containerColor, shape)
            .border(1.dp, accent.copy(alpha = 0.20f), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconBackground, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.take(1),
                    color = iconColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryText
                    )
                    if (badge.isNotBlank()) {
                        JiSoftTag(
                            text = badge,
                            contentColor = ProGold,
                            containerColor = ProGoldBackground
                        )
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = accent.copy(alpha = 0.52f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountScreen(
    authManager: AuthManager,
    authState: AuthState,
    onAuthStateChange: (AuthState) -> Unit,
    onBack: () -> Unit,
    onPrivacyClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var activationCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isRedeeming by remember { mutableStateOf(false) }
    var isRedeemSuccess by remember { mutableStateOf<Boolean?>(null) }
    var showRedeemSuccessDialog by remember { mutableStateOf(false) }

    LaunchedEffect(authManager) {
        authManager.fetchMe()?.let(onAuthStateChange)
    }

    if (showRedeemSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showRedeemSuccessDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = JiCardShape,
            title = {
                Text(text = "Pro 已开通", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "内测阶段 Pro 状态绑定当前设备的匿名账号。请不要卸载 App、清除 App 数据或更换设备使用，否则可能需要人工恢复。若状态异常，请保留激活码和用户 ID 联系开发者。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { showRedeemSuccessDialog = false },
                    shape = JiButtonShape,
                    colors = JiPrimaryButtonColors()
                ) {
                    Text(text = "我知道了")
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "我的",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "‹")
                    }
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = JiCardShape,
                colors = JiCardColors(),
                border = JiCardBorder(),
                elevation = JiCardElevation()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (authState.isPro) "Pro 会员" else "Free 版本",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "今日识别 ${authState.usedToday}/${authState.dailyQuota} 次",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        JiSoftTag(
                            text = if (authState.isPro) "高精度" else "基础",
                            contentColor = if (authState.isPro) ProGold else MaterialTheme.colorScheme.primary,
                            containerColor = if (authState.isPro) ProGoldBackground else NormalTaskBackground
                        )
                    }

                    TaskInfoRow(
                        label = "Pro 到期",
                        value = authState.proExpiresAt ?: "未开通"
                    )
                    TaskInfoRow(
                        label = "用户 ID",
                        value = authState.userId.ifBlank { "初始化中" }
                    )
                    Text(
                        text = "内测提醒：Pro 状态绑定当前匿名账号。请勿卸载 App 或清除 App 数据。后续版本将支持手机号绑定和账号恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (authState.requiresRecovery) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            color = HighImportanceBackground,
                            border = BorderStroke(1.dp, HighImportance.copy(alpha = 0.18f))
                        ) {
                            Text(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                text = "账号状态需要恢复，请保留激活码和用户 ID 联系开发者。",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = HighImportance
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPrivacyClick),
                shape = JiCompactCardShape,
                colors = JiCardColors(),
                border = JiCardBorder(),
                elevation = JiCardElevation()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "隐私与安全",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "了解图片、文本和本地任务如何被处理",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = JiCompactCardShape,
                colors = JiCardColors(),
                border = JiCardBorder(),
                elevation = JiCardElevation()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "激活 Pro",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = activationCode,
                        onValueChange = {
                            activationCode = it
                                .uppercase()
                                .replace(" ", "")
                                .replace("－", "-")
                                .replace("—", "-")
                                .replace("–", "-")
                            message = ""
                            isRedeemSuccess = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("激活码") },
                        singleLine = true,
                        shape = JiCompactCardShape,
                        colors = JiOutlinedTextFieldColors()
                    )
                    Button(
                        onClick = {
                            if (activationCode.isBlank() || isRedeeming) return@Button
                            scope.launch {
                                isRedeeming = true
                                message = ""
                                isRedeemSuccess = null
                                authManager.redeemCode(activationCode)
                                    .onSuccess { state ->
                                        onAuthStateChange(state)
                                        activationCode = ""
                                        isRedeemSuccess = true
                                        showRedeemSuccessDialog = true
                                        message = "Pro 已激活，权益已刷新"
                                    }
                                    .onFailure { error ->
                                        isRedeemSuccess = false
                                        message = error.localizedMessage ?: "兑换失败，请检查激活码后重试"
                                    }
                                isRedeeming = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = JiButtonShape,
                        colors = JiPrimaryButtonColors(),
                        enabled = activationCode.isNotBlank() && !isRedeeming
                    ) {
                        Text(text = if (isRedeeming) "正在兑换..." else "兑换激活码")
                    }
                    if (message.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            color = if (isRedeemSuccess == true) CompletedBackground else HighImportanceBackground,
                            border = BorderStroke(
                                1.dp,
                                if (isRedeemSuccess == true) Completed.copy(alpha = 0.2f) else HighImportance.copy(alpha = 0.18f)
                            )
                        ) {
                            Text(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isRedeemSuccess == true) Completed else HighImportance
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySecurityScreen(
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "隐私与安全",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "‹")
                    }
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PrivacySectionCard(
                title = "我们如何处理你的内容",
                paragraphs = listOf(
                    "「记」目前处于内测阶段，我们会尽量以最少的数据完成任务识别和整理。",
                    "当你使用截图识别、图片导入或多图识别时，图片会被发送到「记」的服务器，用于调用 AI 模型识别其中的待办事项。",
                    "当你使用粘贴识别时，你粘贴的文字会用于整理成任务。请尽量不要粘贴身份证号、银行卡号、密码、验证码、完整住址等敏感信息。"
                )
            )

            PrivacySectionCard(
                title = "会保存哪些数据",
                paragraphs = listOf(
                    "我们只会提取任务所需的信息，例如任务标题、分类、时间、地点、课程 / 项目 / 来源和备注。",
                    "我们不会在 App 中展示原始识别文本，也不会把原图作为任务内容长期保存。",
                    "你保存的任务会优先保存在手机本地数据库中。你可以随时在 App 内完成、编辑或删除任务。"
                )
            )

            PrivacySectionCard(
                title = "账号与模型安全",
                paragraphs = listOf(
                    "「记」使用匿名账号区分 Free / Pro 状态和每日识别次数。匿名账号不会要求手机号、邮箱或微信登录。",
                    "当前内测版本使用匿名账号记录 Free / Pro 状态和每日识别次数。请保存好激活码和“我的”页用户 ID；如卸载、清数据或换设备，Pro 状态可能需要人工恢复。",
                    "服务器只用于判断识别次数、判断 Free / Pro 状态、调用 AI 识别服务和返回结构化任务结果。",
                    "AI 模型的 API Key 只保存在服务器中，不会写入 Android App。App 只会访问「记」自己的后端接口。"
                )
            )

            PrivacySectionCard(
                title = "内测提醒",
                paragraphs = listOf(
                    "当前版本仍处于内测阶段，请避免上传特别敏感的截图，例如身份证、银行卡、护照、密码、验证码、密钥、医疗诊断、隐私聊天或涉及他人隐私的内容。",
                    "如果你发现识别错误、隐私问题或异常行为，可以通过内测反馈入口联系我们。"
                )
            )
        }
    }
}

@Composable
private fun PrivacySectionCard(
    title: String,
    paragraphs: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JiCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                )
            }
        }
    }
}

@Composable
private fun PrivacyIntroDialog(
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = JiCardShape,
        title = {
            Text(
                text = "隐私与安全说明",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "「记」会在你使用图片识别或粘贴识别时，将图片或文本发送到服务器进行 AI 解析，用于整理成任务。我们只提取任务标题、时间、地点、分类、备注等必要信息，不会把原图作为任务长期保存。请避免上传身份证、银行卡、密码、验证码、医疗记录等高度敏感内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = JiButtonShape,
                colors = JiPrimaryButtonColors()
            ) {
                Text(text = "我知道了，开始使用")
            }
        }
    )
}

@Composable
private fun HomeTabBar(
    selectedTab: HomeTaskTab,
    counts: Map<HomeTaskTab, Int>,
    onSelected: (HomeTaskTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeTaskTab.values().forEach { tab ->
            HomeTabPill(
                tab = tab,
                count = counts[tab] ?: 0,
                selected = selectedTab == tab,
                onClick = { onSelected(tab) }
            )
        }
    }
}

@Composable
private fun HomeTabPill(
    tab: HomeTaskTab,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (selected) BrandBlue else SecondaryText,
        animationSpec = tween(durationMillis = JiFastAnimationDuration, easing = FastOutSlowInEasing),
        label = "tab-text"
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) BrandBlue.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(durationMillis = JiFastAnimationDuration, easing = FastOutSlowInEasing),
        label = "tab-pill"
    )
    val badgeBackground by animateColorAsState(
        targetValue = if (selected) BrandBlue.copy(alpha = 0.12f) else TabInactiveBackground.copy(alpha = 0.72f),
        animationSpec = tween(durationMillis = JiFastAnimationDuration, easing = FastOutSlowInEasing),
        label = "tab-badge-bg"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (selected) BrandBlue else TertiaryText,
        animationSpec = tween(durationMillis = JiFastAnimationDuration, easing = FastOutSlowInEasing),
        label = "tab-badge"
    )
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 32.dp else 0.dp,
        animationSpec = tween(durationMillis = JiFastAnimationDuration, easing = FastOutSlowInEasing),
        label = "tab-indicator"
    )

    Column(
        modifier = Modifier
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Surface(
            shape = JiTagShape,
            color = pillColor,
            contentColor = textColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (selected) 10.dp else 0.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1
                )
                Surface(
                    shape = JiTagShape,
                    color = badgeBackground,
                    contentColor = badgeColor
                ) {
                    Text(
                        text = count.toString(),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .height(2.dp)
                .background(BrandBlue, RoundedCornerShape(50.dp))
        )
    }
}

@Composable
private fun JiPageGradient() = Brush.verticalGradient(
    colors = listOf(
        Color.White,
        PageBackground,
        NormalTaskBackground.copy(alpha = 0.50f)
    )
)

@Composable
private fun JiGlassPanelBrush() = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.96f),
        PageBackground.copy(alpha = 0.90f)
    )
)

private fun JiIconBrush(background: Color) = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.88f),
        background.copy(alpha = 0.96f)
    )
)

@Composable
private fun tabAccentColor(tab: HomeTaskTab): Color =
    MaterialTheme.colorScheme.primary

@Composable
private fun HomeTabContent(
    tab: HomeTaskTab,
    tasks: List<TaskEntity>,
    categorySummaries: List<TaskCategorySummary>,
    priorityTasks: List<TaskEntity>,
    onOpenTask: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onOpenGroup: (TaskDashboardCategory, TaskGroupSummary, FloatingPanelAnchor?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (tab) {
            HomeTaskTab.Priority -> {
                val primaryPriorityTask = priorityTasks.firstOrNull()
                val secondaryPriorityTasks = priorityTasks.drop(1).take(2)
                val nextPriorityTasks = priorityTasks.drop(3)
                val completedCount = remember(tasks) {
                    tasks.count { task -> normalizeDraftStatus(task.status) == "已完成" }
                }
                HomeSectionHeader(
                    title = "优先",
                    subtitle = "最重要的任务优先处理"
                )
                if (priorityTasks.isEmpty()) {
                    EmptyHomeCard(text = "现在没有需要优先处理的任务")
                } else {
                    primaryPriorityTask?.let { task ->
                        PriorityTaskCard(
                            task = task,
                            highlightUrgent = shouldHighlightTopPriorityTask(task),
                            onOpen = { onOpenTask(task) },
                            onToggleStatus = { onToggleTaskStatus(task) },
                            onDelete = { onDeleteTask(task) }
                        )
                    }
                    secondaryPriorityTasks.forEach { task ->
                        PriorityTaskCard(
                            task = task,
                            compact = true,
                            onOpen = { onOpenTask(task) },
                            onToggleStatus = { onToggleTaskStatus(task) },
                            onDelete = { onDeleteTask(task) }
                        )
                    }
                    if (nextPriorityTasks.isNotEmpty()) {
                        NextTasksStackCard(
                            tasks = nextPriorityTasks,
                            onClick = { anchor ->
                                onOpenGroup(
                                    TaskDashboardCategory.Other,
                                    TaskGroupSummary(
                                        label = "接下来要处理",
                                        tasks = nextPriorityTasks
                                    ),
                                    anchor
                                )
                            }
                        )
                    }
                }
                PriorityTabCategoryHint()
                CompletedOverviewCard(count = completedCount)
            }
            else -> {
                val category = tab.toDashboardCategory() ?: TaskDashboardCategory.Study
                val summary = categorySummaries.firstOrNull { it.category == category }
                val groups = remember(summary) {
                    summary?.let { buildTaskGroups(category, it.tasks) }.orEmpty()
                }
                HomeSectionHeader(
                    title = tab.title,
                    subtitle = when (category) {
                        TaskDashboardCategory.Study -> "按课程分类，轻松管理学习任务"
                        TaskDashboardCategory.Work -> "按公司 / 项目 / 场景分类，高效推进"
                        TaskDashboardCategory.Life -> "按购物、缴费、提醒等事项查看"
                        TaskDashboardCategory.Other -> "按类型归拢，减少打扰"
                    }
                )
                if (groups.isEmpty()) {
                    EmptyHomeCard(text = "这个分类暂时清爽")
                } else {
                    groups.chunked(2).forEach { rowGroups ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowGroups.forEach { group ->
                                CategoryGroupSummaryCard(
                                    category = category,
                                    group = group,
                                    modifier = Modifier.weight(1f),
                                    onClick = { anchor -> onOpenGroup(category, group, anchor) }
                                )
                            }
                            if (rowGroups.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyHomeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JiCompactCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

}

@Composable
private fun NextTasksStackCard(
    tasks: List<TaskEntity>,
    onClick: (FloatingPanelAnchor?) -> Unit
) {
    val firstTask = remember(tasks) { tasks.firstOrNull() }
    val soonCount = remember(tasks) {
        tasks.count { task ->
            taskDueInfo(task).bucket in listOf(TaskDueBucket.Today, TaskDueBucket.Tomorrow, TaskDueBucket.Soon)
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberJiPressScale(interactionSource)
    val anchorCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .onGloballyPositioned { coordinates ->
                anchorCoordinates[0] = coordinates
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onClick(anchorCoordinates[0]?.toFloatingPanelAnchorOrNull()) }
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 16.dp, start = 18.dp, end = 18.dp)
                .background(BrandBlue.copy(alpha = 0.055f), RoundedCornerShape(24.dp))
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp, start = 10.dp, end = 10.dp)
                .background(BrandBlue.copy(alpha = 0.075f), RoundedCornerShape(25.dp))
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            shape = RoundedCornerShape(26.dp),
            colors = JiCardColors(),
            border = JiCardBorder(),
            elevation = JiCardElevation()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TaskGlyph(
                    text = "待",
                    color = BrandBlue,
                    background = NormalTaskBackground
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "接下来要处理",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        JiSoftTag(text = "还有 ${tasks.size} 个待办")
                        if (soonCount > 0) {
                            JiSoftTag(
                                text = "$soonCount 个临期",
                                contentColor = Urgent,
                                containerColor = UrgentBackground
                            )
                        }
                    }
                    Text(
                        text = firstTask?.title ?: "点击查看完整列表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = TertiaryText
                )
            }
        }
    }
}

@Composable
private fun PriorityTabCategoryHint() {
    Text(
        text = "普通任务可在上方「学习 / 工作 / 生活 / 其他」分类中查看",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CompletedOverviewCard(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = JiCardColors(),
        border = BorderStroke(1.dp, Completed.copy(alpha = 0.16f)),
        elevation = JiCardElevation()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskGlyph(
                text = "✓",
                color = Completed,
                background = CompletedBackground
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已完成",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$count 个任务已完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            JiSoftTag(
                text = "完成区",
                contentColor = Completed,
                containerColor = CompletedBackground
            )
        }
    }
}

@Composable
private fun ArchiveOverviewCard(
    kind: TaskArchiveKind,
    count: Int,
    onClick: () -> Unit
) {
    val isOverdue = kind == TaskArchiveKind.Overdue
    val title = if (isOverdue) "已逾期" else "已完成"
    val subtitle = if (isOverdue) "$count 个任务已逾期" else "$count 个任务已完成"
    val tagText = if (isOverdue) "逾期区" else "完成区"
    val color = if (isOverdue) HighImportance else Completed
    val background = if (isOverdue) HighImportanceBackground else CompletedBackground

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = JiCardColors(),
        border = BorderStroke(1.dp, color.copy(alpha = 0.16f)),
        elevation = JiCardElevation()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskGlyph(
                text = if (isOverdue) "逾" else "✓",
                color = color,
                background = background
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            JiSoftTag(
                text = tagText,
                contentColor = color,
                containerColor = background
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskArchiveBottomSheet(
    kind: TaskArchiveKind,
    tasks: List<TaskEntity>,
    onDismiss: () -> Unit,
    onShowTaskDetail: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onClearTasks: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val isOverdue = kind == TaskArchiveKind.Overdue
    val title = if (isOverdue) "已逾期" else "已完成"
    val emptyText = if (isOverdue) "暂无逾期任务" else "暂无已完成任务"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = JiSheetShape,
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${tasks.size} 个任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onClearTasks,
                    enabled = tasks.isNotEmpty(),
                    shape = JiButtonShape,
                    colors = JiDeleteTextButtonColors()
                ) {
                    Text(text = "清空")
                }
            }

            if (tasks.isEmpty()) {
                EmptyHomeCard(text = emptyText)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = tasks,
                        key = { task -> task.id },
                        contentType = { "archive_task" }
                    ) { task ->
                        TaskSheetRow(
                            task = task,
                            onShowTaskDetail = onShowTaskDetail,
                            onToggleTaskStatus = onToggleTaskStatus,
                            onDeleteTask = onDeleteTask
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryGroupSummaryCard(
    category: TaskDashboardCategory,
    group: TaskGroupSummary,
    modifier: Modifier = Modifier,
    onClick: (FloatingPanelAnchor?) -> Unit
) {
    val firstTask = remember(group.tasks) { group.tasks.firstOrNull() }
    val tone = remember(category, firstTask) { groupTone(category, firstTask) }
    val accentColor = tone.color
    val urgentText = remember(group.tasks) { buildUrgentText(group.tasks) }
    val urgentTone = remember(group.tasks) {
        group.tasks.firstOrNull()?.let { taskStatusTone(it) } ?: JiTone(SecondaryText, TabInactiveBackground)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberJiPressScale(interactionSource)
    val anchorCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .onGloballyPositioned { coordinates ->
                anchorCoordinates[0] = coordinates
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onClick(anchorCoordinates[0]?.toFloatingPanelAnchorOrNull()) }
            ),
        shape = RoundedCornerShape(26.dp),
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 138.dp)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TaskGlyph(
                    text = group.label.take(1).ifBlank { category.title.take(1) },
                    color = accentColor,
                    background = tone.background
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = accentColor
                )
            }

            Text(
                text = group.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                JiSoftTag(
                    text = "${group.tasks.size} 个任务",
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    containerColor = TabInactiveBackground
                )
                if (urgentText.isNotBlank() && urgentText != "保持推进") {
                    JiSoftTag(
                        text = urgentText,
                        contentColor = urgentTone.color,
                        containerColor = urgentTone.background
                    )
                }
            }

            if (firstTask != null) {
                Text(
                    text = "最近：${firstTask.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (firstTask.dueTime.isNotBlank()) {
                    Text(
                        text = firstTask.dueTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityTaskCard(
    task: TaskEntity,
    compact: Boolean = false,
    highlightUrgent: Boolean = false,
    onOpen: () -> Unit,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit
) {
    val tone = remember(task) { taskStatusTone(task) }
    val accentColor = tone.color
    val statusLabel = remember(task) { taskPrimaryStatusLabel(task) }
    val category = remember(task) { inferTaskCategory(task) }
    val subType = remember(task, category) { inferSubType(task, category) }
    val supportText = remember(task) { taskSupportText(task) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberJiPressScale(interactionSource)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen
            ),
        shape = RoundedCornerShape(if (compact) 22.dp else 24.dp),
        colors = JiCardColors(),
        border = if (highlightUrgent) {
            BorderStroke(1.25.dp, Color(0xFFFCA5A5))
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        elevation = JiCardElevation()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(if (compact) 3.dp else 4.dp)
                    .height(if (compact) 58.dp else 78.dp)
                    .background(accentColor.copy(alpha = if (compact) 0.68f else 0.82f), RoundedCornerShape(50.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (!compact) {
                TaskGlyph(
                    text = subType.take(1).ifBlank { category.title.take(1) },
                    color = accentColor,
                    background = tone.background
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = task.title,
                        modifier = Modifier.weight(1f),
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    JiSoftTag(
                        text = statusLabel,
                        contentColor = tone.color,
                        containerColor = tone.background
                    )
                }

                if (compact) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (subType.isNotBlank()) {
                            JiSoftTag(
                                text = subType,
                                contentColor = SecondaryText,
                                containerColor = TabInactiveBackground
                            )
                        }
                        if (task.dueTime.isNotBlank()) {
                            Text(
                                text = task.dueTime,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (subType.isNotBlank()) {
                            JiSoftTag(
                                text = subType,
                                contentColor = SecondaryText,
                                containerColor = TabInactiveBackground
                            )
                        }
                        if (supportText.isNotBlank()) {
                            Text(
                                text = supportText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (task.dueTime.isNotBlank()) {
                        Text(
                            text = task.dueTime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onToggleStatus,
                        shape = JiButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandBlue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(
                            horizontal = if (compact) 11.dp else 14.dp,
                            vertical = if (compact) 4.dp else 6.dp
                        )
                    ) {
                        Text(
                            text = "完成",
                            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(
                        onClick = onDelete,
                        shape = JiButtonShape,
                        colors = JiDeleteTextButtonColors(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (compact) "删" else "删除",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskGlyph(
    text: String,
    color: Color,
    background: Color = color.copy(alpha = 0.14f)
) {
    val shape = RoundedCornerShape(12.dp)
    val iconBrush = remember(background) { JiIconBrush(background) }
    Box(
        modifier = Modifier
            .size(36.dp)
            .shadow(2.dp, shape, clip = false)
            .background(iconBrush, shape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.72f), shape)
                .align(Alignment.TopCenter)
        )
        Text(
            text = text.take(1),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun TaskFloatingPanel(
    category: TaskDashboardCategory?,
    group: TaskGroupSummary,
    anchor: FloatingPanelAnchor?,
    selectedTask: TaskEntity?,
    onDismiss: () -> Unit,
    onShowTaskDetail: (TaskEntity) -> Unit,
    onBackToList: () -> Unit,
    onEditTask: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var visible by remember(group.label) { mutableStateOf(false) }
        var closing by remember(group.label) { mutableStateOf(false) }
        val panelAnimationMillis = 240
        LaunchedEffect(group.label) {
            visible = true
        }
        LaunchedEffect(closing) {
            if (closing) {
                delay(panelAnimationMillis.toLong())
                onDismiss()
            }
        }
        val expanded = visible && !closing
        val panelAlpha by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(durationMillis = panelAnimationMillis, easing = FastOutSlowInEasing),
            label = "floating-panel-alpha"
        )
        val panelOffsetY by animateDpAsState(
            targetValue = if (expanded) 0.dp else 18.dp,
            animationSpec = tween(durationMillis = panelAnimationMillis, easing = FastOutSlowInEasing),
            label = "floating-panel-offset"
        )
        val panelTasks = remember(group.label, group.tasks) { group.tasks.toList() }
        val requestDismiss = { closing = true }
        val density = LocalDensity.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f * panelAlpha))
        ) {
            val targetWidth = maxWidth * 0.90f
            val targetHeight = maxHeight * 0.50f
            val targetY = maxHeight * 0.18f
            val targetWidthPx = with(density) { targetWidth.toPx() }
            val targetHeightPx = with(density) { targetHeight.toPx() }
            val targetYPx = with(density) { targetY.toPx() }
            val targetCenterX = with(density) { maxWidth.toPx() } / 2f
            val targetCenterY = targetYPx + targetHeightPx / 2f
            val startScale = anchor?.let { (it.width / targetWidthPx).coerceIn(0.48f, 0.94f) } ?: 0.94f
            val startTranslationX = anchor?.let { it.centerX - targetCenterX } ?: 0f
            val startTranslationY = anchor?.let { it.centerY - targetCenterY } ?: 18f
            val panelScale by animateFloatAsState(
                targetValue = if (expanded) 1f else startScale,
                animationSpec = tween(durationMillis = panelAnimationMillis, easing = FastOutSlowInEasing),
                label = "floating-panel-scale"
            )
            val panelTranslationX by animateFloatAsState(
                targetValue = if (expanded) 0f else startTranslationX,
                animationSpec = tween(durationMillis = panelAnimationMillis, easing = FastOutSlowInEasing),
                label = "floating-panel-x"
            )
            val panelTranslationY by animateFloatAsState(
                targetValue = if (expanded) 0f else startTranslationY,
                animationSpec = tween(durationMillis = panelAnimationMillis, easing = FastOutSlowInEasing),
                label = "floating-panel-y"
            )
            val pivotX = anchor?.let {
                ((it.centerX - (targetCenterX - targetWidthPx / 2f)) / targetWidthPx).coerceIn(0.12f, 0.88f)
            } ?: 0.5f
            val pivotY = anchor?.let {
                ((it.centerY - (targetCenterY - targetHeightPx / 2f)) / targetHeightPx).coerceIn(0.12f, 0.88f)
            } ?: 0.5f

            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = targetY)
                    .offset(y = panelOffsetY)
                    .width(targetWidth)
                    .height(targetHeight)
                    .graphicsLayer {
                        alpha = panelAlpha
                        scaleX = panelScale
                        scaleY = panelScale
                        translationX = panelTranslationX
                        translationY = panelTranslationY
                        transformOrigin = TransformOrigin(pivotX, pivotY)
                    },
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50.dp))
                            .align(Alignment.CenterHorizontally)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${panelTasks.size} 个任务",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        JiSoftTag(
                            text = category?.title.orEmpty(),
                            contentColor = category?.let { categoryAccentColor(it) } ?: MaterialTheme.colorScheme.primary,
                            containerColor = category?.let { categoryTone(it).background } ?: NormalTaskBackground
                        )
                        TextButton(
                            onClick = requestDismiss,
                            shape = JiButtonShape,
                            colors = JiSecondaryTextButtonColors()
                        ) {
                            Text(text = "关闭")
                        }
                    }

                    val detailTask = selectedTask
                    if (detailTask != null) {
                        SheetTaskDetailPanel(
                            task = detailTask,
                            onBack = onBackToList,
                            onEdit = { onEditTask(detailTask) },
                            onToggleStatus = { onToggleTaskStatus(detailTask) },
                            onDelete = { onDeleteTask(detailTask) }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = panelTasks,
                                key = { task -> task.id },
                                contentType = { "floating_task" }
                            ) { task ->
                                TaskSheetRow(
                                    modifier = Modifier,
                                    task = task,
                                    onShowTaskDetail = onShowTaskDetail,
                                    onToggleTaskStatus = onToggleTaskStatus,
                                    onDeleteTask = onDeleteTask
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDashboardCard(
    summary: TaskCategorySummary,
    onClick: () -> Unit
) {
    val tone = categoryTone(summary.category)
    val accentColor = tone.color
    val importantTask = summary.importantTask
    val summaryBadgeTone = importantTask?.let { taskStatusTone(it) } ?: JiTone(SecondaryText, TabInactiveBackground)
    val remainingCount = (summary.unfinishedTasks.size - 1).coerceAtLeast(0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = JiAnimationDuration,
                    easing = FastOutSlowInEasing
                )
            )
            .clickable(onClick = onClick),
        shape = JiDashboardCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TaskGlyph(
                    text = summary.category.title.take(1),
                    color = accentColor,
                    background = tone.background
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.category.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${summary.unfinishedTasks.size} 个待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                JiSoftTag(
                    text = summary.urgentText,
                    contentColor = summaryBadgeTone.color,
                    containerColor = summaryBadgeTone.background
                )
            }

            if (importantTask != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (importantTask != null) {
                Text(
                    text = importantTask.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (importantTask.dueTime.isNotBlank()) {
                    Text(
                        text = importantTask.dueTime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val supportText = taskSupportText(importantTask)
                if (supportText.isNotBlank()) {
                    Text(
                        text = supportText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (remainingCount > 0) {
                    JiSoftTag(
                        text = "还有 $remainingCount 个任务 >",
                        contentColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            } else {
                Text(
                    text = "这个分类暂时清爽",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryTaskSheet(
    summary: TaskCategorySummary,
    expandedGroupLabel: String?,
    selectedTask: TaskEntity?,
    onToggleGroup: (String) -> Unit,
    onShowTaskDetail: (TaskEntity) -> Unit,
    onBackToGroups: () -> Unit,
    onEditTask: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit
) {
    val groups = remember(summary) {
        buildTaskGroups(summary.category, summary.tasks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = JiAnimationDuration,
                    easing = FastOutSlowInEasing
                )
            )
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = summary.category.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            JiSoftTag(text = "${summary.unfinishedTasks.size} 个待办")
        }
        JiSoftTag(
            text = summary.urgentText,
            contentColor = categoryAccentColor(summary.category),
            containerColor = categoryAccentColor(summary.category).copy(alpha = 0.10f)
        )

        AnimatedVisibility(
            visible = selectedTask != null,
            enter = fadeIn(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            ) + expandVertically(
                animationSpec = tween(durationMillis = JiAnimationDuration, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing)
            ) + shrinkVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
        ) {
            if (selectedTask != null) {
                SheetTaskDetailPanel(
                    task = selectedTask,
                    onBack = onBackToGroups,
                    onEdit = { onEditTask(selectedTask) },
                    onToggleStatus = { onToggleTaskStatus(selectedTask) },
                    onDelete = { onDeleteTask(selectedTask) }
                )
            }
        }

        AnimatedVisibility(
            visible = selectedTask == null,
            enter = fadeIn(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
            )
        ) {
            if (groups.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = JiCompactCardShape,
                    colors = JiCardColors(),
                    border = JiCardBorder(),
                    elevation = JiCardElevation()
                ) {
                    Text(
                        text = "这里还没有未完成任务",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 620.dp)
                        .animateContentSize(
                            animationSpec = tween(
                                durationMillis = JiAnimationDuration,
                                easing = FastOutSlowInEasing
                            )
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = groups,
                        key = { group -> group.label },
                        contentType = { "task_group" }
                    ) { group ->
                        TaskGroupCard(
                            modifier = Modifier.animateItem(),
                            group = group,
                            expanded = expandedGroupLabel == group.label,
                            onToggle = { onToggleGroup(group.label) },
                            onShowTaskDetail = onShowTaskDetail,
                            onToggleTaskStatus = onToggleTaskStatus,
                            onDeleteTask = onDeleteTask
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskGroupCard(
    modifier: Modifier = Modifier,
    group: TaskGroupSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShowTaskDetail: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = JiAnimationDuration,
                    easing = FastOutSlowInEasing
                )
            ),
        shape = JiCompactCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val firstTask = group.tasks.firstOrNull()
                    if (firstTask != null) {
                        Text(
                            text = firstTask.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                JiSoftTag(
                    text = if (expanded) "收起" else "${group.tasks.size} 个 >",
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = JiAnimationDuration,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = JiAnimationDuration,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = 180,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                group.tasks.forEachIndexed { index, task ->
                    TaskSheetRow(
                        task = task,
                        onShowTaskDetail = onShowTaskDetail,
                        onToggleTaskStatus = onToggleTaskStatus,
                        onDeleteTask = onDeleteTask
                    )
                    if (index != group.tasks.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun TaskSheetRow(
    modifier: Modifier = Modifier,
    task: TaskEntity,
    onShowTaskDetail: (TaskEntity) -> Unit,
    onToggleTaskStatus: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit
) {
    val tone = taskStatusTone(task)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onShowTaskDetail(task) },
        shape = RoundedCornerShape(22.dp),
        colors = JiCardColors(),
        border = BorderStroke(1.dp, tone.color.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(42.dp)
                        .background(tone.color, RoundedCornerShape(50.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (task.dueTime.isNotBlank()) {
                        Text(
                            text = task.dueTime,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                JiSoftTag(
                    text = taskPrimaryStatusLabel(task),
                    contentColor = tone.color,
                    containerColor = tone.background
                )
            }

            val supportText = taskSupportText(task)
            if (supportText.isNotBlank()) {
                Text(
                    text = supportText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onShowTaskDetail(task) },
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = "详情")
                }
                TextButton(
                    onClick = { onToggleTaskStatus(task) },
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = "完成")
                }
                TextButton(
                    onClick = { onDeleteTask(task) },
                    shape = JiButtonShape,
                    colors = JiDeleteTextButtonColors()
                ) {
                    Text(text = "删除")
                }
            }
        }
    }
}

@Composable
private fun SheetTaskDetailPanel(
    task: TaskEntity,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = JiAnimationDuration,
                    easing = FastOutSlowInEasing
                )
            ),
        shape = JiCompactCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "任务详情",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                JiSoftTag(
                    text = task.status,
                    contentColor = taskStatusColor(task.status),
                    containerColor = if (normalizeDraftStatus(task.status) == "已完成") CompletedBackground else TabInactiveBackground
                )
            }

            Text(
                text = task.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (task.course.isNotBlank()) {
                TaskInfoRow(label = "课程", value = task.course)
            }
            if (task.dueTime.isNotBlank()) {
                TaskInfoRow(label = "时间", value = task.dueTime)
            }
            if (task.note.isNotBlank()) {
                TaskInfoRow(label = "备注", value = task.note)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = "返回")
                }
                TextButton(
                    onClick = onEdit,
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = "编辑")
                }
                TextButton(
                    onClick = onToggleStatus,
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = if (task.status == "未完成") "完成" else "设为未完成")
                }
                TextButton(
                    onClick = onDelete,
                    shape = JiButtonShape,
                    colors = JiDeleteTextButtonColors()
                ) {
                    Text(text = "删除")
                }
            }
        }
    }
}

@Composable
private fun EmptyTaskHint() {
    Text(
        text = "还没有任务",
        modifier = Modifier.padding(top = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onOpen: () -> Unit,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = JiCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = task.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (task.course.isNotBlank()) {
                TaskInfoRow(label = "课程", value = task.course)
            }
            if (task.dueTime.isNotBlank()) {
                TaskInfoRow(label = "截止时间", value = task.dueTime)
            }
            TaskInfoRow(
                label = "状态",
                value = task.status,
                valueColor = taskStatusColor(task.status)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onToggleStatus,
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(text = if (task.status == "未完成") "标记完成" else "设为未完成")
                }
                TextButton(
                    onClick = onDelete,
                    shape = JiButtonShape,
                    colors = JiDeleteTextButtonColors()
                ) {
                    Text(text = "删除")
                }
            }
        }
    }
}

@Composable
private fun TaskInfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(72.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = if (valueColor == Color.Unspecified) {
                MaterialTheme.colorScheme.onSurface
            } else {
                valueColor
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OptionalTaskInfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    if (label.isNotBlank() && value.isNotBlank()) {
        TaskInfoRow(label = label, value = value, valueColor = valueColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportImageScreen(
    imageUri: Uri,
    taskParser: TaskParser,
    imageTaskParser: ImageTaskParser,
    onSaveTasks: (List<ParsedTaskDraft>) -> Unit,
    onSaveTask: (ParsedTaskDraft) -> Unit,
    onManualAdd: () -> Unit,
    onReturnHome: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var previewBitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }
    var ocrStatus by remember(imageUri) { mutableStateOf(OcrStatus.Recognizing) }
    var importStage by remember(imageUri) { mutableStateOf(ImportTaskStage.Received) }
    var elapsedSeconds by remember(imageUri) { mutableStateOf(0) }
    var retryNonce by remember(imageUri) { mutableStateOf(0) }
    var ocrText by remember(imageUri) { mutableStateOf("") }
    var parsedTaskDrafts by remember(imageUri) { mutableStateOf(emptyList<ParsedTaskDraft>()) }
    var proHint by remember(imageUri) { mutableStateOf<ProHint?>(null) }
    var usageState by remember(imageUri) { mutableStateOf<AuthState?>(null) }
    var saveNotice by remember(imageUri) { mutableStateOf("") }
    var showCancelConfirm by remember(imageUri) { mutableStateOf(false) }
    val drafts = parsedTaskDrafts
    val isProcessing = ocrStatus == OcrStatus.Recognizing && drafts.isEmpty()

    LaunchedEffect(imageUri) {
        previewBitmap = try {
            loadPreviewBitmap(appContext, imageUri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(imageUri, retryNonce) {
        ocrStatus = OcrStatus.Recognizing
        importStage = ImportTaskStage.Received
        elapsedSeconds = 0
        ocrText = ""
        parsedTaskDrafts = emptyList()
        proHint = null
        usageState = null
        saveNotice = ""
        showCancelConfirm = false

        try {
            val result = parseSharedImageTask(
                context = appContext,
                imageUri = imageUri,
                imageTaskParser = imageTaskParser,
                taskParser = taskParser,
                onStageChange = { stage -> importStage = stage }
            )
            importStage = ImportTaskStage.Completed
            ocrStatus = OcrStatus.Success
            if (result.drafts.isEmpty()) {
                ocrText = result.serverMessage.ifBlank {
                    if (result.recognizedText.isBlank()) {
                        "未识别到文字，请尝试更清晰的截图"
                    } else {
                        "未发现需要新建的任务。这张截图里的内容可能已完成、已逾期，或没有明确待办。"
                    }
                }
                parsedTaskDrafts = emptyList()
            } else {
                ocrText = "AI 图片解析成功"
                parsedTaskDrafts = result.drafts
            }
            proHint = result.proHint
            usageState = result.usage
            saveNotice = result.serverMessage
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ocrStatus = OcrStatus.Failed
            ocrText = "识别失败：${error.localizedMessage ?: "请尝试更清晰的截图"}"
            parsedTaskDrafts = emptyList()
            proHint = null
            usageState = null
            saveNotice = ""
        }
    }

    LaunchedEffect(imageUri, retryNonce, ocrStatus) {
        elapsedSeconds = 0
        while (ocrStatus == OcrStatus.Recognizing) {
            delay(1_000)
            elapsedSeconds += 1
        }
    }

    if (showCancelConfirm) {
        CancelImportDialog(
            onDismiss = { showCancelConfirm = false },
            onConfirm = onReturnHome
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (drafts.isNotEmpty()) "识别完成" else "截图导入",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (isProcessing) {
                                showCancelConfirm = true
                            } else {
                                onReturnHome()
                            }
                        },
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "×")
                    }
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (drafts.isEmpty()) {
                Text(
                    text = "已接收到截图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = JiCardShape,
                colors = JiCardColors(),
                border = JiCardBorder(),
                elevation = JiCardElevation()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (drafts.isNotEmpty()) 148.dp else 210.dp)
                        .padding(8.dp),
                    factory = { context ->
                        ImageView(context).apply {
                            adjustViewBounds = true
                            contentDescription = "截图预览"
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            previewBitmap?.let { bitmap ->
                                setImageBitmap(bitmap)
                                tag = bitmap
                            }
                        }
                    },
                    update = { imageView ->
                        if (imageView.tag !== previewBitmap) {
                            if (previewBitmap == null) {
                                imageView.setImageDrawable(null)
                            } else {
                                imageView.setImageBitmap(previewBitmap)
                            }
                            imageView.tag = previewBitmap
                        }
                    }
                    )
                    if (drafts.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(14.dp),
                            shape = JiTagShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = "1/${drafts.size.coerceAtLeast(1)}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            PrivacyHintText(text = "图片会上传到配置的后端识别；服务商留存情况请查看隐私说明。")

            if (drafts.isNotEmpty()) {
                TaskConfirmationList(
                    drafts = drafts,
                    usage = usageState,
                    proHint = proHint,
                    saveNotice = saveNotice,
                    onSaveTask = { index, draft ->
                        if (!canSaveDraftWithCurrentPlan(draft, proHint)) {
                            saveNotice = proRestrictionMessage(proHint)
                            return@TaskConfirmationList
                        }
                        onSaveTask(draft)
                        val remainingDrafts = parsedTaskDrafts.filterIndexed { draftIndex, _ ->
                            draftIndex != index
                        }
                        parsedTaskDrafts = remainingDrafts
                        if (remainingDrafts.isEmpty()) {
                            onReturnHome()
                        }
                    },
                    onSaveTasks = {
                        val savableDrafts = savableDraftsWithCurrentPlan(drafts, proHint)
                        if (savableDrafts.isEmpty()) {
                            saveNotice = proRestrictionMessage(proHint)
                        } else {
                            onSaveTasks(savableDrafts)
                        }
                    },
                    onDiscard = onReturnHome
                )
            } else {
                ImportProgressCard(
                    status = ocrStatus,
                    stage = importStage,
                    elapsedSeconds = elapsedSeconds,
                    message = ocrText,
                    onRetry = { retryNonce += 1 },
                    onManualAdd = onManualAdd,
                    onCancel = { showCancelConfirm = true },
                    onReturnHome = onReturnHome
                )
            }
        }
    }
}

@Composable
private fun ImportProgressCard(
    status: OcrStatus,
    stage: ImportTaskStage,
    elapsedSeconds: Int,
    message: String,
    onRetry: () -> Unit,
    onManualAdd: () -> Unit,
    onCancel: () -> Unit,
    onReturnHome: () -> Unit
) {
    val displayStage = effectiveImportStage(stage, elapsedSeconds)
    val isProcessing = status == OcrStatus.Recognizing
    val isFailed = status == OcrStatus.Failed
    val targetProgress = when (status) {
        OcrStatus.Recognizing -> processingProgress(displayStage, elapsedSeconds)
        OcrStatus.Success -> 1f
        OcrStatus.Failed -> 1f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = JiAnimationDuration, easing = FastOutSlowInEasing),
        label = "import-progress"
    )
    val statusTitle = importProgressTitle(
        status = status,
        stage = displayStage,
        elapsedSeconds = elapsedSeconds,
        message = message
    )
    val statusHint = importProgressHint(
        status = status,
        stage = displayStage,
        elapsedSeconds = elapsedSeconds,
        message = message
    )
    val progressColor = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JiCompactCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isFailed) HighImportanceBackground else NormalTaskBackground,
                    contentColor = progressColor
                ) {
                    Text(
                        text = if (isFailed) "!" else if (status == OcrStatus.Success) "✓" else "${(animatedProgress * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Crossfade(
                        targetState = statusTitle,
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "import-status-title"
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Crossfade(
                        targetState = statusHint,
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "import-status-hint"
                    ) { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            JiImportProgressBar(
                progress = animatedProgress,
                color = progressColor
            )

            if (isProcessing && elapsedSeconds < 20) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = "取消导入")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        shape = JiButtonShape,
                        colors = JiPrimaryButtonColors()
                    ) {
                        Text(text = "重新识别")
                    }
                    TextButton(
                        onClick = onManualAdd,
                        modifier = Modifier.weight(1f),
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "手动添加")
                    }
                }
                TextButton(
                    onClick = if (isProcessing) onCancel else onReturnHome,
                    modifier = Modifier.fillMaxWidth(),
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = if (isProcessing) "取消导入" else "返回首页")
                }
            }
        }
    }
}

@Composable
private fun JiImportProgressBar(
    progress: Float,
    color: Color
) {
    val safeProgress = progress.coerceIn(0.04f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(NormalTaskBackground, RoundedCornerShape(50.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safeProgress)
                .height(8.dp)
                .background(
                    Brush.linearGradient(
                        listOf(color.copy(alpha = 0.78f), color)
                    ),
                    RoundedCornerShape(50.dp)
                )
        )
    }
}

private fun effectiveImportStage(stage: ImportTaskStage, elapsedSeconds: Int): ImportTaskStage {
    val timedStage = when {
        elapsedSeconds >= 12 -> ImportTaskStage.ArrangingCards
        elapsedSeconds >= 8 -> ImportTaskStage.CheckingDeadline
        elapsedSeconds >= 5 -> ImportTaskStage.Classifying
        elapsedSeconds >= 2 -> ImportTaskStage.RecognizingText
        else -> stage
    }

    return if (timedStage.progress > stage.progress) timedStage else stage
}

private fun processingProgress(stage: ImportTaskStage, elapsedSeconds: Int): Float {
    val slowProgress = when {
        elapsedSeconds >= 20 -> 0.96f
        elapsedSeconds >= 12 -> 0.92f
        elapsedSeconds >= 5 -> 0.84f
        else -> stage.progress + elapsedSeconds * 0.025f
    }

    return maxOf(stage.progress, slowProgress).coerceIn(0.08f, 0.96f)
}

private fun importProgressTitle(
    status: OcrStatus,
    stage: ImportTaskStage,
    elapsedSeconds: Int,
    message: String
): String =
    when {
        status == OcrStatus.Failed -> "整理失败"
        status == OcrStatus.Success -> "未发现需要新建的任务"
        elapsedSeconds >= 20 -> "整理失败"
        elapsedSeconds >= 12 -> "截图内容较多，正在继续整理..."
        elapsedSeconds >= 5 -> "正在仔细核对任务信息..."
        else -> stage.title
    }

private fun importProgressHint(
    status: OcrStatus,
    stage: ImportTaskStage,
    elapsedSeconds: Int,
    message: String
): String =
    when {
        status == OcrStatus.Failed -> message.ifBlank { "请重试或手动添加任务" }
        status == OcrStatus.Success -> "这张截图里的内容可能已完成、已逾期，或没有明确待办，无需建立任务"
        elapsedSeconds >= 20 -> "整理失败，请重试或手动添加任务"
        elapsedSeconds >= 12 -> "任务较多，正在继续整理，请稍等..."
        elapsedSeconds >= 5 -> "正在仔细核对标题、截止时间和完成状态"
        else -> stage.hint
    }

private fun canSaveDraftWithCurrentPlan(draft: ParsedTaskDraft, proHint: ProHint?): Boolean {
    if (proHint?.requiresPro != true) return true
    return isFreeSavableDraft(draft)
}

private fun savableDraftsWithCurrentPlan(
    drafts: List<ParsedTaskDraft>,
    proHint: ProHint?
): List<ParsedTaskDraft> {
    if (proHint?.requiresPro != true) return drafts
    return drafts
        .filter { draft -> isFreeSavableDraft(draft) }
        .take(proHint.freeSaveLimit.coerceAtLeast(0))
}

private fun isFreeSavableDraft(draft: ParsedTaskDraft): Boolean {
    val category = draft.normalizedCategory()
    val subType = draft.displaySubType()
    if (category == "学习") return subType in listOf("作业", "考试", "测验", "提醒")
    if (category == "工作") return subType !in listOf("面试", "缴费", "购物")
    return false
}

private fun proRestrictionMessage(proHint: ProHint?): String =
    proHint?.reason?.takeIf { it.isNotBlank() }
        ?: "当前结果包含 Pro 场景，Free 可预览，升级 Pro 后可保存全部"

@Composable
private fun CancelImportDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = JiCompactCardShape,
            colors = JiCardColors(),
            border = JiCardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "取消本次导入？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "当前整理会停止，已识别内容不会保存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "继续等待")
                    }
                    TextButton(
                        onClick = onConfirm,
                        shape = JiButtonShape,
                        colors = JiDeleteTextButtonColors()
                    ) {
                        Text(text = "取消导入")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskConfirmationList(
    drafts: List<ParsedTaskDraft>,
    usage: AuthState?,
    proHint: ProHint?,
    saveNotice: String,
    onSaveTask: (Int, ParsedTaskDraft) -> Unit,
    onSaveTasks: () -> Unit,
    onDiscard: () -> Unit
) {
    LaunchedEffect(drafts) {
        jiDebugLog { "[CONFIRM_TASKS] count=${drafts.size}" }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "识别完成，已为你整理出 ${drafts.size} 个任务",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        usage?.let { state ->
            JiSoftTag(
                text = "${if (state.isPro) "Pro" else "Free"} · 今日 ${state.usedToday}/${state.dailyQuota} 次",
                contentColor = if (state.isPro) Study else MaterialTheme.colorScheme.primary,
                containerColor = if (state.isPro) StudyBackground else NormalTaskBackground
            )
        }

        if (proHint?.requiresPro == true) {
            ProHintCard(proHint = proHint)
        }

        if (saveNotice.isNotBlank()) {
            Text(
                text = saveNotice,
                style = MaterialTheme.typography.bodyMedium,
                color = Urgent,
                fontWeight = FontWeight.Medium
            )
        }

        PrivacyHintText(text = "识别结果仅保存为任务字段，你可以随时删除。")

        drafts.forEachIndexed { index, draft ->
            TaskConfirmationCard(
                draft = draft,
                onSaveTask = onSaveTasks,
                onDiscard = onDiscard,
                titleText = if (drafts.size == 1) "任务确认" else "任务确认 ${index + 1}/${drafts.size}",
                showActions = false,
                showRawText = false
            )
            if (drafts.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onSaveTask(index, draft) },
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(text = "只保存这条")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDiscard,
                shape = JiButtonShape,
                colors = JiSecondaryTextButtonColors()
            ) {
                Text(text = "不保存")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSaveTasks,
                shape = JiButtonShape,
                colors = JiPrimaryButtonColors()
            ) {
                Text(
                    text = when {
                        proHint?.requiresPro == true -> "保存 Free 可用任务"
                        drafts.size == 1 -> "保存到任务"
                        else -> "保存全部任务"
                    }
                )
            }
        }
    }
}

@Composable
private fun ProHintCard(proHint: ProHint) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JiCompactCardShape,
        colors = CardDefaults.cardColors(containerColor = UrgentBackground),
        border = BorderStroke(1.dp, Urgent.copy(alpha = 0.24f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Pro 能力预览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Urgent
            )
            Text(
                text = proHint.reason.ifBlank {
                    "已识别到高级场景或多个任务，升级 Pro 可一键保存全部"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Free 当前最多保存 ${proHint.freeSaveLimit} 个基础任务",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskConfirmationCard(
    draft: ParsedTaskDraft,
    onSaveTask: () -> Unit,
    onDiscard: () -> Unit,
    titleText: String = "任务确认",
    showActions: Boolean = true,
    showRawText: Boolean = false
) {
    var detailsExpanded by remember(draft) { mutableStateOf(false) }
    val importance = draft.normalizedImportance()
    val urgencyLabel = draftUrgencyLabel(draft)
    val accentColor = if (importance == "高") Important else MaterialTheme.colorScheme.primary
    val detailText = listOf(draft.note, draft.materials, draft.platform)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("\n")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JiCardShape,
        colors = JiCardColors(),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.26f)),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier
                .background(accentColor.copy(alpha = 0.025f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                JiSoftTag(
                    text = draft.displayTypeText().ifBlank { "任务" },
                    contentColor = when (draft.normalizedCategory()) {
                        "学习" -> Study
                        "工作" -> Work
                        "生活" -> Life
                        else -> Other
                    },
                    containerColor = when (draft.normalizedCategory()) {
                        "学习" -> StudyBackground
                        "工作" -> WorkBackground
                        "生活" -> LifeBackground
                        else -> OtherBackground
                    }
                )
                JiSoftTag(
                    text = if (importance == "高") "高重要" else "普通",
                    contentColor = if (importance == "高") Important else NormalTask,
                    containerColor = if (importance == "高") ImportantBackground else NormalTaskBackground
                )
                if (urgencyLabel.isNotBlank()) {
                    JiSoftTag(
                        text = urgencyLabel,
                        contentColor = draftUrgencyColor(draft),
                        containerColor = when (draftDueInfo(draft).bucket) {
                            TaskDueBucket.Today -> HighImportanceBackground
                            TaskDueBucket.Tomorrow, TaskDueBucket.Soon -> UrgentBackground
                            TaskDueBucket.Overdue -> HighImportanceBackground
                            else -> NormalTaskBackground
                        }
                    )
                }
            }

            TaskInfoRow(label = "任务标题", value = draft.title)
            OptionalTaskInfoRow(
                label = draft.displayContextLabel(),
                value = draft.displayContextValue()
            )
            OptionalTaskInfoRow(
                label = if (draft.normalizedCategory() == "学习") "截止时间" else "时间",
                value = draft.time.ifBlank { draft.dueTime }
            )
            OptionalTaskInfoRow(label = "平台", value = draft.platform)
            OptionalTaskInfoRow(label = "地点", value = draft.location)
            OptionalTaskInfoRow(label = "材料", value = draft.materials)

            if (detailText.isNotBlank()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "详情摘要",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = { detailsExpanded = !detailsExpanded },
                            shape = JiButtonShape,
                            colors = JiSecondaryTextButtonColors(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(text = if (detailsExpanded) "收起" else "展开")
                        }
                    }
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (detailsExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showRawText && draft.rawText.isNotBlank()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "原文",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = draft.rawText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showActions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDiscard,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "不保存")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSaveTask,
                        shape = JiButtonShape,
                        colors = JiPrimaryButtonColors()
                    ) {
                        Text(text = "保存到任务")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskMissingScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = "任务详情") },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "返回")
                    }
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Text(
            text = "任务不存在",
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailScreen(
    task: TaskEntity,
    onBack: () -> Unit,
    onSave: (TaskEntity) -> Unit,
    onDelete: (TaskEntity) -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var course by remember(task.id) { mutableStateOf(task.course) }
    var dueTime by remember(task.id) { mutableStateOf(task.dueTime) }
    var note by remember(task.id) { mutableStateOf(task.note) }
    var status by remember(task.id) { mutableStateOf(task.status) }
    var showTitleError by remember(task.id) { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = "任务详情") },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "返回")
                    }
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    if (it.isNotBlank()) showTitleError = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "任务标题") },
                singleLine = true,
                isError = showTitleError,
                colors = JiOutlinedTextFieldColors(),
                supportingText = {
                    if (showTitleError) {
                        Text(text = "任务标题必填")
                    }
                }
            )

            OutlinedTextField(
                value = course,
                onValueChange = { course = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "课程名称") },
                singleLine = true,
                colors = JiOutlinedTextFieldColors()
            )

            OutlinedTextField(
                value = dueTime,
                onValueChange = { dueTime = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "截止时间") },
                singleLine = true,
                colors = JiOutlinedTextFieldColors()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "状态",
                    modifier = Modifier.width(72.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = status,
                    modifier = Modifier.weight(1f),
                    color = taskStatusColor(status),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = {
                        status = if (status == "未完成") "已完成" else "未完成"
                    },
                    shape = JiButtonShape,
                    colors = JiSecondaryTextButtonColors()
                ) {
                    Text(text = if (status == "未完成") "标记完成" else "设为未完成")
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                label = { Text(text = "备注") },
                colors = JiOutlinedTextFieldColors()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onDelete(task) },
                    shape = JiButtonShape,
                    colors = JiDeleteTextButtonColors()
                ) {
                    Text(text = "删除")
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onBack,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                showTitleError = true
                                return@Button
                            }

                            onSave(
                                task.copy(
                                    title = title,
                                    course = course,
                                    dueTime = dueTime,
                                    note = note,
                                    status = status
                                )
                            )
                        },
                        shape = JiButtonShape,
                        colors = JiPrimaryButtonColors()
                    ) {
                        Text(text = "保存修改")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskScreen(
    initialMode: AddTaskMode,
    authState: AuthState,
    taskParser: TaskParser,
    imageTaskParser: ImageTaskParser,
    onBack: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onSaveDrafts: (List<ParsedTaskDraft>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("学习") }
    var contextValue by remember { mutableStateOf("") }
    var dueTime by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showTitleError by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }
    var textDrafts by remember { mutableStateOf(emptyList<ParsedTaskDraft>()) }
    var textProHint by remember { mutableStateOf<ProHint?>(null) }
    var textMessage by remember { mutableStateOf("") }
    var isParsingText by remember { mutableStateOf(false) }
    var selectedImageUris by remember { mutableStateOf(emptyList<Uri>()) }
    var imageDrafts by remember { mutableStateOf(emptyList<ParsedTaskDraft>()) }
    var imageUsage by remember { mutableStateOf<AuthState?>(null) }
    var imageProHint by remember { mutableStateOf<ProHint?>(null) }
    var imageMessage by remember { mutableStateOf("") }
    var isParsingImages by remember { mutableStateOf(false) }
    var imageProgressText by remember { mutableStateOf("") }

    val singleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedImageUris = listOfNotNull(uri)
        imageDrafts = emptyList()
        imageMessage = ""
        imageProHint = null
    }
    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        selectedImageUris = uris.take(9)
        imageDrafts = emptyList()
        imageMessage = ""
        imageProHint = null
    }

    fun launchImagePicker(multiple: Boolean) {
        if (multiple && !authState.isPro) {
            imageMessage = "多图批量识别是 Pro 能力。Free 可以先选择 1 张图片识别。"
            imageProHint = ProHint(
                requiresPro = true,
                reason = "升级 Pro 后可一次导入多张截图，并使用高精度识别。",
                freeSaveLimit = 3
            )
            return
        }
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (multiple) {
            multiImagePicker.launch(request)
        } else {
            singleImagePicker.launch(request)
        }
    }

    fun clearSelectedImages() {
        selectedImageUris = emptyList()
        imageDrafts = emptyList()
        imageMessage = ""
        imageProgressText = ""
        imageProHint = null
    }

    fun startImageRecognition() {
        if (selectedImageUris.isEmpty() || isParsingImages) return
        if (selectedImageUris.size > 1 && !authState.isPro) {
            imageMessage = "Free 用户每次只能识别 1 张图片。升级 Pro 可批量导入。"
            imageProHint = ProHint(true, "升级 Pro 后可一次导入多张截图，高精度整理。", 3)
            return
        }
        scope.launch {
            isParsingImages = true
            imageDrafts = emptyList()
            imageMessage = ""
            imageProHint = null
            val collectedDrafts = mutableListOf<ParsedTaskDraft>()
            selectedImageUris.forEachIndexed { index, uri ->
                imageProgressText = if (selectedImageUris.size > 1) {
                    "正在识别 ${index + 1} / ${selectedImageUris.size}"
                } else {
                    "正在识别..."
                }
                val result = parseSharedImageTask(
                    context = context.applicationContext,
                    imageUri = uri,
                    imageTaskParser = imageTaskParser,
                    taskParser = taskParser
                )
                collectedDrafts += result.drafts
                imageUsage = result.usage ?: imageUsage
                imageProHint = result.proHint ?: imageProHint
            }
            val normalizedDrafts = normalizeParsedDrafts(collectedDrafts)
            imageDrafts = normalizedDrafts
            imageProHint = imageProHint ?: buildClientProHintForDrafts(authState, normalizedDrafts)
            imageMessage = if (normalizedDrafts.isEmpty()) {
                "没有发现需要新建的任务。"
            } else {
                "已整理出 ${normalizedDrafts.size} 个任务"
            }
            imageProgressText = ""
            isParsingImages = false
        }
    }

    fun saveManualTask() {
        if (title.isBlank()) {
            showTitleError = true
            return
        }
        val storageNote = buildString {
            append("分类：").append(category)
            if (location.isNotBlank()) append("\n地点：").append(location.trim())
            if (note.isNotBlank()) append("\n").append(note.trim())
        }
        onSave(title.trim(), contextValue.trim(), dueTime.trim(), storageNote)
    }

    val screenTitle = selectedMode.title
    val screenSubtitle = selectedMode.subtitle

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = screenTitle, fontWeight = FontWeight.Bold)
                        Text(
                            text = screenSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        shape = JiButtonShape,
                        colors = JiSecondaryTextButtonColors()
                    ) {
                        Text(text = "返回")
                    }
                },
                colors = JiTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedMode) {
                AddTaskMode.Manual -> {
                    AddTaskFormCard {
                        OutlinedTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                if (it.isNotBlank()) showTitleError = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "任务标题") },
                            singleLine = true,
                            isError = showTitleError,
                            shape = JiCompactCardShape,
                            colors = JiOutlinedTextFieldColors(),
                            supportingText = {
                                if (showTitleError) {
                                    Text(text = "任务标题必填")
                                }
                            }
                        )

                        CategoryPillSelector(
                            selectedCategory = category,
                            onSelect = { category = it }
                        )

                        OutlinedTextField(
                            value = contextValue,
                            onValueChange = { contextValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    text = when (category) {
                                        "学习" -> "课程 / 科目"
                                        "工作" -> "项目 / 公司 / 岗位"
                                        "生活" -> "来源 / 事项"
                                        else -> "来源 / 分类"
                                    }
                                )
                            },
                            singleLine = true,
                            shape = JiCompactCardShape,
                            colors = JiOutlinedTextFieldColors()
                        )

                        OutlinedTextField(
                            value = dueTime,
                            onValueChange = { dueTime = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "截止时间 / 提醒时间") },
                            singleLine = true,
                            shape = JiCompactCardShape,
                            colors = JiOutlinedTextFieldColors()
                        )

                        OutlinedTextField(
                            value = location,
                            onValueChange = { location = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "地点") },
                            singleLine = true,
                            shape = JiCompactCardShape,
                            colors = JiOutlinedTextFieldColors()
                        )

                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            label = { Text(text = "备注") },
                            shape = JiCompactCardShape,
                            colors = JiOutlinedTextFieldColors()
                        )
                    }

                    AddTaskBottomActions(
                        primaryText = "保存任务",
                        onCancel = onBack,
                        onPrimary = ::saveManualTask
                    )
                }

                AddTaskMode.Paste -> {
                    AddTaskFormCard {
                        OutlinedTextField(
                            value = pastedText,
                            onValueChange = {
                                pastedText = it
                                textMessage = ""
                                textDrafts = emptyList()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp),
                            label = { Text("粘贴通知、作业、面试安排等内容，自动整理成任务") },
                            shape = JiCompactCardShape,
                            colors = JiOutlinedTextFieldColors()
                        )
                        PrivacyHintText(text = "粘贴内容默认本地解析；如配置远程解析服务，文本会发送到该服务。请勿粘贴密码或验证码。")
                        Button(
                            onClick = {
                                if (pastedText.isBlank() || isParsingText) return@Button
                                scope.launch {
                                    isParsingText = true
                                    textMessage = ""
                                    val parsed = withContext(Dispatchers.Default) {
                                        normalizeParsedDrafts(
                                            LocalTaskParser.parseLocalMany(pastedText)
                                                .filter { it.isUnfinishedTaskDraft() }
                                        )
                                    }
                                    textDrafts = parsed
                                    textProHint = buildClientProHintForDrafts(authState, parsed)
                                    textMessage = if (parsed.isEmpty()) {
                                        "没有发现需要新建的任务，可以调整文字后重试。"
                                    } else {
                                        "已整理出 ${parsed.size} 个任务"
                                    }
                                    isParsingText = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            colors = JiPrimaryButtonColors(),
                            enabled = pastedText.isNotBlank() && !isParsingText
                        ) {
                            Text(if (isParsingText) "正在识别..." else "识别文本")
                        }
                        if (textMessage.isNotBlank()) {
                            Text(
                                text = textMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (textDrafts.isNotEmpty()) {
                        TaskConfirmationList(
                            drafts = textDrafts,
                            usage = null,
                            proHint = textProHint,
                            saveNotice = "",
                            onSaveTask = { index, draft ->
                                if (!canSaveDraftWithCurrentPlan(draft, textProHint)) {
                                    textMessage = proRestrictionMessage(textProHint)
                                    return@TaskConfirmationList
                                }
                                onSaveDrafts(listOf(draft))
                                textDrafts = textDrafts.filterIndexed { draftIndex, _ -> draftIndex != index }
                            },
                            onSaveTasks = {
                                val savableDrafts = savableDraftsWithCurrentPlan(textDrafts, textProHint)
                                if (savableDrafts.isEmpty()) {
                                    textMessage = proRestrictionMessage(textProHint)
                                } else {
                                    onSaveDrafts(savableDrafts)
                                }
                            },
                            onDiscard = { textDrafts = emptyList() }
                        )
                    }
                }

                AddTaskMode.Image -> {
                    AddTaskFormCard {
                        Text(
                            text = "选择 1 张截图",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "适合快速整理单张课程、通知或聊天截图。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PrivacyHintText(text = "图片会上传到配置的后端识别；服务商留存情况请查看隐私说明。")
                        Button(
                            onClick = { launchImagePicker(multiple = false) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            colors = JiPrimaryButtonColors()
                        ) {
                            Text("选择图片")
                        }
                        if (selectedImageUris.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                JiSoftTag(
                                    text = "已选择 1 张图片",
                                    contentColor = BrandBlue,
                                    containerColor = NormalTaskBackground
                                )
                                TextButton(
                                    onClick = ::clearSelectedImages,
                                    shape = JiButtonShape,
                                    colors = JiSecondaryTextButtonColors()
                                ) {
                                    Text("重新选择")
                                }
                            }
                        }
                        Button(
                            onClick = ::startImageRecognition,
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            colors = JiPrimaryButtonColors(),
                            enabled = selectedImageUris.isNotEmpty() && !isParsingImages
                        ) {
                            Text(if (isParsingImages) imageProgressText.ifBlank { "正在识别..." } else "开始识别")
                        }
                        Text(
                            text = "Pro 支持多图批量识别",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (imageMessage.isNotBlank()) {
                            Text(
                                text = imageMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (imageDrafts.isNotEmpty()) {
                        TaskConfirmationList(
                            drafts = imageDrafts,
                            usage = imageUsage,
                            proHint = imageProHint,
                            saveNotice = "",
                            onSaveTask = { index, draft ->
                                if (!canSaveDraftWithCurrentPlan(draft, imageProHint)) {
                                    imageMessage = proRestrictionMessage(imageProHint)
                                    return@TaskConfirmationList
                                }
                                onSaveDrafts(listOf(draft))
                                imageDrafts = imageDrafts.filterIndexed { draftIndex, _ -> draftIndex != index }
                            },
                            onSaveTasks = {
                                val savableDrafts = savableDraftsWithCurrentPlan(imageDrafts, imageProHint)
                                if (savableDrafts.isEmpty()) {
                                    imageMessage = proRestrictionMessage(imageProHint)
                                } else {
                                    onSaveDrafts(savableDrafts)
                                }
                            },
                            onDiscard = { imageDrafts = emptyList() }
                        )
                    }
                }

                AddTaskMode.MultiImage -> {
                    AddTaskFormCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pro 批量整理",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            JiSoftTag(
                                text = "Pro",
                                contentColor = ProGold,
                                containerColor = ProGoldBackground
                            )
                        }
                        Text(
                            text = "适合长截图、多门课程、多个通知一起整理。一次建议选择 2–9 张截图。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiCompactCardShape,
                            color = ProGoldBackground.copy(alpha = 0.58f),
                            border = BorderStroke(1.dp, ProGold.copy(alpha = 0.16f))
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("• 支持一次导入多张截图", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                                Text("• 自动合并整理多个任务", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                                Text("• 识别中显示逐张进度", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        PrivacyHintText(text = "图片会上传到配置的后端识别；服务商留存情况请查看隐私说明。")
                        Button(
                            onClick = { launchImagePicker(multiple = true) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            colors = if (authState.isPro) {
                                JiPrimaryButtonColors()
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = TabInactiveBackground,
                                    contentColor = SecondaryText
                                )
                            }
                        ) {
                            Text(if (authState.isPro) "批量导入截图" else "预览 Pro 批量能力")
                        }
                        if (selectedImageUris.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "已选择 ${selectedImageUris.size} / 9 张截图",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                TextButton(
                                    onClick = ::clearSelectedImages,
                                    shape = JiButtonShape,
                                    colors = JiSecondaryTextButtonColors()
                                ) {
                                    Text("清空")
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                selectedImageUris.forEachIndexed { index, _ ->
                                    Surface(
                                        modifier = Modifier.size(54.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        shadowElevation = 1.dp
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = ProGold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = ::startImageRecognition,
                            modifier = Modifier.fillMaxWidth(),
                            shape = JiButtonShape,
                            colors = JiPrimaryButtonColors(),
                            enabled = selectedImageUris.size >= 2 && !isParsingImages && authState.isPro
                        ) {
                            Text(if (isParsingImages) imageProgressText.ifBlank { "正在批量识别..." } else "开始批量识别")
                        }
                        if (imageMessage.isNotBlank()) {
                            Text(
                                text = imageMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (imageDrafts.isNotEmpty()) {
                        TaskConfirmationList(
                            drafts = imageDrafts,
                            usage = imageUsage,
                            proHint = imageProHint,
                            saveNotice = "",
                            onSaveTask = { index, draft ->
                                if (!canSaveDraftWithCurrentPlan(draft, imageProHint)) {
                                    imageMessage = proRestrictionMessage(imageProHint)
                                    return@TaskConfirmationList
                                }
                                onSaveDrafts(listOf(draft))
                                imageDrafts = imageDrafts.filterIndexed { draftIndex, _ -> draftIndex != index }
                            },
                            onSaveTasks = {
                                val savableDrafts = savableDraftsWithCurrentPlan(imageDrafts, imageProHint)
                                if (savableDrafts.isEmpty()) {
                                    imageMessage = proRestrictionMessage(imageProHint)
                                } else {
                                    onSaveDrafts(savableDrafts)
                                }
                            },
                            onDiscard = { imageDrafts = emptyList() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddModeTabs(
    selectedMode: AddTaskMode,
    onSelected: (AddTaskMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AddTaskMode.values().forEach { mode ->
            AddModeTabPill(
                mode = mode,
                selected = selectedMode == mode,
                onClick = { onSelected(mode) }
            )
        }
    }
}

@Composable
private fun AddModeTabPill(
    mode: AddTaskMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) NormalTaskBackground.copy(alpha = 0.92f) else TabInactiveBackground.copy(alpha = 0.86f)
    val contentColor = if (selected) BrandBlue else SecondaryText

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = JiButtonShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (selected) 1.dp else 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) BrandBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = mode.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = mode.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) SecondaryText else contentColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddTaskFormCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JiCardShape,
        colors = JiCardColors(),
        border = JiCardBorder(),
        elevation = JiCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun CategoryPillSelector(
    selectedCategory: String,
    onSelect: (String) -> Unit
) {
    val categories = listOf("学习", "工作", "生活", "其他")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "分类",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                val selected = selectedCategory == category
                Surface(
                    modifier = Modifier.clickable { onSelect(category) },
                    shape = JiTagShape,
                    color = if (selected) NormalTaskBackground else TabInactiveBackground.copy(alpha = 0.82f),
                    contentColor = if (selected) BrandBlue else SecondaryText,
                    border = BorderStroke(
                        1.dp,
                        if (selected) BrandBlue.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.58f)
                    ),
                    shadowElevation = if (selected) 1.dp else 0.dp
                ) {
                    Text(
                        text = category,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTaskBottomActions(
    primaryText: String,
    onCancel: () -> Unit,
    onPrimary: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = JiButtonShape,
            colors = JiSecondaryTextButtonColors()
        ) {
            Text(text = "取消")
        }
        Button(
            onClick = onPrimary,
            modifier = Modifier.weight(1.4f),
            shape = JiButtonShape,
            colors = JiPrimaryButtonColors()
        ) {
            Text(text = primaryText)
        }
    }
}

private fun buildClientProHintForDrafts(
    authState: AuthState,
    drafts: List<ParsedTaskDraft>
): ProHint? {
    if (authState.isPro || drafts.isEmpty()) return null

    val hasAdvancedScene = drafts.any { draft -> !isFreeSavableDraft(draft) }
    val exceedsFreeLimit = drafts.size > 3

    if (!hasAdvancedScene && !exceedsFreeLimit) return null

    return ProHint(
        requiresPro = true,
        reason = when {
            hasAdvancedScene && exceedsFreeLimit -> "已识别到高级场景和多个任务，Free 可保存前 3 个基础任务。"
            hasAdvancedScene -> "已识别到生活、面试、缴费、购物等高级场景，升级 Pro 可保存。"
            else -> "已识别到 ${drafts.size} 个任务，Free 可保存前 3 个。"
        },
        freeSaveLimit = 3
    )
}

@Composable
private fun JiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = JiPrimaryBlue,
            onPrimary = Color.White,
            primaryContainer = JiLightBlue,
            onPrimaryContainer = JiPrimaryBlue,
            secondary = JiPrimaryBlue,
            onSecondary = Color.White,
            secondaryContainer = JiLightBlue,
            onSecondaryContainer = JiPrimaryBlue,
            tertiary = JiCompletedGreen,
            onTertiary = Color.White,
            background = JiBackground,
            onBackground = JiPrimaryText,
            surface = JiCardBackground,
            onSurface = JiPrimaryText,
            surfaceVariant = JiLightBlue,
            onSurfaceVariant = JiSecondaryText,
            outline = JiBorder,
            outlineVariant = JiBorder,
            error = JiDeleteRed,
            onError = Color.White
        ),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(JiPageGradient())
            ) {
                content()
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun JiHomePreview() {
    JiTheme {
        HomeScreen(
            tasks = listOf(
                TaskEntity(
                    id = 1,
                    title = "完成课程作业",
                    course = "软件工程",
                    dueTime = "周五 18:00",
                    note = "",
                    status = "未完成"
                )
            ),
            onAddClick = {},
            onOpenTask = {},
            onToggleTaskStatus = {},
            onDeleteTask = {},
            onClearArchivedTasks = {},
            onAccountClick = {}
        )
    }
}
