package app.pocket.local

internal object QwenThinking {
    const val adapter = "litert-qwen3-thinking-v1"
    const val contextTokens = 1280
    const val outputTokens = 640
    const val thinkingTokens = 256
    const val channel = "thought"
    const val start = "<think>\n"
    const val end = "\n</think>"

    // The export's bundled template expects strings. LiteRT-LM conversations
    // supply content items, so render text items using Qwen3's ChatML format.
    val template = """
        {%- for message in messages -%}
            {{- '<|im_start|>' + message['role'] + '\n' -}}
            {%- for item in message['content'] -%}
                {%- if item['type'] == 'text' -%}
                    {{- item['text'] -}}
                {%- endif -%}
            {%- endfor -%}
            {{- '<|im_end|>\n' -}}
        {%- endfor -%}
        {%- if add_generation_prompt -%}
            {{- '<|im_start|>assistant\n' -}}
        {%- endif -%}
    """.trimIndent()
}
