package com.meetingatlas.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NotesContractsTest {
    @Test fun unknownOrMissingStoredTemplateFallsBackToGeneral() {
        assertEquals(MeetingTemplate.GENERAL, meetingTemplateFrom(null))
        assertEquals(MeetingTemplate.GENERAL, meetingTemplateFrom(""))
        assertEquals(MeetingTemplate.GENERAL, meetingTemplateFrom("LEGACY_UNKNOWN"))
        assertEquals(MeetingTemplate.GENERAL, meetingTemplateFrom("general"))
    }

    @Test fun knownStoredTemplateIsPreserved() {
        MeetingTemplate.entries.forEach { template ->
            assertEquals(template, meetingTemplateFrom(template.name))
        }
    }
}
