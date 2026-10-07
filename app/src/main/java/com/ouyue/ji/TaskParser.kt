package com.ouyue.ji

interface TaskParser {
    suspend fun parse(rawText: String): ParsedTaskDraft?

    suspend fun parseMany(rawText: String): List<ParsedTaskDraft> =
        listOfNotNull(parse(rawText))
}

data class ParsedTaskDraft(
    val title: String,
    val category: String = "",
    val subType: String = "",
    val importance: String = "",
    val contextLabel: String = "",
    val contextValue: String = "",
    val status: String = "未完成",
    val time: String = "",
    val location: String = "",
    val platform: String = "",
    val materials: String = "",
    val course: String = "",
    val dueTime: String = time,
    val note: String = "",
    val rawText: String = ""
)

private val UnfinishedDraftStatusWords = listOf(
    "未完成",
    "未做",
    "进行中",
    "待完成",
    "未提交",
    "待提交",
    "前往作业",
    "逾期",
    "过期",
    "overdue",
    "expired",
    "in_progress",
    "pending",
    "todo"
)

private val FinishedDraftStatusWords = listOf(
    "已完成",
    "已结束",
    "已截止",
    "已批阅",
    "已提交",
    "已评价",
    "已关闭",
    "已做",
    "完成",
    "结束",
    "批阅",
    "得分",
    "查看",
    "查看答案",
    "历史记录",
    "done",
    "finished",
    "completed"
)

private val HighImportanceWords = listOf(
    "高",
    "重要",
    "紧急",
    "必须",
    "必做",
    "强制",
    "考试",
    "测验",
    "作业提交",
    "提交作业",
    "待提交",
    "老师布置",
    "报名截止",
    "缴费截止",
    "还款截止",
    "面试",
    "通知面试",
    "入职",
    "实习",
    "材料准备",
    "材料提交",
    "简历",
    "身份证复印件",
    "签字笔",
    "汇报",
    "论文",
    "PPT",
    "小组任务",
    "实训",
    "报告",
    "花呗",
    "水电费",
    "high",
    "urgent",
    "critical"
)

private val NormalImportanceWords = listOf(
    "普通",
    "一般",
    "可选",
    "不急",
    "无强制截止",
    "可做可不做",
    "normal",
    "low",
    "optional"
)

fun normalizeDraftStatus(status: String): String {
    val compactStatus = status.trim().replace(" ", "").lowercase()
    if (compactStatus.isBlank()) return "未完成"

    if (UnfinishedDraftStatusWords.any { compactStatus.contains(it.lowercase()) }) {
        return "未完成"
    }

    if (FinishedDraftStatusWords.any { compactStatus.contains(it.lowercase()) }) {
        return "已完成"
    }

    return "未完成"
}

fun ParsedTaskDraft.isUnfinishedTaskDraft(): Boolean =
    normalizeDraftStatus(status) == "未完成"

fun normalizeDraftImportance(importance: String, text: String = ""): String {
    val explicitImportance = importance.trim().lowercase()
    if (explicitImportance.isNotBlank()) {
        if (HighImportanceWords.any { explicitImportance.contains(it.lowercase()) }) return "高"
        if (NormalImportanceWords.any { explicitImportance.contains(it.lowercase()) }) return "普通"
    }

    val searchableText = text.trim().lowercase()
    if (searchableText.isBlank()) return "普通"
    if (NormalImportanceWords.any { searchableText.contains(it.lowercase()) }) return "普通"

    return if (HighImportanceWords.any { searchableText.contains(it.lowercase()) }) {
        "高"
    } else {
        "普通"
    }
}

fun ParsedTaskDraft.normalizedImportance(): String =
    normalizeDraftImportance(
        importance = importance,
        text = listOf(title, category, subType, contextValue, time, dueTime, note, rawText)
            .joinToString(" ")
    )

fun normalizeDraftCategory(category: String, subType: String = ""): String {
    val trimmedCategory = category.trim()
    when (trimmedCategory.lowercase()) {
        "study" -> return "学习"
        "work" -> return "工作"
        "life", "daily" -> return "生活"
        "other" -> return "其他"
    }
    if (trimmedCategory == "日常") return "生活"
    if (trimmedCategory in listOf("学习", "工作", "生活", "其他")) return trimmedCategory

    val typeText = "${trimmedCategory} ${subType.trim()}"
    return when {
        typeText.isBlank() -> "其他"
        listOf(
            "作业", "题库作业", "手动出题", "前往作业", "课程", "课件", "章节测试", "测试",
            "考试", "成绩", "报告", "论文", "PPT", "学习通", "智慧职教", "班级学习"
        )
            .any { typeText.contains(it, ignoreCase = true) } -> "学习"
        listOf("面试", "实习", "简历", "会议", "项目", "材料提交", "入职", "培训", "申请", "HR", "公司", "岗位")
            .any { typeText.contains(it, ignoreCase = true) } -> "工作"
        listOf("快递", "缴费", "水电费", "取件", "购物", "出行", "健康", "运动", "生活提醒", "预约", "还款", "花呗")
            .any { typeText.contains(it, ignoreCase = true) } -> "生活"
        else -> "其他"
    }
}

