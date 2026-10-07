package com.ouyue.ji

object LocalTaskParser : TaskParser {
    private val courseRules = listOf(
        listOf("大英", "大学英语", "英语作业", "英语") to "大学英语",
        listOf("高数", "数学") to "高等数学",
        listOf("飞机构造") to "飞机构造",
        listOf("A320", "飞机系统", "航空", "机务") to "A320飞机系统与附件",
        listOf("机械") to "机械基础",
        listOf("电工", "电子") to "电工电子",
        listOf("实训") to "实训课程",
        listOf("计算机") to "计算机",
        listOf("体育") to "体育",
        listOf("思政", "毛概", "形势与政策") to "思政课程"
    )

    private val platformWords = listOf("智慧职教", "雨课堂", "学习通", "超星", "职教云", "云班课", "微信", "支付宝")
    private val learningWords = listOf(
        "课程名称", "作业名称", "考试名称", "学习通", "智慧职教", "雨课堂", "作业", "考试", "论文", "PPT",
        "小组作业", "小组课题", "研究项目", "实训", "报告", "提交", "截止", "结束时间", "课程通知"
    )
    private val workWords = listOf(
        "面试", "会议", "项目", "公司", "岗位", "老板", "实习", "汇报", "方案", "材料", "简历",
        "身份证复印件", "签字笔", "入职", "招聘", "通知面试"
    )
    private val lifeWords = listOf(
        "买", "购物", "花呗", "还款", "水电费", "缴费", "快递", "取件", "妈妈", "爸爸", "父母",
        "老婆", "女朋友", "提醒", "家里", "账单"
    )
    private val titleSignal = learningWords + workWords + lifeWords + listOf("第", "章", "待办")
    private val noteKeywords = listOf(
        "线上提交", "上传", "拍照上传", "拍照提交", "Word", "PDF", "格式", "纸质版", "照片", "附件",
        "提前", "带", "携带", "准备", "小组完成", "小组", "材料要求", "分组要求", "注意事项"
    )
    private val materialWords = listOf("身份证复印件", "身份证", "简历", "签字笔", "电脑", "纸质版", "照片", "附件")
    private val locationWords = listOf("教室", "会议室", "公司", "线上", "腾讯会议", "地点", "校区", "楼", "室")
    private val finishedWords = listOf("已结束", "已批阅", "得分", "已完成", "已提交")
    private val unfinishedWords = listOf("进行中", "未完成", "未做", "前往作业", "待提交", "待完成")
    private val explicitFieldLabels = listOf(
        "课程名称", "作业名称", "考试名称", "开始时间", "结束时间", "截止时间", "考试形式", "地点", "材料"
    )

