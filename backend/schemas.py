import json
from typing import Any

from pydantic import BaseModel, Field


class ParsedTask(BaseModel):
    title: str = ""
    category: str = ""
    subType: str = ""
    importance: str = ""
    contextLabel: str = ""
    contextValue: str = ""
    time: str = ""
    location: str = ""
    platform: str = ""
    materials: str = ""
    note: str = ""
    status: str = "未完成"
    rawText: str = ""


class ParsedTaskResponse(BaseModel):
    tasks: list[ParsedTask] = Field(default_factory=list)


FINISHED_WORDS = ("已完成", "已批阅", "得分", "查看答案", "历史记录", "已结束", "已提交", "已评价", "已关闭")
UNFINISHED_WORDS = ("未完成", "未做", "进行中", "待完成", "未提交", "待提交", "前往作业")
STUDY_WORDS = ("作业", "题库作业", "手动出题", "前往作业", "课程", "课件", "章节测试", "测试", "考试", "成绩", "报告", "论文", "学习通", "智慧职教", "班级学习")
WORK_WORDS = ("面试", "实习", "简历", "会议", "项目", "材料提交", "入职", "培训", "申请", "HR", "公司", "岗位")
LIFE_WORDS = ("快递", "缴费", "水电费", "取件", "购物", "出行", "健康", "运动", "生活提醒", "预约", "还款", "花呗")
HOMEWORK_WORDS = ("作业", "题库作业", "手动出题", "前往作业", "未做", "作业时间", "课程任务")
EXAM_WORDS = ("考试", "测验", "期末", "期中", "补考", "考核", "测试")
INTERVIEW_WORDS = ("面试", "笔试", "群面", "终面", "HR", "机务面试", "岗位面试")
PAYMENT_WORDS = ("缴费", "支付", "付款", "费用", "报名费", "水电费", "欠费", "还款")
MEETING_WORDS = ("会议", "开会", "汇报", "讨论", "例会", "项目会")
HIGH_WORDS = ("高", "重要", "紧急", "必须", "考试", "面试", "报名截止", "缴费截止", "材料提交", "入职", "实习", "high", "urgent", "critical")


def mock_task_response() -> dict:
    return ParsedTaskResponse(
        tasks=[
            ParsedTask(
                title="测试任务标题",
                category="学习",
                subType="作业",
                importance="普通",
                contextLabel="课程",
                contextValue="测试课程",
                time="2026-06-30 23:59",
                platform="测试平台",
                note="这是后端模拟返回",
            )
        ]
    ).model_dump()


def empty_task_response() -> dict:
    return ParsedTaskResponse(tasks=[]).model_dump()


def normalize_task_response(data: Any) -> dict:
    if isinstance(data, str):
        data = json.loads(extract_json_object(data))

    if isinstance(data, list):
        raw_tasks = data
    elif isinstance(data, dict):
        raw_tasks = data.get("tasks")
        if raw_tasks is None and any(key in data for key in ParsedTask.model_fields.keys()):
            raw_tasks = [data]
    else:
        return empty_task_response()

    if not isinstance(raw_tasks, list):
        return empty_task_response()

    tasks: list[ParsedTask] = []
    for raw in raw_tasks:
        if not isinstance(raw, dict):
            continue

        title = clean_text(raw.get("title", raw.get("t", "")))
        if not title:
            continue

        text = " ".join(clean_text(value) for value in raw.values())
        if looks_finished(text):
            continue

        sub_type = normalize_type(
            clean_text(raw.get("subType", raw.get("subcategory", raw.get("type", "")))),
            f"{title} {text}",
        )
        category = normalize_category(clean_text(raw.get("category", raw.get("cat", ""))), f"{title} {sub_type} {text}")
        context_value = clean_text(raw.get("contextValue", raw.get("course", "")))
        context_label = clean_text(raw.get("contextLabel", "")) or default_context_label(category, sub_type, context_value)

        tasks.append(
            ParsedTask(
                title=title,
                category=category,
                subType=sub_type,
                importance=normalize_importance(clean_text(raw.get("importance", raw.get("imp", ""))), f"{title} {text}"),
                contextLabel=context_label,
                contextValue=context_value,
                time=clean_text(raw.get("time", raw.get("deadline", raw.get("ddl", "")))),
                location=clean_text(raw.get("location", raw.get("loc", ""))),
                platform=clean_text(raw.get("platform", raw.get("src", ""))),
                materials=clean_text(raw.get("materials", "")),
                note=clean_note(clean_text(raw.get("note", raw.get("notes", "")))),
                status=normalize_status(clean_text(raw.get("status", ""))),
                rawText=clean_source_text(clean_text(raw.get("rawText", raw.get("sourceText", "")))),
            )
        )

    if not tasks:
        return empty_task_response()
    if looks_like_independent_homework_list(tasks):
        return ParsedTaskResponse(tasks=tasks[:20]).model_dump()
    if len(tasks) > 1 and looks_like_single_notice(tasks):
        return ParsedTaskResponse(tasks=[merge_notice_tasks(tasks)]).model_dump()
    return ParsedTaskResponse(tasks=tasks[:20]).model_dump()


