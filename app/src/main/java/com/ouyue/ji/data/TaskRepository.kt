package com.ouyue.ji.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(
    private val taskDao: TaskDao
) {
    val tasks: Flow<List<TaskEntity>> = taskDao.observeTasks()

    suspend fun addTask(
        title: String,
        course: String,
        dueTime: String,
        note: String
    ) {
        taskDao.insertTask(
            TaskEntity(
                title = title,
                course = course,
                dueTime = dueTime,
                note = note
            )
        )
    }

    suspend fun toggleTaskStatus(task: TaskEntity) {
        val nextStatus = if (task.status == "未完成") "已完成" else "未完成"
        taskDao.updateTask(task.copy(status = nextStatus))
    }

    suspend fun updateTask(task: TaskEntity) {
        taskDao.updateTask(task)
    }

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteTask(task)
    }

    suspend fun deleteTasks(tasks: List<TaskEntity>) {
        val ids = tasks.map { task -> task.id }.filter { id -> id > 0 }
        if (ids.isNotEmpty()) {
            taskDao.deleteTasksByIds(ids)
        }
    }
}
