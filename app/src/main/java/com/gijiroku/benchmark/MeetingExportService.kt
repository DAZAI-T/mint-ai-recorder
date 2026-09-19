package com.gijiroku.benchmark

import android.content.Context

class MeetingExportService(
    context: Context,
    private val databaseNamespace: String = ""
) {
    private val appContext = context.applicationContext ?: context

    fun buildDocument(meetingId: String): MeetingExportDocument =
        EncryptedMeetingDatabase(appContext, databaseNamespace).use { database ->
            val metadata = database.readMeeting(meetingId) ?: error(AppLanguage.text("会議が見つかりません", "Meeting not found"))
            MeetingExportDocumentBuilder.build(metadata, database.listCurrentRevisions(meetingId))
        }
}