fun ParsedTaskDraft.normalizedCategory(): String =
    normalizeDraftCategory(category, subType)

fun normalizeDraftSubType(category: String, subType: String = "", text: String = ""): String {
    val searchableText = listOf(category, subType, text).joinToString(" ")
    val homeworkSignals = listOf("作业", "题库作业", "手动出题", "前往作业", "未做", "作业时间", "课程任务")
    if (homeworkSignals.any { searchableText.contains(it, ignoreCase = true) }) return "作业"

    val normalizedType = when (subType.trim().lowercase()) {
        "homework" -> "作业"
        "exam", "test" -> "考试"
        "interview" -> "面试"
        "payment" -> "缴费"
        "meeting" -> "会议"
        "reminder", "project", "shopping", "other" -> "提醒"
        else -> subType.trim()
    }
    if (normalizedType.isNotBlank()) return normalizedType

    return when {
        listOf("考试", "测验", "期末", "期中", "补考", "考核", "测试")
            .any { searchableText.contains(it, ignoreCase = true) } -> "考试"
        listOf("面试", "笔试", "群面", "终面", "HR", "机务面试", "岗位面试")
            .any { searchableText.contains(it, ignoreCase = true) } -> "面试"
        listOf("缴费", "支付", "付款", "费用", "报名费", "水电费", "欠费")
            .any { searchableText.contains(it, ignoreCase = true) } -> "缴费"
        listOf("会议", "开会", "汇报", "讨论", "例会", "项目会")
            .any { searchableText.contains(it, ignoreCase = true) } -> "会议"
        else -> "提醒"
    }
}

fun ParsedTaskDraft.displaySubType(): String {
    val rawType = subType.ifBlank {
        if (category !in listOf("学习", "工作", "生活", "其他")) category else ""
    }
    return normalizeDraftSubType(
        category = category,
        subType = rawType,
        text = listOf(title, contextValue, time, dueTime, note, rawText).joinToString(" ")
    )
}

fun ParsedTaskDraft.displayTypeText(): String {
    val normalizedCategory = normalizedCategory()
    val type = displaySubType()
    return listOf(normalizedCategory, type)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
}

fun ParsedTaskDraft.displayContextLabel(): String =
    contextLabel.ifBlank {
        val category = normalizedCategory()
        val type = displaySubType()
        val hasContext = displayContextValue().isNotBlank()
        when {
            category == "学习" && course.isNotBlank() -> "课程"
            category == "工作" && hasContext && type == "面试" -> "公司/岗位"
            category == "工作" && hasContext -> "项目"
            category == "生活" && hasContext && type == "缴费" -> "缴费项目"
            category == "生活" && hasContext -> "事项"
            else -> ""
        }
    }

fun ParsedTaskDraft.displayContextValue(): String =
    contextValue.ifBlank { course }

fun ParsedTaskDraft.noteForStorage(): String {
    val category = normalizedCategory()
    val type = displaySubType()
    val contextLabel = displayContextLabel()
    val contextValue = displayContextValue()
    val lines = mutableListOf(
        "分类：$category",
        "类型：$type"
    )

    if (contextLabel.isNotBlank() && contextValue.isNotBlank()) {
        lines += "$contextLabel：$contextValue"
    }
    if (location.isNotBlank()) lines += "地点：$location"
    if (platform.isNotBlank()) lines += "平台：$platform"
    if (materials.isNotBlank()) lines += "材料：$materials"
    if (normalizedImportance() == "高") lines += "重要性：高"
    if (note.isNotBlank()) lines += note

    return lines.distinct().joinToString("\n")
}

fun ParsedTaskDraft.courseForStorage(): String =
    if (normalizedCategory() == "学习" && displayContextLabel() == "课程") displayContextValue() else ""

class FallbackTaskParser(
    private val primary: TaskParser,
    private val fallback: TaskParser
) : TaskParser {
    override suspend fun parse(rawText: String): ParsedTaskDraft? {
        val primaryDraft = runCatching {
            primary.parse(rawText)
        }.getOrNull()

        return primaryDraft ?: fallback.parse(rawText)
    }

    override suspend fun parseMany(rawText: String): List<ParsedTaskDraft> {
        val primaryDrafts = runCatching {
            primary.parseMany(rawText)
        }.getOrNull().orEmpty()

        return primaryDrafts.ifEmpty { fallback.parseMany(rawText) }
    }
}
