package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.CaptureSessionLog
import com.example.model.PairedDevice
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY lastConnectedAt DESC")
    fun getAllDevices(): Flow<List<PairedDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: PairedDevice)

    @Update
    suspend fun updateDevice(device: PairedDevice)

    @Delete
    suspend fun deleteDevice(device: PairedDevice)

    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1")
    suspend fun getDeviceById(id: String): PairedDevice?
}

@Dao
interface SessionLogDao {
    @Query("SELECT * FROM capture_sessions ORDER BY startTime DESC LIMIT 50")
    fun getAllSessions(): Flow<List<CaptureSessionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(log: CaptureSessionLog)

    @Query("DELETE FROM capture_sessions")
    suspend fun clearHistory()
}