    private val fullDateTime = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\s+\d{1,2}:\d{2}(?::\d{2})?""")
    private val homeworkTimeRange = Regex(
        """(?:作业时间|考试时间|任务时间|时间)[:：]?\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\s+\d{1,2}:\d{2}(?::\d{2})?\s*[-—~至到]\s*(\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\s+\d{1,2}:\d{2}(?::\d{2})?)"""
    )
    private val deadlinePatterns = listOf(
        Regex("""(?:截止|结束时间|截止时间|提醒时间)[:：]?\s*(\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\s+\d{1,2}:\d{2}(?::\d{2})?)"""),
        Regex("""(?:今天|明天|后天|今晚|本周|下周)?(?:周[一二三四五六日天]|星期[一二三四五六日天])?(?:晚上|上午|下午)?\d{1,2}点(?:半)?(?:前|之前|截止)?"""),
        Regex("""(?:本周|下周)[一二三四五六日天](?:前|之前|截止)?"""),
        Regex("""(?:本周|下周)(?:周[一二三四五六日天]|星期[一二三四五六日天])(?:前|之前|截止)?"""),
        Regex("""(?:明天晚上|明晚|明天上午|明天中午|明天下午|今天晚上|今晚|今天上午|今天中午|今天下午|后天晚上|后天上午|后天下午)(?:前|之前|截止)?"""),
        Regex("""(?:今天|明天|后天|今晚)(?:前|之前|截止)?"""),
        Regex("""(?:周[一二三四五六日天]|星期[一二三四五六日天])(?:前|之前|截止)?"""),
        Regex("""\d{1,2}月\d{1,2}[日号]?(?:前|之前|截止)?"""),
        Regex("""\d{1,2}[./]\d{1,2}(?:前|之前|截止)?""")
    )

    override suspend fun parse(rawText: String): ParsedTaskDraft? =
        parseLocal(rawText)

    override suspend fun parseMany(rawText: String): List<ParsedTaskDraft> =
        parseLocalMany(rawText)

    fun parseLocal(text: String): ParsedTaskDraft? =
        parseLocalMany(text).firstOrNull()

    fun looksLikeTaskListPage(rawText: String): Boolean {
        val cleanText = cleanOcrText(rawText)
        return looksLikeCleanTaskListPage(cleanText)
    }

    fun looksLikeCleanTaskListPage(cleanText: String): Boolean {
        if (cleanText.isBlank()) return false
        return looksLikeTaskListPageLines(cleanText.lines().filter { it.isNotBlank() })
    }

    fun parseLocalMany(text: String): List<ParsedTaskDraft> {
        val cleanText = cleanOcrText(text)
        if (cleanText.isBlank()) return emptyList()

        val lines = cleanText.lines().filter { it.isNotBlank() }
        val globalCourse = inferCourse(lines, lines.joinToString(" "))
        val globalPlatform = extractPlatform(lines)
        val isTaskListPage = looksLikeTaskListPageLines(lines)
        if (!isTaskListPage && looksLikeSingleEventNotice(lines)) {
            return listOfNotNull(
                parseSegment(
                    lines = lines,
                    globalCourse = globalCourse,
                    globalPlatform = globalPlatform
                )
            )
        }
        val segments = splitTaskSegments(lines)

        return segments
            .mapNotNull { segmentLines ->
                parseSegment(
                    lines = segmentLines,
                    globalCourse = globalCourse,
                    globalPlatform = globalPlatform
                )
            }
            .distinctBy { draft -> "${draft.title}|${draft.dueTime}|${draft.contextValue}" }
    }

    private fun looksLikeSingleEventNotice(lines: List<String>): Boolean {
        if (looksLikeTaskListPageLines(lines)) return false

        val text = lines.joinToString(" ")
        val singleEventSignals = listOf("面试", "会议", "考试", "报名", "活动通知", "宣讲", "签到", "入职")
        if (!containsAny(text, singleEventSignals)) return false

        val likelyStarts = lines.count { line -> isLikelyTaskStart(line) }
        val timeCount = fullDateTime.findAll(text).map { it.value }.distinct().count()
        val locationHits = lines.count { line -> locationWords.any { keyword -> line.contains(keyword, ignoreCase = true) } }

        return likelyStarts <= 4 || timeCount <= 3 || locationHits > 0
    }

    private fun looksLikeTaskListPageLines(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false

        val text = lines.joinToString(" ")
        val independentTitles = lines
            .filter { line -> isIndependentListTaskTitle(line) }
            .map { line -> normalizeIndependentTaskTitle(line) }
            .filter { it.isNotBlank() }
            .distinct()

        val homeworkTimeCount = lines.count { line -> line.contains("作业时间") }
        val goHomeworkCount = countOccurrences(text, "前往作业")
        val activeStatusCount = lines.count { line ->
            listOf("未做", "进行中", "未完成", "待提交", "待完成").any { status -> line.contains(status) }
        }
        val completedStatusCount = lines.count { line ->
            listOf("已结束", "已批阅", "已完成", "已提交", "得分").any { status -> line.contains(status) }
        }
        val repeatedTagCount = listOf("题库作业", "手动出题", "作业要求").sumOf { tag ->
            countOccurrences(text, tag)
        }

        return independentTitles.size >= 2 ||
            hasMultipleNumberedHomeworkTitles(text) ||
            goHomeworkCount >= 2 ||
            homeworkTimeCount >= 2 ||
            (independentTitles.isNotEmpty() && activeStatusCount + completedStatusCount + repeatedTagCount >= 2)
    }

    private fun isIndependentListTaskTitle(line: String): Boolean {
        if (line.contains("作业时间") || line.contains("作业要求")) return false
        if (line.contains("题库作业") || line.contains("手动出题")) return false
        if (line.contains("前往作业") || line.contains("查看")) return false
        if (finishedWords.any { line == it } || unfinishedWords.any { line == it }) return false
        if (looksLikeCourseLine(line)) return false

        return Regex("""第[一二三四五六七八九十百千万\d]+[章节]?.{0,24}作业\s*\d+""").containsMatchIn(line) ||
            Regex("""作业\s*\d+""").containsMatchIn(line)
    }

    private fun normalizeIndependentTaskTitle(line: String): String {
        return line
            .replace(Regex("""\s+"""), "")
            .replace("进行中", "")
            .replace("未做", "")
            .replace("已结束", "")
            .replace("已批阅", "")
            .trim()
    }

    private fun hasMultipleNumberedHomeworkTitles(text: String): Boolean {
        val titles = Regex("""第[一二三四五六七八九十百千万\d]+[章节]?.{0,24}作业\s*\d+""")
            .findAll(text)
            .map { match -> normalizeIndependentTaskTitle(match.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .toList()

        return titles.size >= 2
    }

    private fun countOccurrences(text: String, word: String): Int =
        Regex(Regex.escape(word)).findAll(text).count()

    fun cleanOcrText(rawText: String): String {
        val normalizedLines = rawText
            .replace("\r", "\n")
            .lineSequence()
            .map { normalizeLine(it) }
            .filter { it.isNotBlank() }
            .filterNot { isIrrelevantLine(it) }
            .toList()

        val seen = linkedSetOf<String>()
        return mergeBrokenLines(normalizedLines)
            .filter { line -> seen.add(line.lowercase()) }
            .joinToString("\n")
            .trim()
    }

    private fun parseSegment(
        lines: List<String>,
        globalCourse: String,
        globalPlatform: String
    ): ParsedTaskDraft? {
        val segmentLines = lines.filter { it.isNotBlank() }
        val text = segmentLines.joinToString(" ")
        if (!hasTaskSignal(text)) return null

        val category = inferCategory(text)
        val subType = inferSubType(category, text)
        val platform = extractPlatform(segmentLines).ifBlank { globalPlatform }
        val course = if (category == "学习") {
            inferCourse(segmentLines, text).ifBlank { globalCourse }
        } else {
            ""
        }
        val deadline = extractDeadline(segmentLines, text)
        val location = extractLocation(segmentLines)
        val materials = extractMaterials(segmentLines)
        val note = extractNote(segmentLines)
        val status = inferStatus(text)
        val importance = inferImportance(text, subType, deadline)
        val (contextLabel, contextValue) = inferContext(
            category = category,
            subType = subType,
            lines = segmentLines,
            text = text,
            course = course
        )
        val title = extractTitle(
            lines = segmentLines,
            text = text,
            category = category,
            subType = subType,
            contextValue = contextValue,
            dueTime = deadline,
            note = note
        )

        return ParsedTaskDraft(
            title = title.ifBlank { "截图任务" },
            category = category,
            subType = subType,
            importance = importance,
            contextLabel = contextLabel,
            contextValue = contextValue,
            status = status,
            time = deadline,
            dueTime = deadline,
            location = location,
            platform = platform,
            materials = materials,
            course = if (contextLabel == "课程") contextValue else course,
            note = note,
            rawText = segmentLines.joinToString("\n")
        )
    }

    private fun normalizeLine(line: String): String {
        return line
            .replace("：", ":")
            .replace("　", " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '，', '。', ',', '.', '；', ';', '|', '｜')
    }

    private fun mergeBrokenLines(lines: List<String>): List<String> {
        val merged = mutableListOf<String>()
        var pending = ""

        lines.forEach { line ->
            if (pending.isBlank()) {
                pending = line
            } else if (shouldMergeBrokenLine(pending, line)) {
                pending += line
            } else {
                merged += pending
                pending = line
            }
        }

        if (pending.isNotBlank()) merged += pending
        return merged
    }

    private fun shouldMergeBrokenLine(previous: String, next: String): Boolean {
        if (previous.length > 14 || next.length > 18) return false
        if (isHardBoundary(previous) || isHardBoundary(next)) return false
        if (isLikelyTaskStart(next)) return false
        if (previous.any { it.isDigit() } && next.any { it.isDigit() }) return false
        return previous.length + next.length <= 28
    }

    private fun isHardBoundary(line: String): Boolean {
        return line.contains(":") ||
            line.contains("时间") ||
            line.contains("截止") ||
            line.contains("要求") ||
            platformWords.any { line.contains(it, ignoreCase = true) } ||
            finishedWords.any { line.contains(it) } ||
            unfinishedWords.any { line.contains(it) }
    }

    private fun splitTaskSegments(lines: List<String>): List<List<String>> {
        val segments = mutableListOf<List<String>>()
        val globalLines = mutableListOf<String>()
        var current = mutableListOf<String>()

        lines.forEach { line ->
            if (isLikelyTaskStart(line)) {
                if (current.isNotEmpty()) segments += current.toList()
                current = (globalLines.takeLast(8) + line).toMutableList()
            } else if (current.isNotEmpty()) {
                current += line
            } else {
                globalLines += line
            }
        }

        if (current.isNotEmpty()) segments += current.toList()
        if (segments.isEmpty()) segments += lines
        return segments
    }

    private fun isLikelyTaskStart(line: String): Boolean {
        if (line.contains("作业时间") || line.contains("作业要求") || line.contains("结束时间")) return false
        if (line.length > 60) return false
        if (looksLikeCourseLine(line)) return false
        if (platformWords.any { line.equals(it, ignoreCase = true) }) return false
        if (finishedWords.any { line == it } || unfinishedWords.any { line == it }) return false

        val hasStudyStart = Regex("""第[一二三四五六七八九十百千万\d]+[章节]?.*(作业|考试|报告|实训|实验)""")
            .containsMatchIn(line) || listOf("作业名称", "考试名称", "课程通知", "论文", "PPT", "小组").any { line.contains(it) }
        val hasWorkStart = listOf("面试", "会议", "项目", "实习", "入职", "招聘", "通知面试").any { line.contains(it) }
        val hasLifeStart = listOf("买", "购物", "花呗", "还款", "水电费", "缴费", "快递", "取件", "提醒").any { line.contains(it) }

        return hasStudyStart || hasWorkStart || hasLifeStart
    }

    private fun hasTaskSignal(text: String): Boolean =
        titleSignal.any { text.contains(it, ignoreCase = true) } || fullDateTime.containsMatchIn(text)

    private fun inferCategory(text: String): String {
        val scores = listOf(
            "学习" to learningWords.count { text.contains(it, ignoreCase = true) },
            "工作" to workWords.count { text.contains(it, ignoreCase = true) },
            "生活" to lifeWords.count { text.contains(it, ignoreCase = true) }
        )
        val best = scores.maxByOrNull { it.second }
        return if (best == null || best.second == 0) "其他" else best.first
    }

    private fun inferSubType(category: String, text: String): String {
        return when (category) {
            "学习" -> when {
                containsAny(text, listOf("作业", "题库作业", "手动出题", "前往作业", "未做", "作业时间", "作业要求")) -> "作业"
                containsAny(text, listOf("考试", "测验")) -> "考试"
                containsAny(text, listOf("论文")) -> "论文"
                containsAny(text, listOf("PPT", "ppt", "汇报")) -> "PPT"
                containsAny(text, listOf("小组作业", "小组课题", "小组")) -> "小组任务"
                containsAny(text, listOf("实训")) -> "实训"
                containsAny(text, listOf("报告")) -> "报告"
                containsAny(text, listOf("课程通知", "通知")) -> "课程通知"
                else -> "未分类"
            }
            "工作" -> when {
                containsAny(text, listOf("面试", "通知面试", "招聘", "岗位")) -> "面试"
                containsAny(text, listOf("会议", "开会")) -> "会议"
                containsAny(text, listOf("项目", "方案")) -> "项目"
                containsAny(text, listOf("身份证复印件", "简历", "签字笔", "材料")) -> "材料准备"
                containsAny(text, listOf("实习", "入职")) -> "实习"
                else -> "工作安排"
            }
            "生活" -> when {
                containsAny(text, listOf("买", "购物", "清单")) -> "购物"
                containsAny(text, listOf("缴费", "水电费", "话费")) -> "缴费"
                containsAny(text, listOf("花呗", "还款")) -> "还款"
                containsAny(text, listOf("快递", "取件")) -> "快递"
                containsAny(text, listOf("妈妈", "爸爸", "父母", "老婆", "女朋友")) -> "家人交代"
                containsAny(text, listOf("提醒")) -> "提醒"
                else -> "提醒"
            }
            else -> "未分类"
        }
    }

    private fun inferContext(
        category: String,
        subType: String,
        lines: List<String>,
        text: String,
        course: String
    ): Pair<String, String> {
        return when (category) {
            "学习" -> "课程" to course
            "工作" -> {
                if (subType == "项目") {
                    "项目" to extractProject(text)
                } else {
                    "公司/岗位" to extractCompanyOrPosition(text)
                }
            }
            "生活" -> when (subType) {
                "购物" -> "购买清单" to extractShoppingList(lines, text)
                "缴费" -> "缴费项目" to extractPaymentItem(text)
                "还款" -> "缴费项目" to extractPaymentItem(text).ifBlank { "花呗还款" }
                "快递" -> "事项" to extractExpressMatter(text)
                "家人交代" -> "来源" to extractFamilySource(text)
                else -> "事项" to extractLifeMatter(text)
            }
            else -> "" to ""
        }
    }

    private fun inferCourse(lines: List<String>, text: String): String {
        val explicitCourse = extractFieldValue(lines, "课程名称", allowContinuation = true)
        if (explicitCourse.isNotBlank()) return explicitCourse

        val courseFromTop = lines
            .take(12)
            .firstNotNullOfOrNull { line ->
                if (looksLikeCourseLine(line)) cleanCourseName(line) else null
            }
        if (!courseFromTop.isNullOrBlank()) return courseFromTop

        return courseRules.firstOrNull { (keywords, _) ->
            keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
        }?.second.orEmpty()
    }

    private fun looksLikeCourseLine(line: String): Boolean {
        if (line.contains("班级学习")) return false
        if (line.contains("作业时间") || line.contains("作业要求")) return false
        if (line.contains("（24机电") || line.contains("(24机电")) return true
        if (Regex("""[（(].*班[）)]""").containsMatchIn(line) && line.length >= 8) return true
        return false
    }

    private fun cleanCourseName(line: String): String {
        return line
            .replace(Regex("""[（(].*?班[）)]"""), "")
            .replace("课程介绍", "")
            .trim(' ', '，', '。', ',', '.', '；', ';', ':', '|')
            .take(40)
    }

    private fun extractTitle(
        lines: List<String>,
        text: String,
        category: String,
        subType: String,
        contextValue: String,
        dueTime: String,
        note: String
    ): String {
        val explicitHomeworkTitle = extractFieldValue(lines, "作业名称", allowContinuation = true)
        if (explicitHomeworkTitle.isNotBlank()) return explicitHomeworkTitle.take(40)

        val explicitExamTitle = extractFieldValue(lines, "考试名称", allowContinuation = true)
        if (explicitExamTitle.isNotBlank()) return explicitExamTitle.take(40)

        if (category == "生活" && subType == "快递") {
            return buildExpressTitle(text)
        }

        val titleLine = lines.firstOrNull { line -> isLikelyTitleLine(line, category) }
        if (!titleLine.isNullOrBlank()) return cleanupTitleLine(titleLine)

        var title = text
        listOf(contextValue, dueTime, note).filter { it.isNotBlank() }.forEach { value ->
            title = title.replace(value, "", ignoreCase = true)
        }
        platformWords.forEach { title = title.replace(it, "", ignoreCase = true) }

        title = title
            .replace(homeworkTimeRange, "")
            .replace(fullDateTime, "")
            .replace(Regex("""(?:作业|考试|任务)?时间[:：]?"""), "")
            .replace(Regex("""(截止|截至|之前|前)"""), "")
            .replace(Regex("""\s+"""), "")
            .trim(' ', '，', '。', ',', '.', '；', ';', ':', '|')

        if (title.isBlank() && subType.isNotBlank()) return subType
        return cleanupTitleLine(title).take(40)
    }

    private fun isLikelyTitleLine(line: String, category: String): Boolean {
        if (line.contains("作业时间") || line.contains("作业要求")) return false
        if (line.contains("题库作业") || line.contains("手动出题")) return false
        if (line.contains("前往作业") || line.contains("进行中")) return false
        if (looksLikeCourseLine(line)) return false

        return when (category) {
            "学习" -> listOf("作业", "考试", "报告", "实训", "实验", "论文", "PPT").any { line.contains(it, ignoreCase = true) } ||
                Regex("""第[一二三四五六七八九十百千万\d]+章""").containsMatchIn(line)
            "工作" -> listOf("面试", "会议", "项目", "实习", "入职", "招聘").any { line.contains(it) }
            "生活" -> listOf("买", "购物", "缴费", "还款", "快递", "取件", "提醒").any { line.contains(it) }
            else -> titleSignal.any { line.contains(it, ignoreCase = true) }
        }
    }

    private fun cleanupTitleLine(line: String): String {
        var title = line
            .replace(Regex("""作业[|｜]考试"""), "")
            .replace("题库作业", "")
            .replace("手动出题", "")
            .replace("进行中", "")
            .replace("未做", "")
            .replace("已批阅", "")
            .replace("已结束", "")
            .trim(' ', '，', '。', ',', '.', '；', ';', ':', '|', '｜')

        platformWords.forEach { title = title.replace(it, "", ignoreCase = true) }

        return title
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(40)
    }

    private fun extractDeadline(lines: List<String>, text: String): String {
        val explicitEndTime = extractFieldValue(lines, "结束时间", allowContinuation = false)
        if (explicitEndTime.isNotBlank()) return explicitEndTime.take(30)

        val explicitDeadline = extractFieldValue(lines, "截止时间", allowContinuation = false)
        if (explicitDeadline.isNotBlank()) return explicitDeadline.take(30)

        buildTimeCandidates(lines, text).forEach { candidate ->
            homeworkTimeRange.find(candidate)?.groups?.get(1)?.value?.let { return it.trim() }

            val dateTimes = fullDateTime.findAll(candidate).map { it.value.trim() }.toList()
            if ((candidate.contains("作业时间") || candidate.contains("考试时间")) && dateTimes.size >= 2) {
                return dateTimes.last()
            }
        }

        val safeText = removeClassInfo(text)
        val match = deadlinePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(safeText)?.let { result ->
                if (result.groups.size > 1) {
                    result.groups[1]?.value ?: result.value
                } else {
                    result.value
                }
            }
        }.orEmpty()

        return cleanupDeadline(match)
    }

    private fun buildTimeCandidates(lines: List<String>, text: String): List<String> {
        val candidates = mutableListOf(text)
        lines.forEachIndexed { index, line ->
            if (line.contains("作业时间") || line.contains("考试时间") || line.contains("结束时间")) {
                candidates += line
                if (index + 1 < lines.size) candidates += "$line ${lines[index + 1]}"
                if (index + 2 < lines.size) candidates += "$line ${lines[index + 1]} ${lines[index + 2]}"
            }
        }
        return candidates
    }

    private fun removeClassInfo(text: String): String {
        return text
            .replace(Regex("""[（(][^）)]*班[）)]"""), " ")
            .replace(Regex("""\d{2}机电\d{1,2}[-—]\d{1,2}班"""), " ")
            .replace(Regex("""\d{1,2}[-—]\d{1,2}班"""), " ")
            .replace(Regex("""\b\d{1,3}\b"""), " ")
            .replace(Regex("""\d+(\.\d+)?\s*(KB/S|K/S|MB/S|5GA|SGA)""", RegexOption.IGNORE_CASE), " ")
    }

    private fun cleanupDeadline(value: String): String {
        val deadline = value.trim(' ', '，', '。', ',', '.', '；', ';')
        if (deadline.matches(Regex("""\d{1,2}[-—]\d{1,2}"""))) return ""
        return deadline.take(30)
    }

    private fun extractPlatform(lines: List<String>): String {
        val text = lines.joinToString(" ")
        return platformWords.firstOrNull { word -> text.contains(word, ignoreCase = true) }.orEmpty()
    }

    private fun extractLocation(lines: List<String>): String {
        val explicitLocation = extractFieldValue(lines, "地点", allowContinuation = false)
        if (explicitLocation.isNotBlank()) return explicitLocation.take(40)

        return lines.firstOrNull { line ->
            locationWords.any { keyword -> line.contains(keyword, ignoreCase = true) }
        }?.take(40).orEmpty()
    }

    private fun extractMaterials(lines: List<String>): String {
        val explicitMaterials = extractFieldValue(lines, "材料", allowContinuation = true)
        if (explicitMaterials.isNotBlank()) return explicitMaterials.take(60)

        val materialCandidateLines = lines.filterNot { line ->
            looksLikeCourseLine(line) || line.contains("与附件") || line.contains("课程介绍")
        }
        val materials = materialWords.filter { word ->
            materialCandidateLines.any { line ->
                line.contains(word, ignoreCase = true) && (word != "附件" || isRealAttachmentRequirement(line))
            }
        }
        val preparedLine = materialCandidateLines.firstOrNull { line ->
            listOf("带", "携带", "准备", "材料").any { keyword -> line.contains(keyword) } &&
                materialWords.any { word -> line.contains(word, ignoreCase = true) }
        }.orEmpty()

        return (materials + preparedLine)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("、")
            .take(80)
    }

    private fun isRealAttachmentRequirement(line: String): Boolean {
        return listOf("上传", "提交", "材料", "准备", "携带", "带", "照片", "PDF", "Word", "格式")
            .any { keyword -> line.contains(keyword, ignoreCase = true) }
    }

    private fun extractNote(lines: List<String>): String {
        return lines
            .filterNot { line -> looksLikeCourseLine(line) || line.contains("与附件") || line.contains("课程介绍") }
            .filter { line -> noteKeywords.any { keyword -> line.contains(keyword, ignoreCase = true) } }
            .map { line ->
                line
                    .replace("作业要求:暂无要求", "")
                    .replace("作业要求：暂无要求", "")
                    .replace("暂无要求", "")
                    .replace("考试过程中如果出现页面卡死、题目空白情况，请尝试切换网络或退出重新进入考试", "")
                    .trim(' ', '，', '。', ',', '.', '；', ';', ':')
            }
            .filter { it.isNotBlank() }
            .filterNot { line ->
                line == "提交" ||
                    line == "上传" ||
                    fullDateTime.containsMatchIn(line) ||
                    finishedWords.any { line == it } ||
                    unfinishedWords.any { line == it }
            }
            .distinct()
            .joinToString("；")
            .take(100)
    }

    private fun inferStatus(text: String): String {
        if (unfinishedWords.any { text.contains(it) }) return "未完成"
        if (finishedWords.any { text.contains(it) }) return "已完成"
        return "未完成"
    }

    private fun inferImportance(text: String, subType: String, deadline: String): String {
        val importantText = listOf(text, subType, deadline).joinToString(" ")
        return normalizeDraftImportance("", importantText)
    }

    private fun extractFieldValue(
        lines: List<String>,
        label: String,
        allowContinuation: Boolean
    ): String {
        val index = lines.indexOfFirst { line -> line.contains("$label:") || line.contains("$label：") }
        if (index < 0) return ""

        val line = lines[index]
        val firstValue = line
            .substringAfter("$label:", line.substringAfter("$label：", ""))
            .trim(' ', '，', '。', ',', '.', '；', ';', ':')

        if (!allowContinuation) return firstValue

        val values = mutableListOf<String>()
        if (firstValue.isNotBlank()) values += firstValue

        var nextIndex = index + 1
        while (nextIndex < lines.size && values.size < 3) {
            val nextLine = lines[nextIndex]
            if (isExplicitFieldLine(nextLine)) break
            if (isLikelyTaskStart(nextLine)) break
            if (nextLine.length <= 1) break
            values += nextLine.trim(' ', '，', '。', ',', '.', '；', ';', ':')
            nextIndex++
        }

        return values.joinToString("").take(60)
    }

    private fun isExplicitFieldLine(line: String): Boolean {
        return explicitFieldLabels.any { label -> line.contains("$label:") || line.contains("$label：") }
    }

    private fun extractCompanyOrPosition(text: String): String {
        val company = Regex("""[\u4e00-\u9fa5A-Za-z0-9]{2,24}(?:公司|集团|科技|有限|工作室)""")
            .find(text)
            ?.value
            .orEmpty()
        val position = Regex("""(?:岗位|职位)[:：]?\s*([\u4e00-\u9fa5A-Za-z0-9]+)""")
            .find(text)
            ?.groups
            ?.get(1)
            ?.value
            .orEmpty()

        return listOf(company, position)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { cleanupTitleLine(text).take(32) }
    }

    private fun extractProject(text: String): String {
        return Regex("""[\u4e00-\u9fa5A-Za-z0-9]{2,24}项目""")
            .find(text)
            ?.value
            ?: cleanupTitleLine(text).take(32)
    }

    private fun extractShoppingList(lines: List<String>, text: String): String {
        val afterBuy = Regex("""买(.{1,30})""")
            .find(text)
            ?.groups
            ?.get(1)
            ?.value
            ?.trim(' ', '，', '。', ',', '.', '；', ';')
            .orEmpty()

        return afterBuy.ifBlank {
            lines.filterNot { line -> line.contains("购物") || line.contains("提醒") }
                .take(4)
                .joinToString("、")
                .take(40)
        }
    }

    private fun extractPaymentItem(text: String): String {
        return when {
            text.contains("花呗") -> "花呗"
            text.contains("水电费") -> "水电费"
            text.contains("话费") -> "话费"
            text.contains("账单") -> "账单"
            else -> "缴费"
        }
    }

    private fun extractFamilySource(text: String): String {
        return listOf("妈妈", "爸爸", "父母", "老婆", "女朋友", "家里")
            .firstOrNull { text.contains(it) }
            .orEmpty()
    }

    private fun extractLifeMatter(text: String): String {
        return extractPaymentItem(text).takeIf { it != "缴费" }
            ?: cleanupTitleLine(text).take(32)
    }

    private fun buildExpressTitle(text: String): String {
        return when {
            text.contains("快递站") -> "去快递站取快递"
            text.contains("取件") || text.contains("快递") -> "取快递"
            else -> "生活提醒"
        }
    }

    private fun extractExpressMatter(text: String): String {
        val code = Regex("""取件(?:号码|码)?\s*[:：]?\s*([A-Za-z0-9-]{3,16})""")
            .find(text)
            ?.groups
            ?.get(1)
            ?.value
            .orEmpty()
        if (code.isNotBlank()) return "取件号码 $code"

        return cleanupTitleLine(text)
            .replace(Regex("""(?:今天|明天|后天|今晚|明晚|上午|中午|下午|晚上)"""), "")
            .trim(' ', '，', '。', ',', '.', '；', ';', ':', '|')
            .take(32)
    }

    private fun containsAny(text: String, words: List<String>): Boolean =
        words.any { word -> text.contains(word, ignoreCase = true) }

    private fun isIrrelevantLine(line: String): Boolean {
        val normalized = line.replace(" ", "")
        if (normalized.length <= 1) return true
        if (normalized.matches(Regex("""\d{1,2}:\d{2}"""))) return true
        if (normalized.matches(Regex("""\d{1,3}"""))) return true
        if (normalized.matches(Regex("""\d{1,3}%"""))) return true
        if (normalized.contains("KB/S", ignoreCase = true)) return true
        if (normalized.contains("K/S", ignoreCase = true)) return true
        if (normalized.contains("MB/S", ignoreCase = true)) return true
        if (normalized.contains("5GA", ignoreCase = true)) return true
        if (normalized.contains("SGA", ignoreCase = true)) return true
        if (normalized.contains("中国移动") || normalized.contains("中国联通") || normalized.contains("中国电信")) return true

        val irrelevantExactWords = listOf(
            "班级学习", "课程介绍", "课件", "课堂", "成绩", "全部", "请输入关键字查询", "返回", "首页",
            "更多", "分享", "保存", "取消", "相册", "照片", "截图", "编辑", "完成",
            "搜索", "前往", "确认", "扫一扫"
        )
        if (irrelevantExactWords.any { normalized.equals(it, ignoreCase = true) }) return true

        val hasTaskSignal = titleSignal.any { normalized.contains(it, ignoreCase = true) } ||
            normalized.contains("作业时间") ||
            normalized.contains("作业要求") ||
            looksLikeCourseLine(line)

        return normalized.length <= 3 && !hasTaskSignal
    }
}
