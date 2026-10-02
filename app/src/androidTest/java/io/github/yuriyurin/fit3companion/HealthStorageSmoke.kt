package io.github.yuriyurin.fit3companion

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import io.github.yuriyurin.fit3companion.ble.Fit3HealthStore
import io.github.yuriyurin.fit3companion.ble.Fit3BackupStore
import io.github.yuriyurin.fit3companion.ble.BleSnapshot
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthCodec
import io.github.yuriyurin.fit3companion.protocol.ActivityGoals
import io.github.yuriyurin.fit3companion.protocol.Fit3ActivityGoalCodec
import io.github.yuriyurin.fit3companion.ble.Fit3ActivityGoalStore
import io.github.yuriyurin.fit3companion.ble.CustomFaceLibrary
import io.github.yuriyurin.fit3companion.ble.InstalledFacePreviewStore
import org.json.JSONObject
import java.io.File

/** Runs solely in a unique test directory/pref namespace, never in the user's Health database. */
class HealthStorageSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val languageResult = LanguageResourcesSmoke.verify(targetContext)
            val prefix = "health-smoke-${System.nanoTime()}"
            val root = File(targetContext.cacheDir, prefix).apply { check(mkdirs()) }
            val sandbox = object : ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = root
                override fun getDatabasePath(name: String): File = File(root, name)
                override fun getSharedPreferences(name: String, mode: Int) =
                    baseContext.getSharedPreferences("$prefix-$name", mode)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                                                 errorHandler: DatabaseErrorHandler?) =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            }
            check(!sandbox.getDatabasePath("health-v2.db").exists()) { "Use a fresh test APK sandbox" }
            val faceSources = io.github.yuriyurin.fit3companion.ble.InstalledFaceSources(sandbox)
            val watchedFace = io.github.yuriyurin.fit3companion.protocol.Fit3SapCodec.InstalledFace(80, 0, "wf_name-00080", true, "0")
            val sampleKey = "a".repeat(64)
            check(faceSources.reconcile("AA:BB", listOf(watchedFace)).isEmpty())
            faceSources.confirmed("AA:BB", watchedFace, sampleKey)
            check(io.github.yuriyurin.fit3companion.ble.InstalledFaceSources(sandbox)
                .reconcile("aa:bb", listOf(watchedFace))[80 to 0] == sampleKey)
            check(faceSources.reconcile("CC:DD", listOf(watchedFace)).isEmpty())
            check(faceSources.reconcile("AA:BB", listOf(watchedFace.copy(sampler = 1))).isEmpty())
            faceSources.confirmed("AA:BB", watchedFace, sampleKey)
            check(faceSources.reconcile("AA:BB", listOf(watchedFace.copy(version = "10006"))).isEmpty())
            faceSources.confirmed("AA:BB", watchedFace, sampleKey)
            faceSources.confirmed("AA:BB", watchedFace, null)
            check(faceSources.reconcile("AA:BB", listOf(watchedFace)).isEmpty())
            faceSources.confirmed("AA:BB", watchedFace, sampleKey)
            check(faceSources.reconcile("AA:BB", emptyList()).isEmpty())
            // Optional real BIN copied to the app solely for this isolated test.
            val customSample = File(targetContext.filesDir, "test-custom-face.bin")
            var customResult = ""
            if (customSample.exists()) {
                val bytes = customSample.readBytes()
                val library = CustomFaceLibrary(sandbox)
                val face = library.add(bytes, "test-face.bin")
                check(face.faceId == 80 && face.styles == listOf(0))
                check(library.list().size == 1)
                check(library.add(bytes, "duplicate.bin").key == face.key)
                check(library.list().size == 1)
                check(library.preview(face.key)?.width == 178)
                // Independent durable image survives removal of the source BIN and re-opening.
                val previews = InstalledFacePreviewStore(sandbox)
                previews.prepare(bytes, face.key, face.name, listOf(0))
                val expectedPixels = library.preview(face.key)!!
                check(previews.preview(face.key, 0)!!.sameAs(expectedPixels))
                check(previews.preview(face.key, 1) == null) // Never borrow another sampler's artwork.
                check(previews.preview("../health-v2.db", 0) == null)
                check(runCatching { previews.prepare(bytes, "b".repeat(64), face.name, listOf(0)) }.isFailure)
                library.rename(face.key, "Мой циферблат")
                check(CustomFaceLibrary(sandbox).list().single().name == "Мой циферблат")
                check(previews.name(face.key) == "Мой циферблат")
                check(library.binary(face.key).contentEquals(bytes))
                // Preparing transmission must not change original library identity/name/artwork.
                val prepared = io.github.yuriyurin.fit3companion.ble.FaceBinValidator.prepareForInstallation(bytes)
                check(prepared.report.faceId == face.faceId)
                check(io.github.yuriyurin.fit3companion.ble.FaceBinValidator.validate(prepared.binary).styles == face.styles)
                check(library.binary(face.key).contentEquals(bytes))
                check(library.list().single().name == "Мой циферблат")
                check(previews.preview(face.key, 0)!!.sameAs(expectedPixels))
                check(runCatching { library.rename(face.key, " ") }.isFailure)
                check(runCatching { library.binary("../health-v2.db") }.isFailure)
                library.delete(face.key)
                check(library.list().isEmpty())
                val reopened = InstalledFacePreviewStore(sandbox)
                check(reopened.preview(face.key, 0)!!.sameAs(expectedPixels))
                check(reopened.name(face.key) == "Мой циферблат")
                check(library.preview(face.key) == null)
                // Upgrade path: deletion itself preserves a b32 source even before UI reads it.
                val migrationSandbox = object : ContextWrapper(sandbox) {
                    override fun getFilesDir() = File(root, "migration").apply { mkdirs() }
                }
                val oldLibrary = CustomFaceLibrary(migrationSandbox)
                val oldFace = oldLibrary.add(bytes, "old.bin")
                faceSources.confirmed("AA:BB", watchedFace, oldFace.key)
                oldLibrary.delete(oldFace.key)
                check(oldLibrary.list().isEmpty())
                check(InstalledFacePreviewStore(migrationSandbox).preview(oldFace.key, 0)!!.sameAs(expectedPixels))
                // A broken stored PNG fails closed, without falling back to an unrelated stock face.
                File(sandbox.filesDir, "installed-face-previews/${face.key}-0.png").writeText("broken")
                check(reopened.preview(face.key, 0) == null)
                check(customSample.exists()) // User's source is never removed.
                customResult = "PASS: custom BIN import, durable independent PNG/name after deletion, migration, sampler isolation and corrupt preview handling\n"
            }
            // Fixed synthetic timestamps and values, unrelated to a user's device history.
            val testTime = java.time.Instant.parse("2020-01-02T10:00:00Z").toEpochMilli()
            val old = Fit3HealthCodec.State(steps = 100, sleepMinutes = 420,
                spo2 = 96, spo2At = testTime, lastSyncMillis = testTime)
            val legacy = Fit3HealthStore.encode(old).put("version", 1)
            File(sandbox.filesDir, "health-cache-v1.json").writeText(legacy.toString())
            check(Fit3HealthStore.load(sandbox).sleepMinutes == 420)

            // b26: sender association and minute bins survive export/import onto a phone.
            val owned = old.copy(stepSourceAddress = "AA:BB:CC:DD:EE:FF",
                stepRecords = mapOf(testTime to Fit3HealthCodec.StepRecord(10, 10, 0, 5.0, .5, 10000)))
            val decoded = Fit3HealthStore.decode(Fit3HealthStore.encode(owned))
            check(decoded.stepSourceAddress == owned.stepSourceAddress)
            check(decoded.stepRecords == owned.stepRecords)

            val raw = byteArrayOf(0x82.toByte(), 1, 0xE0.toByte(), 0x28, 0x27)
            val next = old.copy(steps = 103, lastSuccessfulSyncMillis = testTime + 10000)
            Fit3HealthStore.accept(sandbox, "test-watch", next, raw, testTime + 10000)
            Fit3HealthStore.accept(sandbox, "test-watch", next, raw, testTime + 11000)
            check(Fit3HealthStore.load(sandbox).steps == 103)
            check(Fit3HealthStore.load(sandbox).sleepMinutes == 420)
            val exported = Fit3HealthStore.export(sandbox, next)
            check(exported.getJSONArray("packetArchive").length() == 1)

            // Inject an actual SQLite write failure after insertion of the incoming packet.
            val path = sandbox.getDatabasePath("health-v2.db").absolutePath
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER fail_snapshot BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            }
            var failed = false
            try { Fit3HealthStore.accept(sandbox, "test-watch", next.copy(steps = 999),
                raw + byteArrayOf(7), testTime + 12000) } catch (_: Exception) { failed = true }
            check(failed)
            check(Fit3HealthStore.load(sandbox).steps == 103)
            check(Fit3HealthStore.export(sandbox, next).getJSONArray("packetArchive").length() == 1)
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TRIGGER fail_snapshot")
            }

            // New JSON includes the archive; repeated import remains idempotent.
            val goals = ActivityGoals(7500, 120, 650)
            val savedGoals = Fit3ActivityGoalStore.save(sandbox, goals)
            check(Fit3ActivityGoalStore.load(sandbox) == savedGoals)
            check(savedGoals.pending)
            Fit3ActivityGoalStore.observe(sandbox, listOf(Fit3ActivityGoalCodec.Observation(9, 6000, savedGoals.revision + 1000)))
            check(Fit3ActivityGoalStore.load(sandbox).goals == goals) // Pending edit wins over an incoming old batch.
            check(!Fit3ActivityGoalStore.confirm(sandbox, savedGoals.revision - 1))
            check(Fit3ActivityGoalStore.confirm(sandbox, savedGoals.revision))
            check(!Fit3ActivityGoalStore.load(sandbox).pending)
            Fit3ActivityGoalStore.observe(sandbox, listOf(Fit3ActivityGoalCodec.Observation(9, 6000, savedGoals.revision - 1000)))
            check(Fit3ActivityGoalStore.load(sandbox).goals == goals)
            val changedGoals = Fit3ActivityGoalStore.save(sandbox, ActivityGoals(8000, 100, 700))
            check(!Fit3ActivityGoalStore.confirm(sandbox, savedGoals.revision)) // Earlier acknowledgement cannot clear new edit.
            check(Fit3ActivityGoalStore.load(sandbox).pending)
            check(Fit3ActivityGoalStore.confirm(sandbox, changedGoals.revision))
            Fit3ActivityGoalStore.observe(sandbox, listOf(
                Fit3ActivityGoalCodec.Observation(9, 7500, changedGoals.revision + 1000),
                Fit3ActivityGoalCodec.Observation(124, 650, changedGoals.revision + 1000),
                Fit3ActivityGoalCodec.Observation(125, 120, changedGoals.revision + 1000)))
            check(Fit3ActivityGoalStore.load(sandbox).goals == goals)
            check(Fit3HealthStore.load(sandbox).steps == 103) // Goals never become measured steps.
            val backup = Fit3BackupStore.exportJson(sandbox, BleSnapshot(health = next))
            val goalJson = JSONObject(backup).getJSONObject("preferences").getJSONObject("activityGoals")
            check(goalJson.getInt("steps") == 7500 && goalJson.getInt("minutes") == 120 && goalJson.getInt("calories") == 650)
            Fit3ActivityGoalStore.save(sandbox, ActivityGoals())
            check(Fit3BackupStore.importJson(sandbox, backup))
            check(Fit3ActivityGoalStore.load(sandbox).goals == goals)
            check(Fit3ActivityGoalStore.load(sandbox).pending)
            val invalidGoals = JSONObject(backup).apply {
                getJSONObject("preferences").getJSONObject("activityGoals").put("minutes", 0)
            }
            check(!Fit3BackupStore.importJson(sandbox, invalidGoals.toString()))
            check(Fit3ActivityGoalStore.load(sandbox).goals == goals)
            check(Fit3HealthStore.load(sandbox).steps == 103)
            val withLanguage = JSONObject(backup).apply {
                getJSONObject("preferences").put("language", "en")
            }
            check(Fit3BackupStore.importJson(sandbox, withLanguage.toString()))
            check(sandbox.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE)
                .getString("language", null) == "en")
            withLanguage.getJSONObject("preferences").put("language", "invalid")
            check(Fit3BackupStore.importJson(sandbox, withLanguage.toString()))
            check(sandbox.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE)
                .getString("language", null) == "en")
            check(JSONObject(backup).getInt("version") == 3)
            check(Fit3BackupStore.importJson(sandbox, backup))
            check(Fit3BackupStore.importJson(sandbox, backup))
            check(Fit3HealthStore.export(sandbox, next).getJSONArray("packetArchive").length() == 1)
            check(Fit3HealthStore.load(sandbox).sleepMinutes == 420)
            val malformed = JSONObject(exported.toString())
            malformed.getJSONArray("packetArchive").getJSONObject(0).put("received", -1)
            failed = false
            try { Fit3HealthStore.import(sandbox, malformed) } catch (_: Exception) { failed = true }
            check(failed && Fit3HealthStore.load(sandbox).steps == 103)
            result.putString("stream", languageResult + customResult + "PASS: durable watchface source, device/sampler isolation, version change, official replacement and deletion\n" + "PASS: legacy migration, durable packet+snapshot, deduplication, rollback, JSON backup, repeated restore, durable goals and stale-ACK protection\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
