package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.time.ZoneId

/** 起止月份均包含在季节内，结束月小于起始月即跨年。 */
data class SeasonMonths(val firstMonth: Int, val lastMonth: Int) {
    init { require(firstMonth in 1..12 && lastMonth in 1..12) { "季节月份应在 1 至 12 之间" } }
}

interface SeasonSettings {
    /** 返回四季当前的月份，未自定义的季节沿用默认值。 */
    suspend fun readSeasonMonths(): Map<NamedSeason, SeasonMonths>

    /** 一次保存本次修改的所有季节，已有草稿和正式记录中的实际范围继续保留。 */
    suspend fun saveSeasonMonths(changes: Map<NamedSeason, SeasonMonths>)

    suspend fun createSeasonTime(year: Int, season: NamedSeason, zone: ZoneId = ZoneId.systemDefault()): EventTime.Season {
        val months = readSeasonMonths().getValue(season)
        return EventTime.Season(year, season, months.firstMonth, months.lastMonth, zone)
    }
}

@Entity(tableName = "season_settings")
internal data class SeasonSettingRow(@PrimaryKey val season: NamedSeason, @Embedded val months: SeasonMonths)

@Dao
internal interface SeasonSettingsDao {
    @Query("SELECT * FROM season_settings")
    suspend fun settings(): List<SeasonSettingRow>

    @Upsert
    suspend fun save(rows: List<SeasonSettingRow>)
}
