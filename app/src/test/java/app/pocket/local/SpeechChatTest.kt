package app.pocket.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechChatTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun voiceIdentitySurvivesRestartAndRejectsMissingOrUnknownVoices() {
        val spec = SpeechModelCatalog.models.first()
        val voice = spec.voices.last()
        val directory = temporary.newFolder()
        val repo = ChatRepository(directory)
        val chat = repo.create(speechChatKey(spec, voice), "${spec.title} · ${voice.name}")
        val restored = ChatRepository(directory).get(chat.id)
        assertEquals(spec to voice, speechChatSelection(requireNotNull(restored).model))
        assertNull(speechChatSelection("speech:${spec.id}:unknown"))
        assertNull(speechChatSelection("speech:${spec.id}"))
        assertNull(speechChatSelection("text:${spec.id}"))
    }

    @Test fun playbackCompletionStaysWithItsOriginAndLateErrorsDoNotOverwriteIt() {
        val repo = ChatRepository(temporary.newFolder())
        val chat = repo.create("speech:voice:juno")
        val reply = ChatLine("assistant", "Preparing", state = "pending")
        chat.messages.add(reply); repo.save(chat)
        val turn = SpeechChatTurn(repo)
        turn.begin(chat.id, reply.id, "Hello from the first chat.")
        val other = repo.create("text:other")
        assertFalse(turn.event("generating", "Preparing speech"))
        assertTrue(turn.event("playing", "Playing"))
        assertFalse(turn.event("error", "Late error"))
        assertFalse(turn.event("complete", "Finished"))
        val saved = requireNotNull(repo.get(chat.id)).messages.single()
        assertEquals("complete", saved.state)
        assertEquals("Hello from the first chat.", saved.text)
        assertTrue(requireNotNull(repo.get(other.id)).messages.isEmpty())
        assertEquals(other.id, requireNotNull(repo.selected()).id)
    }

    @Test fun cancellationBeforePlaybackFinishesPlaceholderAndAllowsRetry() {
        val repo = ChatRepository(temporary.newFolder())
        val chat = repo.create()
        val reply = ChatLine("assistant", "Preparing", state = "pending")
        chat.messages.add(reply); repo.save(chat)
        val turn = SpeechChatTurn(repo)
        turn.begin(chat.id, reply.id, "First reading")
        assertTrue(turn.event("stopped", "Speech stopped."))
        assertEquals("error", requireNotNull(repo.get(chat.id)).messages.single().state)
        assertFalse(turn.event("playing", "Stale playback"))
        val updated = requireNotNull(repo.get(chat.id))
        val retry = ChatLine("assistant", "Preparing", state = "pending")
        updated.messages.add(retry); repo.save(updated)
        turn.begin(chat.id, retry.id, "Second reading")
        assertTrue(turn.event("playing", "Playing"))
        assertEquals("Second reading", requireNotNull(repo.get(chat.id)).messages.last().text)
    }

    @Test fun synthesisFailureIsSavedAsErrorWithoutACompletedReading() {
        val repo = ChatRepository(temporary.newFolder())
        val chat = repo.create()
        val reply = ChatLine("assistant", "Preparing", state = "pending")
        chat.messages.add(reply); repo.save(chat)
        val turn = SpeechChatTurn(repo)
        turn.begin(chat.id, reply.id, "Read this")
        assertTrue(turn.event("error", "Not enough memory"))
        val saved = requireNotNull(repo.get(chat.id)).messages.single()
        assertEquals("error", saved.state)
        assertEquals("Not enough memory", saved.text)
    }
}
