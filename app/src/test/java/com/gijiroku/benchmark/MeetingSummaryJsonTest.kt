package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingSummaryJsonTest {

    @Test
    fun parsesExpandedSchemaAndKeepsOnlyRealEvidenceTurns() {
        val source = SummarySourceTurn("t000001", 18_420, 18_510, "Speaker 1", "10月15日まで")
        val parsed = MeetingSummaryJson.parse(
            """
                {
                  "title":"試作会議",
                  "purpose":"日程確認",
                  "topics":[{"topic":"試作","discussion":"期限を確認","speakers":["Speaker 1"]}],
                  "decisions":["10月15日までに完成"],
                  "action_items":[],
                  "open_questions":[],
                  "important_information":["期限: 10月15日"],
                  "summary":"日程を確認した",
                  "evidence":[
                    {"field":"decisions","item_index":0,"source_turn_ids":["t000001","invented"]},
                    {"field":"unsupported","item_index":0,"source_turn_ids":["t000001"]}
                  ]
                }
            """.trimIndent(),
            mapOf(source.turnId to source)
        )

        assertEquals("試作会議", parsed.title)
        assertEquals("試作", parsed.topics.single().topic)
        assertEquals(listOf("期限: 10月15日"), parsed.importantInformation)
        assertEquals(listOf("t000001"), parsed.evidence.single().sourceTurnIds)
        assertEquals(listOf(TimeRange(18_420, 18_510)), parsed.evidence.single().sourceRanges)
    }
}
