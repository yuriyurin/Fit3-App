package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import io.github.yuriyurin.fit3companion.protocol.ActivityGoals
import io.github.yuriyurin.fit3companion.protocol.Fit3ActivityGoalCodec
import java.util.UUID

/** Desired settings survive process death, upgrades and a disconnected watch. No health counters. */
object Fit3ActivityGoalStore {
    data class Saved(val goals: ActivityGoals, val revision: Long, val uuid: UUID, val pending: Boolean)
    private fun prefs(context: Context) = context.getSharedPreferences("fit3_activity", Context.MODE_PRIVATE)
    fun load(context: Context): Saved {
        val p = prefs(context)
        return Saved(ActivityGoals(p.getInt("step_goal", 6000).coerceIn(1000, 50_000),
            p.getInt("active_minute_goal", 90).coerceIn(30, 360),
            p.getInt("active_calorie_goal", 500).coerceIn(100, 5000)),
            p.getLong("goals_revision", 0),
            runCatching { UUID.fromString(p.getString("goals_uuid", "00000000-0000-0000-0000-000000000000")) }
                .getOrDefault(UUID(0, 0)), p.getBoolean("goals_pending", false))
    }
    @Synchronized fun save(context: Context, goals: ActivityGoals): Saved {
        require(goals.valid())
        val saved = Saved(goals, maxOf(System.currentTimeMillis(), load(context).revision + 1000), UUID.randomUUID(), true)
        check(prefs(context).edit().putInt("step_goal", goals.steps)
            .putInt("active_minute_goal", goals.minutes).putInt("active_calorie_goal", goals.calories)
            .putLong("goals_revision", saved.revision).putString("goals_uuid", saved.uuid.toString())
            .putBoolean("goals_pending", true).commit())
        return saved
    }
    @Synchronized fun confirm(context: Context, revision: Long): Boolean {
        if (load(context).revision != revision) return false
        val wireTime = revision / 1000 * 1000 // Health timestamps have second precision.
        check(prefs(context).edit().putBoolean("goals_pending", false)
            .putLong("goal_seen_9", wireTime).putLong("goal_seen_124", wireTime)
            .putLong("goal_seen_125", wireTime).commit())
        return true
    }
    @Synchronized fun observe(context: Context, records: List<Fit3ActivityGoalCodec.Observation>) {
        // A stale batch must never overwrite a newly edited goal waiting for acknowledgement.
        if (load(context).pending || records.isEmpty()) return
        val p = prefs(context)
        val editor = p.edit()
        records.groupBy { it.type }.forEach { (type, recordsForType) ->
            val record = recordsForType.maxBy { it.updatedAt }
            if (record.updatedAt >= p.getLong("goal_seen_$type", 0)) {
                val key = when (type) { 9 -> "step_goal"; 0x7C -> "active_calorie_goal"; else -> "active_minute_goal" }
                editor.putInt(key, record.value).putLong("goal_seen_$type", record.updatedAt)
            }
        }
        check(editor.commit())
    }
}
