-keep class com.google.ai.edge.litertlm.** { *; }
-keep class app.pocket.local.NativeDiffusion { *; }
-keep interface app.pocket.local.NativeProgressListener { *; }
-keep class app.pocket.local.ModelSetupPlan { *; }
-keep class app.pocket.local.RemoteModelFile { *; }
-keep class app.pocket.local.PhoneLibraryIndex { *; }
-keep class app.pocket.local.IndexedModelSource { *; }
-keep class app.pocket.local.PhoneModelSource { *; }
-keep class app.pocket.local.PhoneModelScan { *; }
-keep class app.pocket.local.FoundPhoneModel { *; }
-keep enum app.pocket.local.PhoneModelKind { *; }
-keepclassmembers class app.pocket.local.ImageGenerationService { public void onProgress(int, int); }

-keep class app.pocket.local.SavedChat { *; }
-keep class app.pocket.local.ChatLine { *; }
-keep enum app.pocket.local.ModelRole { *; }

-keep class app.pocket.local.GenerationMetrics { *; }
