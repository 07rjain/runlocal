package app.pocket.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository() = ChatRepository(temporary.newFolder())

    @Test fun completionStaysWithOriginAfterSelectingAnotherChat() {
        val repo = repository()
        val first = repo.create("text:one", "First model")
        val reply = ChatLine("assistant", "Working", state = "pending")
        first.messages.add(ChatLine("user", "Hello")); first.messages.add(reply); repo.save(first)
        val second = repo.create("text:two", "Second model")
        assertTrue(repo.finish(first.id, reply.id, "Hello back"))
        assertEquals(second.id, repo.selected()!!.id)
        assertTrue(repo.get(second.id)!!.messages.isEmpty())
        assertEquals("Hello back", repo.get(first.id)!!.messages.last().text)
    }
    @Test fun imageCompletionIsIdempotentAcrossRepeatedPolls() {
        val repo = repository(); val chat = repo.create("image:sd15")
        val reply = ChatLine("assistant", "Working", state = "pending")
        chat.messages.add(reply); repo.save(chat)
        assertTrue(repo.finish(chat.id, reply.id, "Generated locally", "/generated/output.png"))
        assertFalse(repo.finish(chat.id, reply.id, "Late error", failed = true))
        assertEquals("/generated/output.png", repo.get(chat.id)!!.messages.single().image)
        assertEquals("complete", repo.get(chat.id)!!.messages.single().state)
    }
    @Test fun persistsDraftAndExactImageAssetsAcrossRestart() {
        val directory = temporary.newFolder(); val repo = ChatRepository(directory)
        val chat = repo.create("image:moss", "Adapter A")
        chat.draft = "A mountain"; chat.trigger = "trigger_a"
        chat.imageAssets = mapOf("LORA" to "adapter-a", "DIFFUSION" to "base-a"); repo.save(chat)
        val reopened = ChatRepository(directory).selected()!!
        assertEquals("A mountain", reopened.draft)
        assertEquals(chat.imageAssets, reopened.imageAssets)
        assertEquals("trigger_a", reopened.trigger)
    }
    @Test fun failedReplyIsSavedWithoutDeletingOtherTurns() {
        val repo = repository(); val chat = repo.create()
        val reply = ChatLine("assistant", "Working", state = "pending", turn = "one")
        chat.messages.add(ChatLine("user", "Question", turn = "one")); chat.messages.add(reply); repo.save(chat)
        repo.finish(chat.id, reply.id, "Interrupted", failed = true)
        assertEquals(2, repo.get(chat.id)!!.messages.size)
        assertEquals("error", repo.get(chat.id)!!.messages.last().state)
    }
    @Test fun deleteOnlyRemovesTheChosenConversationAndRejectsTraversal() {
        val repo = repository(); val first = repo.create(); val second = repo.create()
        repo.delete(first.id)
        assertNull(repo.get(first.id)); assertNotNull(repo.get(second.id))
        assertThrows(IllegalArgumentException::class.java) { repo.delete("../outside") }
    }
    @Test fun contextOmitsFailedTurnsPendingRepliesAndImageOutputs() {
        val messages = listOf(
            ChatLine("user", "Failed question", turn = "failed"),
            ChatLine("assistant", "Runtime error", state = "error", turn = "failed"),
            ChatLine("user", "Good question", turn = "good"),
            ChatLine("assistant", "Good reply", turn = "good"),
            ChatLine("assistant", "An image", image = "/output.png"),
            ChatLine("user", "Follow up", turn = "current"),
            ChatLine("assistant", "Working", state = "pending", turn = "current")
        )
        assertEquals(listOf("Good question", "Good reply", "Follow up"), textConversationContext(messages).map { it.text })
    }

    @Test fun metricsSurviveRestartAndStayWithOriginatingReply() {
        val directory = temporary.newFolder(); val repo = ChatRepository(directory)
        val chat = repo.create(); val reply = ChatLine("assistant", "Working", state = "pending")
        chat.messages.add(reply); repo.save(chat)
        val other = repo.create()
        val metrics = GenerationMetrics("CPU only", 50.0, 100.0, 500.0, 12, 25.0, 40.0)
        assertTrue(repo.finish(chat.id, reply.id, "Done", metrics = metrics))
        val reopened = ChatRepository(directory)
        assertEquals(metrics, reopened.get(chat.id)!!.messages.single().metrics)
        assertEquals(other.id, reopened.selected()!!.id)
        assertTrue(reopened.get(other.id)!!.messages.isEmpty())
    }
    @Test fun oldMessagesWithoutMetricsStillLoadAndFailedTurnsNeverStoreSuccessMetrics() {
        val repo = repository(); val chat = repo.create()
        chat.messages.add(ChatLine("assistant", "Working", state = "pending")); repo.save(chat)
        assertNull(repo.get(chat.id)!!.messages.single().metrics)
        repo.finish(chat.id, chat.messages.single().id, "Failed", failed = true,
            metrics = GenerationMetrics("CPU only", 0.0, null, 10.0, null, null, null))
        assertNull(repo.get(chat.id)!!.messages.single().metrics)
    }

}