def clean_text(value: Any) -> str:
    return str(value or "").strip()


def contains_any(text: str, words: tuple[str, ...]) -> bool:
    lower_text = text.lower()
    return any(word.lower() in lower_text for word in words)


def normalize_category(category: str, text: str) -> str:
    lower = category.strip().lower()
    if lower == "study" or category == "学习":
        return "学习"
    if lower == "work" or category == "工作":
        return "工作"
    if lower in {"life", "daily"} or category in {"生活", "日常"}:
        return "生活"
    if lower == "other" or category in {"其他", "其它"}:
        return "其他"
    if contains_any(text, STUDY_WORDS):
        return "学习"
    if contains_any(text, WORK_WORDS):
        return "工作"
    if contains_any(text, LIFE_WORDS):
        return "生活"
    return "其他"


def normalize_type(sub_type: str, text: str) -> str:
    lower = sub_type.strip().lower()
    if contains_any(text, HOMEWORK_WORDS) or lower == "homework":
        return "作业"
    if lower in {"exam", "test"} or contains_any(text, EXAM_WORDS):
        return "考试"
    if lower == "interview" or contains_any(text, INTERVIEW_WORDS):
        return "面试"
    if lower == "payment" or contains_any(text, PAYMENT_WORDS):
        return "缴费"
    if lower == "meeting" or contains_any(text, MEETING_WORDS):
        return "会议"
    if lower == "shopping":
        return "购物"
    return "提醒"


def normalize_importance(importance: str, text: str) -> str:
    lower = importance.strip().lower()
    if lower == "high" or contains_any(f"{importance} {text}", HIGH_WORDS):
        return "高"
    if lower in {"low", "normal"}:
        return "普通"
    return "普通"


def normalize_status(status: str) -> str:
    if contains_any(status, FINISHED_WORDS):
        return "已完成"
    return "未完成"


def default_context_label(category: str, sub_type: str, value: str) -> str:
    if not value:
        return ""
    if category == "学习":
        return "课程"
    if category == "工作":
        return "公司/岗位" if sub_type == "面试" else "项目"
    if category == "生活":
        return "缴费项目" if sub_type == "缴费" else "事项"
    return ""


def looks_finished(text: str) -> bool:
    return contains_any(text, FINISHED_WORDS) and not contains_any(text, UNFINISHED_WORDS)


def clean_note(note: str) -> str:
    if not note:
        return ""
    return note.strip()[:80]


def clean_source_text(source_text: str) -> str:
    if not source_text:
        return ""
    return source_text.strip()[:120]


def looks_like_independent_homework_list(tasks: list[ParsedTask]) -> bool:
    titles = {task.title.replace(" ", "") for task in tasks if task.subType == "作业"}
    if len(titles) < 2:
        return False
    numbered = [title for title in titles if "作业" in title and any(ch.isdigit() for ch in title)]
    return len(numbered) >= 2


def looks_like_single_notice(tasks: list[ParsedTask]) -> bool:
    text = " ".join(task.title + " " + task.subType + " " + task.note for task in tasks)
    if looks_like_independent_homework_list(tasks):
        return False
    return contains_any(text, ("面试", "会议", "报名", "通知", "入职")) and len({task.time for task in tasks if task.time}) <= 3


def merge_notice_tasks(tasks: list[ParsedTask]) -> ParsedTask:
    first = tasks[0]
    text = "\n".join(part for task in tasks for part in (task.title, task.note, task.location, task.materials) if part)
    title = next((task.title for task in tasks if any(word in task.title for word in ("面试", "会议", "报名", "通知", "入职"))), first.title)
    sub_type = next((task.subType for task in tasks if task.subType in {"面试", "会议"}), first.subType)
    return first.model_copy(
        update={
            "title": title or "待处理通知",
            "subType": sub_type,
            "importance": "高",
            "time": next((task.time for task in tasks if task.time), ""),
            "location": "；".join(dict.fromkeys(task.location for task in tasks if task.location))[:80],
            "materials": "；".join(dict.fromkeys(task.materials for task in tasks if task.materials))[:120],
            "note": "\n".join(dict.fromkeys(line.strip() for line in text.splitlines() if line.strip() and line.strip() != title))[:500],
            "status": "未完成",
        }
    )


def extract_json_object(text: str) -> str:
    clean = text.strip()
    if clean.startswith("```"):
        clean = clean.strip("`").strip()
        if clean.lower().startswith("json"):
            clean = clean[4:].strip()
    start = clean.find("{")
    end = clean.rfind("}")
    if start < 0 or end < start:
        raise ValueError("No JSON object found in model response")
    return clean[start : end + 1]
