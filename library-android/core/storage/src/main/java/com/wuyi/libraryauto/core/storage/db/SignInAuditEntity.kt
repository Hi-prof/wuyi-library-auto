package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "signin_audit",
    indices = [Index(value = ["createdAtEpochSeconds"], name = "idx_signin_audit_created_at")],
)
data class SignInAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val bookingId: String,
    val studentId: String,
    val matchedMinor: Int?,
    val httpStatusCode: Int?,
    val rawMessage: String,
    val signInError: String?,
    val triggerSource: String,
    val createdAtEpochSeconds: Long,
)
