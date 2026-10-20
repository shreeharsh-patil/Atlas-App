# ---- Meeting Atlas: app-owned R8 rules -------------------------------------

# Enum constant names are persisted (Room TEXT columns via MeetingConverters)
# and serialized (kotlinx.serialization), so they must survive obfuscation.
-keepclassmembers enum com.meetingatlas.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers enum com.meetingatlas.** {
    public static ** *;
}

# Room reaches the generated implementation reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.meetingatlas.data.MeetingDatabase_Impl { <init>(); }

# WorkManager instantiates ListenableWorker subclasses by name.
-keepnames class com.meetingatlas.notes.MeetingNotesWorker
-keepclassmembers class com.meetingatlas.notes.MeetingNotesWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep annotations readable for Hilt entry points and kotlinx.serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
