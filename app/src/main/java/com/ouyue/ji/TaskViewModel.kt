package com.ouyue.ji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ouyue.ji.data.TaskEntity
import com.ouyue.ji.data.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class TaskViewModel(
    private val repository: TaskRepository
) : ViewModel() {
    val tasks: Flow<List<TaskEntity>> = repository.tasks

    fun addTask(
        title: String,
        course: String,
        dueTime: String,
        note: String
    ) {
        if (title.isBlank()) return

        viewModelScope.launch {
            repository.addTask(
                title = title.trim(),
                course = course.trim(),
                dueTime = dueTime.trim(),
                note = note.trim()
            )
        }
    }

    fun toggleTaskStatus(task: TaskEntity) {
        viewModelScope.launch {
            repository.toggleTaskStatus(task)
        }
    }

    fun updateTask(task: TaskEntity) {
        if (task.title.isBlank()) return

        viewModelScope.launch {
            repository.updateTask(
                task.copy(
                    title = task.title.trim(),
                    course = task.course.trim(),
                    dueTime = task.dueTime.trim(),
                    note = task.note.trim()
                )
            )
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }

    fun deleteTasks(tasks: List<TaskEntity>) {
        if (tasks.isEmpty()) return

        viewModelScope.launch {
            repository.deleteTasks(tasks)
        }
    }

    class Factory(
        private val repository: TaskRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
                return TaskViewModel(repository) as T
            }

            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
