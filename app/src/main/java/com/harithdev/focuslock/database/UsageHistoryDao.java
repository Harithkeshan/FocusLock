package com.harithdev.focuslock.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.harithdev.focuslock.model.DailySummary;
import com.harithdev.focuslock.model.UsageHistory;

import java.util.List;

@Dao
public interface UsageHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(UsageHistory history);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<UsageHistory> historyList);

    @Query("SELECT * FROM usage_history WHERE date = :date ORDER BY totalUsedMs DESC")
    List<UsageHistory> getUsageForDate(String date);

    @Query("SELECT date, SUM(totalUsedMs) as totalUsedMs FROM usage_history " +
           "WHERE date >= :startDate GROUP BY date ORDER BY date ASC")
    List<DailySummary> getDailySummaries(String startDate);

    @Query("SELECT * FROM usage_history WHERE packageName = :packageName AND date >= :startDate ORDER BY date ASC")
    List<UsageHistory> getAppHistory(String packageName, String startDate);

    @Query("DELETE FROM usage_history WHERE date < :cutoffDate")
    void deleteOldHistory(String cutoffDate);
}
