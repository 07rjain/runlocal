# Third-party components

RunLocal application source is licensed under Apache-2.0 (LICENSE and NOTICE). Third-party components are separately licensed. This file is a release-preparation inventory, not a completed transitive license audit.

- **LiteRT-LM Android 0.18.1**, `com.google.ai.edge.litertlm:litertlm-android`. Upstream: https://github.com/google-ai-edge/LiteRT-LM. The LICENSE and THIRD_PARTY_NOTICE from the resolved 0.18.1 AAR are copied to app/src/main/assets/licenses. Retain the license/notice metadata supplied by this artifact and its transitive dependencies; complete the redistribution review before public release.
- **stable-diffusion.cpp**, commit `290148d2e31ca6ef9757d567905776b5f3646db5`. Upstream: https://github.com/leejet/stable-diffusion.cpp. Source license files are retained in the fetched dependency; the native runtime notices are included under APK assets/licenses.
- **GGML**, commit `d49c49e1dd361ba3c0a77c90b5b1404b83969aa8`. Upstream: https://github.com/leejet/ggml. Retain its upstream license and bundled notices.
- **Gson** is currently supplied transitively by the LiteRT-LM dependency and used for metadata/conversation serialization. Its license and exact resolved version must be included in the final dependency inventory.
- **Gradle wrapper / Android build tools / Kotlin** are build dependencies with their respective upstream terms.

No Cactus code/runtime is incorporated. No model weights are bundled in the source tree or APK. Users download weights under each model publisher's separate license/access conditions. A LoRA license does not replace its base model's license.
