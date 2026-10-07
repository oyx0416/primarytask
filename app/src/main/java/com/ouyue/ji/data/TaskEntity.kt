package com.ouyue.ji.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val course: String = "",
    val dueTime: String = "",
    val note: String = "",
    val status: String = "未完成"
)
