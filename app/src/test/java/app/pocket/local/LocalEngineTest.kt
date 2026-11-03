package app.pocket.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = LocalModel("model-a", "LFM3", File("/models/a.litertlm"))
    private class Session : TextSession {
        val prompts = mutableListOf<String>()
        var closed = false
        var failure: Exception? = null
        override fun send(prompt: String): String { prompts.add(prompt); failure?.let { throw it }; return "Answer: $prompt" }
        override fun close() { closed = true }
    }
    private class Factory : TextSessionFactory {
        data class Open(val model: String, val backend: ProcessingBackend, val history: List<ChatLine>, val session: Session)
        val opened = mutableListOf<Open>()
        override fun open(model: LocalModel, backend: ProcessingBackend, history: List<ChatLine>): TextSession = Session().also {
            opened.add(Open(model.id, backend, history, it))
        }
    }
    @Test fun cpuAndGpuConfigUseRequestedDelegateAndSeparateVersionedCaches() {
        val directory = temporary.newFolder()
        val cpu = textEngineConfig(model, ProcessingBackend.CPU, directory.path)
        val gpu = textEngineConfig(model, ProcessingBackend.GPU, directory.path)
        assertEquals("CPU", cpu.backend.name); assertEquals("GPU", gpu.backend.name)
        assertNotEquals(cpu.cacheDir, gpu.cacheDir)
        assertTrue(cpu.cacheDir!!.contains(TextRuntimeInfo.VERSION))
        assertEquals(2048, cpu.maxNumTokens)
    }
    @Test fun sameChatContinuesNativeConversationWithoutReplayingPreviousTurns() {
        val factory = Factory(); val runtime = LocalEngine("unused", factory)
        val first = ChatLine("user", "Remember kiwi")
        val response = runtime.generate(model, listOf(first), ProcessingBackend.CPU, "chat-a")
        runtime.generate(model, listOf(first, ChatLine("assistant", response), ChatLine("user", "What word?")), ProcessingBackend.CPU, "chat-a")
        assertEquals(1, factory.opened.size)
        assertEquals(listOf("Remember kiwi", "What word?"), factory.opened.single().session.prompts)
        assertTrue(factory.opened.single().history.isEmpty())
    }
    @Test fun switchingChatEvenWithSameModelClosesEngineAndSeedsOnlyTargetHistory() {
        val factory = Factory(); val runtime = LocalEngine("unused", factory)
        runtime.generate(model, listOf(ChatLine("user", "Secret in A")), ProcessingBackend.CPU, "chat-a")
        val target = listOf(ChatLine("user", "B earlier"), ChatLine("assistant", "B reply"), ChatLine("user", "B next"))
        runtime.generate(model, target, ProcessingBackend.CPU, "chat-b")
        assertTrue(factory.opened.first().session.closed)
        assertEquals(target.dropLast(1), factory.opened.last().history)
        assertEquals(listOf("B next"), factory.opened.last().session.prompts)
    }
    @Test fun processorChangeRecreatesEngineAndDoesNotSilentlyFallback() {
        val factory = Factory(); val runtime = LocalEngine("unused", factory)
        runtime.generate(model, listOf(ChatLine("user", "CPU question")), ProcessingBackend.CPU, "chat")
        runtime.generate(model, listOf(ChatLine("user", "GPU question")), ProcessingBackend.GPU, "chat")
        assertTrue(factory.opened.first().session.closed)
        assertEquals(ProcessingBackend.GPU, factory.opened.last().backend)
        factory.opened.last().session.failure = IllegalStateException("GPU driver error")
        val history = listOf(ChatLine("user", "GPU question"), ChatLine("assistant", "Answer: GPU question"), ChatLine("user", "next"))
        assertThrows(IllegalStateException::class.java) { runtime.generate(model, history, ProcessingBackend.GPU, "chat") }
        assertEquals(2, factory.opened.size)
        assertTrue(factory.opened.last().session.closed)
    }
    @Test fun failedDecodeIsNotReusedForRetry() {
        val factory = Factory(); val runtime = LocalEngine("unused", factory)
        val first = ChatLine("user", "First")
        runtime.generate(model, listOf(first), ProcessingBackend.CPU, "chat")
        factory.opened.single().session.failure = IllegalStateException("decode failed")
        val next = listOf(first, ChatLine("assistant", "Answer: First"), ChatLine("user", "Next"))
        assertThrows(IllegalStateException::class.java) { runtime.generate(model, next, ProcessingBackend.CPU, "chat") }
        runtime.generate(model, next, ProcessingBackend.CPU, "chat")
        assertEquals(2, factory.opened.size)
        assertEquals(next.dropLast(1), factory.opened.last().history)
    }
    @Test fun changingModelOrRewritingHistoryResetsState() {
        val factory = Factory(); val runtime = LocalEngine("unused", factory)
        runtime.generate(model, listOf(ChatLine("user", "one")), ProcessingBackend.CPU, "chat")
        runtime.generate(model, listOf(ChatLine("user", "rewritten")), ProcessingBackend.CPU, "chat")
        runtime.generate(model.copy(id = "model-b"), listOf(ChatLine("user", "new model")), ProcessingBackend.CPU, "chat")
        assertEquals(3, factory.opened.size)
        assertTrue(factory.opened.take(2).all { it.session.closed })
    }
    @Test fun replayIsBoundedAndStartsWithAUserTurn() {
        val history = (1..8).flatMap { listOf(ChatLine("user", "question $it"), ChatLine("assistant", "answer $it")) }
        assertEquals(6, recentTextHistory(history).size)
        assertEquals("question 6", recentTextHistory(history).first().text)
        assertTrue(recentTextHistory(listOf(ChatLine("assistant", "orphan"))).isEmpty())
    }
    @Test fun processorDefaultsAndErrorExplanationsDoNotMisdiagnoseModelLoadingAsPromptFailure() {
        assertEquals(ProcessingBackend.CPU, ProcessingBackend.fromPreference(null))
        assertEquals(ProcessingBackend.CPU, ProcessingBackend.fromPreference("broken"))
        assertEquals(ProcessingBackend.GPU, ProcessingBackend.fromPreference("GPU"))
        val format = explainTextFailure(IllegalStateException("UNIMPLEMENTED: Failed to load model from buffer"), ProcessingBackend.CPU)
        assertTrue(format.action.contains(TextRuntimeInfo.VERSION)); assertTrue(format.action.contains("prompt will not fix"))
        val gpu = explainTextFailure(IllegalStateException("OpenCL delegate failed"), ProcessingBackend.GPU)
        assertTrue(gpu.action.contains("CPU only")); assertTrue(gpu.action.contains("not switched"))
        assertTrue(explainTextFailure(IllegalStateException("out of memory"), ProcessingBackend.GPU).summary.contains("memory"))
        assertTrue(explainTextFailure(IllegalStateException("context length exceeded"), ProcessingBackend.CPU).action.contains("new chat"))
    }
    @Test fun longChatRebuildsBoundedContextInsteadOfGrowingNativeStateForever() {
        val factory = Factory(); val runtime = LocalEngine("unused", factory)
        val history = mutableListOf<ChatLine>()
        for (i in 1..6) {
            history.add(ChatLine("user", "question $i"))
            val answer = runtime.generate(model, history.toList(), ProcessingBackend.CPU, "chat")
            history.add(ChatLine("assistant", answer))
        }
        assertTrue(factory.opened.size > 1)
        assertTrue(factory.opened.all { it.history.size <= 6 })
        assertTrue(factory.opened.dropLast(1).all { it.session.closed })
        assertEquals("question 3", factory.opened.last().history.first().text)
    }

}
