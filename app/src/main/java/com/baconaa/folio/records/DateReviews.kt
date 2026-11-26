package com.baconaa.folio.records

import androidx.room.*
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

data class DateReviewQuery(val date: LocalDate = LocalDate.now(), val season: NamedSeason? = null)
data class ReviewMatch(val record: RecordCard, val reason: String, val excluded: Boolean)
interface DateReviews {
    suspend fun reviewDates(query: DateReviewQuery): List<ReviewMatch>
    suspend fun setSeasonExcluded(id: RecordId, excluded: Boolean)
}

internal fun EventTime.reviewReason(query: DateReviewQuery, months: SeasonMonths?): String? {
    val reference = query.date
    if (query.season == null) {
        val exact = (this as? EventTime.Exact)?.date ?: return null
        return if (exact.year < reference.year && MonthDay.from(exact) == MonthDay.from(reference))
            "${reference.year - exact.year} 年前的同一天" else null
    }
    val range = dateRange ?: return null
    val (first, last) = requireNotNull(months)
    // 找到不晚于记录末日且已经结束的最近一个季节，无需枚举跨年的长范围。
    val cutoff = minOf(range.endInclusive, reference.minusDays(1))
    var year = cutoff.year - if (cutoff.monthValue < first) 1 else 0
    fun end() = YearMonth.of(year + if (last < first) 1 else 0, last).atEndOfMonth()
    if (end() >= reference) year--
    val start = LocalDate.of(year, first, 1)
    val finish = end()
    if (range.start > finish || range.endInclusive < start) return null
    return if (range.start >= start && range.endInclusive <= finish) "符合${query.season.label}（$start 至 $finish）"
        else "可能符合：记录范围 ${range.start} 至 ${range.endInclusive} 与 $start 至 $finish 部分重合"
}

@Entity(tableName = "season_review_exclusions", foreignKeys = [
    ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE)
])
internal data class SeasonReviewExclusion(@PrimaryKey val recordId: String)

@Dao
internal interface SeasonReviewDao {
    @Query("SELECT recordId FROM season_review_exclusions")
    suspend fun excluded(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun exclude(row: SeasonReviewExclusion)

    @Query("DELETE FROM season_review_exclusions WHERE recordId = :id")
    suspend fun include(id: String)
}
