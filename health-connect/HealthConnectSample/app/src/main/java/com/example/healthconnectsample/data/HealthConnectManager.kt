/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.healthconnectsample.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.metadata.DataOrigin
import androidx.health.connect.client.permission.HealthDataRequestPermissions
import androidx.health.connect.client.permission.Permission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.healthconnectsample.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Duration

// The minimum android level that can use Health Connect
const val MIN_SUPPORTED_SDK = Build.VERSION_CODES.O_MR1

/**
 * Demonstrates reading and writing from Health Connect.
 */
class HealthConnectManager(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    var availability = mutableStateOf(HealthConnectAvailability.NOT_SUPPORTED)
        private set

    init {
        checkAvailability()
    }

    fun checkAvailability() {
        availability.value = when {
            HealthConnectClient.isAvailable(context) -> HealthConnectAvailability.INSTALLED
            isSupported() -> HealthConnectAvailability.NOT_INSTALLED
            else -> HealthConnectAvailability.NOT_SUPPORTED
        }
    }

    /**
     * Determines whether all the specified permissions are already granted. It is recommended to
     * call [PermissionController.getGrantedPermissions] first in the permissions flow, as if the
     * permissions are already granted then there is no need to request permissions via
     * [HealthDataRequestPermissions].
     */
    suspend fun hasAllPermissions(permissions: Set<Permission>): Boolean {
        return permissions == healthConnectClient.permissionController.getGrantedPermissions(
            permissions
        )
    }

    /**
     * Obtains a list of [ActivitySession]s in a specified time frame. An Activity Session is a
     * period of time given to an activity, that would make sense to a user, e.g. "Afternoon run"
     * etc. It does not necessarily mean, however, that the user was *running* for that entire time,
     * more that conceptually, this was the activity being undertaken.
     */
    suspend fun readActivitySessions(start: Instant, end: Instant): List<ActivitySession> {
        val request = ReadRecordsRequest(
            recordType = ActivitySession::class,
            timeRangeFilter = TimeRangeFilter.between(start, end)
        )
        val response = healthConnectClient.readRecords(request)
        return response.records
    }

    /**
     * Writes an [ActivitySession] to Health Connect, and additionally writes underlying data for
     * the session too, such as [Steps], [Distance] etc.
     */
    suspend fun writeActivitySession(start: ZonedDateTime, end: ZonedDateTime) {
        healthConnectClient.insertRecords(
            listOf(
                ActivitySession(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    activityType = ActivitySession.ActivityType.RUNNING,
                    title = "My Run #${Random.nextInt(0, 60)}"
                ),
                Steps(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    count = (1000 + 1000 * Random.nextInt(3)).toLong()
                ),
                // Mark a 5 minute pause during the workout
                ActivityEvent(
                    startTime = start.toInstant().plus(10, ChronoUnit.MINUTES),
                    startZoneOffset = start.offset,
                    endTime = start.toInstant().plus(15, ChronoUnit.MINUTES),
                    endZoneOffset = end.offset,
                    eventType = ActivityEvent.EventType.PAUSE
                ),
                Distance(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    distanceMeters = (1000 + 100 * Random.nextInt(20)).toDouble()
                ),
                TotalCaloriesBurned(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    energyKcal = (140 + Random.nextInt(20)) * 0.01
                )
            ) + buildHeartRateSeries(start, end) + buildSpeedSeries(start, end)
        )
    }

    /**
     * Deletes an [ActivitySession] and underlying data.
     */
    suspend fun deleteActivitySession(uid: String) {
        val activitySession = healthConnectClient.readRecord(ActivitySession::class, uid)
        healthConnectClient.deleteRecords(
            ActivitySession::class,
            uidsList = listOf(uid),
            clientIdsList = emptyList()
        )
        val timeRangeFilter = TimeRangeFilter.between(
            activitySession.record.startTime,
            activitySession.record.endTime
        )
        val rawDataTypes: Set<KClass<out Record>> = setOf(
            HeartRateSeries::class,
            SpeedSeries::class,
            Distance::class,
            Steps::class,
            TotalCaloriesBurned::class,
            ActivityEvent::class
        )
        rawDataTypes.forEach { rawType ->
            healthConnectClient.deleteRecords(rawType, timeRangeFilter)
        }
    }

    /**
     * Reads aggregated data and raw data for selected data types, for a given [ActivitySession].
     */
    suspend fun readAssociatedSessionData(
        uid: String
    ): ActivitySessionData {
        val activitySession = healthConnectClient.readRecord(ActivitySession::class, uid)
        // Use the start time and end time from the session, for reading raw and aggregate data.
        val timeRangeFilter = TimeRangeFilter.between(
            startTime = activitySession.record.startTime,
            endTime = activitySession.record.endTime
        )
        val aggregateDataTypes = setOf(
            ActivitySession.ACTIVE_TIME_TOTAL,
            Steps.COUNT_TOTAL,
            Distance.DISTANCE_TOTAL,
            TotalCaloriesBurned.CALORIES_TOTAL,
            HeartRateSeries.BPM_AVG,
            HeartRateSeries.BPM_MAX,
            HeartRateSeries.BPM_MIN,
            SpeedSeries.SPEED_AVG,
            SpeedSeries.SPEED_MAX,
            SpeedSeries.SPEED_MIN
        )
        // Limit the data read to just the application that wrote the session. This may or may not
        // be desirable depending on the use case: In some cases, it may be useful to combine with
        // data written by other apps.
        val dataOriginFilter = listOf(activitySession.record.metadata.dataOrigin)
        val aggregateRequest = AggregateRequest(
            metrics = aggregateDataTypes,
            timeRangeFilter = timeRangeFilter,
            dataOriginFilter = dataOriginFilter
        )
        val aggregateData = healthConnectClient.aggregate(aggregateRequest)
        val speedData = readData<SpeedSeries>(timeRangeFilter, dataOriginFilter)
        val heartRateData = readData<HeartRateSeries>(timeRangeFilter, dataOriginFilter)

        return ActivitySessionData(
            uid = uid,
            totalActiveTime = aggregateData[ActivitySession.ACTIVE_TIME_TOTAL],
            totalSteps = aggregateData[Steps.COUNT_TOTAL],
            totalDistance = aggregateData[Distance.DISTANCE_TOTAL],
            totalEnergyBurned = aggregateData[TotalEnergyBurned.TOTAL],
            minHeartRate = aggregateData[HeartRateSeries.BPM_MIN],
            maxHeartRate = aggregateData[HeartRateSeries.BPM_MAX],
            avgHeartRate = aggregateData[HeartRateSeries.BPM_AVG],
            heartRateSeries = heartRateData,
            speedSeries = speedData,
            minSpeed = aggregateData[SpeedSeries.SPEED_MIN],
            maxSpeed = aggregateData[SpeedSeries.SPEED_MAX],
            avgSpeed = aggregateData[SpeedSeries.SPEED_AVG],
        )
    }

    /**
     * Deletes all existing sleep data.
     */
    suspend fun deleteAllSleepData() {
        val now = Instant.now()
        healthConnectClient.deleteRecords(SleepStage::class, TimeRangeFilter.before(now))
        healthConnectClient.deleteRecords(SleepSession::class, TimeRangeFilter.before(now))
    }

    /**
     * Generates a week's worth of sleep data using both a [SleepSession] to describe the overall
     * period of sleep, and additionally multiple [SleepStage] periods which cover the entire
     * [SleepSession]. For the purposes of this sample, the sleep stage data is generated randomly.
     */
    suspend fun generateSleepData() {
        val records = mutableListOf<Record>()
        // Make yesterday the last day of the sleep data
        val lastDay = ZonedDateTime.now().minusDays(1).truncatedTo(ChronoUnit.DAYS)
        val notes = context.resources.getStringArray(R.array.sleep_notes_array)
        // Create 7 days-worth of sleep data
        for (i in 0..7) {
            val wakeUp = lastDay.minusDays(i.toLong())
                .withHour(Random.nextInt(7, 10))
                .withMinute(Random.nextInt(0, 60))
            val bedtime = wakeUp.minusDays(1)
                .withHour(Random.nextInt(19, 22))
                .withMinute(Random.nextInt(0, 60))
            val sleepSession = SleepSession(
                notes = notes[Random.nextInt(0, notes.size)],
                startTime = bedtime.toInstant(),
                startZoneOffset = bedtime.offset,
                endTime = wakeUp.toInstant(),
                endZoneOffset = wakeUp.offset
            )
            val sleepStages = generateSleepStages(bedtime, wakeUp)
            records.add(sleepSession)
            records.addAll(sleepStages)
        }
        healthConnectClient.insertRecords(records)
    }

    /**
     * Reads sleep sessions for the previous seven days (from yesterday) to show a week's worth of
     * sleep data.
     *
     * In addition to reading [SleepSession]s, for each session, the duration is calculated to
     * demonstrate aggregation, and the underlying [SleepStage] data is also read.
     */
    suspend fun readSleepSessions(): List<SleepSessionData> {
        val lastDay = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS)
            .minusDays(1)
            .withHour(12)
        val firstDay = lastDay
            .minusDays(7)

        val sessions = mutableListOf<SleepSessionData>()
        val sleepSessionRequest = ReadRecordsRequest(
            recordType = SleepSession::class,
            timeRangeFilter = TimeRangeFilter.between(firstDay.toInstant(), lastDay.toInstant()),
            ascendingOrder = false
        )
        val sleepSessions = healthConnectClient.readRecords(sleepSessionRequest)
        sleepSessions.records.forEach { session ->
            val sessionTimeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            val durationAggregateRequest = AggregateRequest(
                metrics = setOf(SleepSession.SLEEP_DURATION_TOTAL),
                timeRangeFilter = sessionTimeFilter
            )
            val aggregateResponse = healthConnectClient.aggregate(durationAggregateRequest)
            val stagesRequest = ReadRecordsRequest(
                recordType = SleepStage::class,
                timeRangeFilter = sessionTimeFilter
            )
            val stagesResponse = healthConnectClient.readRecords(stagesRequest)
            sessions.add(
                SleepSessionData(
                    uid = session.metadata.uid!!,
                    title = session.title,
                    notes = session.notes,
                    startTime = session.startTime,
                    startZoneOffset = session.startZoneOffset,
                    endTime = session.endTime,
                    endZoneOffset = session.endZoneOffset,
                    duration = aggregateResponse[SleepSession.SLEEP_DURATION_TOTAL],
                    stages = stagesResponse.records
                )
            )
        }
        return sessions
    }

    /**
     * Writes [Weight] record to Health Connect.
     */
    suspend fun writeWeightInput(weight: Weight) {
        val records = listOf(weight)
        healthConnectClient.insertRecords(records)
    }

    /**
     * Reads in existing [Weight] records.
     */
    suspend fun readWeightInputs(start: Instant, end: Instant): List<Weight> {
        val request = ReadRecordsRequest(
            recordType = Weight::class,
            timeRangeFilter = TimeRangeFilter.between(start, end)
        )
        val response = healthConnectClient.readRecords(request)
        return response.records
    }

    /**
     * Returns the weekly average of [Weight] records.
     */
    suspend fun computeWeeklyAverage(start: Instant, end: Instant): Double? {
        val request = AggregateRequest(
            metrics = setOf(Weight.WEIGHT_AVG),
            timeRangeFilter = TimeRangeFilter.between(start, end)
        )
        val response = healthConnectClient.aggregate(request)
        return response[Weight.WEIGHT_AVG]
    }

    /*
    * Returns the projected weight for next time point.
    * method: linear interpolation  y=y0 +(x-x0)*(y1-y0)/(x1-x0)
    * assumption: time interval measurements between 3 weight should be same,
    * if using minutes, then all are measured by minutes. if using hours, then all are measured by hours,
    * if using days, then all are measured by days. In my view, measured by days is reasonable in real life.
    * user put the daily weight in app, we can do a projection.
    * but for testing purpose, here we use minutes to do a projection.
    * data source: at least 3 consecutive time weight data.
    *
     */

    suspend fun computeProjectedWeightDemo(weights: List<Weight>): Double?{
        if(weights.size >= 3){
            val last = weights.last()
            val lastThree = weights.takeLast(3)
            val zonedDateTime2 =
                dateTimeWithOffsetOrDefault(last.time, last.zoneOffset)
            val zonedDateTime0 = dateTimeWithOffsetOrDefault(lastThree[0].time, lastThree[0].zoneOffset)
            val interval = ChronoUnit.MINUTES.between(zonedDateTime2, zonedDateTime0) //x2-x0
            val zonedDateTime1 = dateTimeWithOffsetOrDefault(lastThree[1].time, lastThree[1].zoneOffset)
            val intervalSub = ChronoUnit.MINUTES.between(zonedDateTime1,zonedDateTime0) //x1-x0
            val weightDiff = lastThree[1].weightKg - lastThree[0].weightKg  //y1-y0
            val projectedWt = lastThree[0].weightKg + interval*weightDiff/intervalSub  //y0 +(x2-x0)*(y1-y0)/(x1-x0)
            return projectedWt
        }
        return null
    }

    /*
    this function is not for demo purpose, dynamically choose the time interval By user
    if user does the daily measurement, choose ChronoUnit.Days
    if user does the hourly measurement, choose ChronoUnit.Hours
    for this measurement frequency, we need to implement some functionality for the UI part.
    for example, if user do daily measurement, after user input one weight for the day, we can disable add button till next day. Or throw an error: you already input weight for today, do not input twice. But you can edit your input.
    to force user do the projection on daily bases.
     */

    suspend fun computeProjectedWeightLinear(weights: List<Weight>, unit: ChronoUnit): Double?{
        if(weights.size >= 3){
            val last = weights.last()
            val lastThree = weights.takeLast(3)
            val zonedDateTime2 =
                dateTimeWithOffsetOrDefault(last.time, last.zoneOffset)
            val zonedDateTime0 = dateTimeWithOffsetOrDefault(lastThree[0].time, lastThree[0].zoneOffset)
            val interval = unit.between(zonedDateTime2, zonedDateTime0) //x2-x0
            val zonedDateTime1 = dateTimeWithOffsetOrDefault(lastThree[1].time, lastThree[1].zoneOffset)
            val intervalSub = unit.between(zonedDateTime1,zonedDateTime0) //x1-x0
            val weightDiff = lastThree[1].weightKg - lastThree[0].weightKg  //y1-y0
            val projectedWt = lastThree[0].weightKg + interval*weightDiff/intervalSub  //y0 +(x2-x0)*(y1-y0)/(x1-x0)
            return projectedWt
        }
        return null
    }

    /*
    using Lagrange Polynomial interpolation ( Degree of Polynomial is smaller than 6)
     */
    suspend fun computeProjectedWeightPolynomial(weights: List<Weight>, unit: ChronoUnit): Double?{
        if(weights.size >= 3){
            var wts: MutableList<Double> = mutableListOf()
            var times: MutableList<Long> = mutableListOf()
            val startTime = dateTimeWithOffsetOrDefault(weights[0].time, weights[0].zoneOffset)
            for(weight in weights){
                wts.add(weight.weightKg)
                val time = dateTimeWithOffsetOrDefault(weight.time, weight.zoneOffset)
                val dif = unit.between(time, startTime)
                times.add(dif)
            }// obtain a (x,y) list: (x0, y0), (x1, y1), ....

            if(wts.size > 6){
                wts = wts.takeLast(6) as MutableList<Double>
                times = times.takeLast(6) as MutableList<Long>
            }// always use the most recent 6 points if the input data is longer than 6 to avoid too high degree polynomial interpolation
            var cur = 0.0
            for(index in wts.indices-1){ // the last weight not include(we need to do projection)
                var prefixUp = 1.0
                var prefixDown = 1.0
                for(i in times.indices-1){
                    if(index != i){
                        prefixUp *= (times.last() -times[i])
                        prefixDown *= (times[index]-times[i])
                    }
                }
                var prefix = prefixUp/prefixDown
                cur += wts[index] * prefix  //y = y0*(x-x1)(x-x2)../(x0-x1)(x0-x2).. +...
            }
            return cur
        }
        return null
    }









    /**
     * Deletes a [Weight] record.
     */
    suspend fun deleteWeightInput(uid: String) {
        healthConnectClient.deleteRecords(
            Weight::class,
            uidsList = listOf(uid),
            clientIdsList = emptyList()
        )
    }

    /**
     * Obtains a changes token for the specified record types.
     */
    suspend fun getChangesToken(dataTypes: Set<KClass<out Record>>): String {
        val request = ChangesTokenRequest(dataTypes)
        return healthConnectClient.getChangesToken(request)
    }

    /**
     * Creates a [Flow] of change messages, using a changes token as a start point. The flow will
     * terminate when no more changes are available, and the final message will contain the next
     * changes token to use.
     */
    suspend fun getChanges(token: String): Flow<ChangesMessage> = flow {
        var nextChangesToken = token
        do {
            val response = healthConnectClient.getChanges(nextChangesToken)
            if (response.changesTokenExpired) {
                // As described here: https://developer.android.com/guide/health-and-fitness/health-connect/data-and-data-types/differential-changes-api
                // tokens are only valid for 30 days. It is important to check whether the token has
                // expired. As well as ensuring there is a fallback to using the token (for example
                // importing data since a certain date), more importantly, the app should ensure
                // that the changes API is used sufficiently regularly that tokens do not expire.
                throw IOException("Changes token has expired")
            }
            emit(ChangesMessage.ChangeList(response.changes))
            nextChangesToken = response.nextChangesToken
        } while (response.hasMore)
        emit(ChangesMessage.NoMoreChanges(nextChangesToken))
    }

    /**
     * Creates a random list of sleep stages that spans the specified [start] to [end] time.
     */
    private fun generateSleepStages(start: ZonedDateTime, end: ZonedDateTime): List<SleepStage> {
        val sleepStages = mutableListOf<SleepStage>()
        var stageStart = start
        while (stageStart < end) {
            val stageEnd = stageStart.plusMinutes(Random.nextLong(30, 120))
            val checkedEnd = if (stageEnd > end) end else stageEnd
            sleepStages.add(
                SleepStage(
                    stage = randomSleepStage(),
                    startTime = stageStart.toInstant(),
                    startZoneOffset = stageStart.offset,
                    endTime = checkedEnd.toInstant(),
                    endZoneOffset = checkedEnd.offset
                )
            )
            stageStart = checkedEnd
        }
        return sleepStages
    }

    /**
     * Convenience function to reuse code for reading data.
     */
    private suspend inline fun <reified T : Record> readData(
        timeRangeFilter: TimeRangeFilter,
        dataOriginFilter: List<DataOrigin> = listOf()
    ): List<T> {
        val request = ReadRecordsRequest(
            recordType = T::class,
            dataOriginFilter = dataOriginFilter,
            timeRangeFilter = timeRangeFilter
        )
        return healthConnectClient.readRecords(request).records
    }

    private fun buildHeartRateSeries(
        sessionStartTime: ZonedDateTime,
        sessionEndTime: ZonedDateTime
    ): HeartRateSeries {
        val samples = mutableListOf<HeartRate>()
        var time = sessionStartTime
        while (time.isBefore(sessionEndTime)) {
            samples.add(
                HeartRate(
                    time = time.toInstant(),
                    beatsPerMinute = (80 + Random.nextInt(80)).toLong()
                )
            )
            time = time.plusSeconds(30)
        }
        return HeartRateSeries(
            startTime = sessionStartTime.toInstant(),
            startZoneOffset = sessionStartTime.offset,
            endTime = sessionEndTime.toInstant(),
            endZoneOffset = sessionEndTime.offset,
            samples = samples
        )
    }

    private fun buildSpeedSeries(
        sessionStartTime: ZonedDateTime,
        sessionEndTime: ZonedDateTime
    ) = SpeedSeries(
        startTime = sessionStartTime.toInstant(),
        startZoneOffset = sessionStartTime.offset,
        endTime = sessionEndTime.toInstant(),
        endZoneOffset = sessionEndTime.offset,
        samples = listOf(
            Speed(
                time = sessionStartTime.toInstant(),
                metersPerSecond = 2.5
            ),
            Speed(
                time = sessionStartTime.toInstant().plus(5, ChronoUnit.MINUTES),
                metersPerSecond = 2.7
            ),
            Speed(
                time = sessionStartTime.toInstant().plus(10, ChronoUnit.MINUTES),
                metersPerSecond = 2.9
            )
        )
    )

    private fun isSupported() = Build.VERSION.SDK_INT >= MIN_SUPPORTED_SDK

    // Represents the two types of messages that can be sent in a Changes flow.
    sealed class ChangesMessage {
        data class NoMoreChanges(val nextChangesToken: String) : ChangesMessage()
        data class ChangeList(val changes: List<Change>) : ChangesMessage()
    }
}

/**
 * Health Connect requires that the underlying Healthcore APK is installed on the device.
 * [HealthConnectAvailability] represents whether this APK is indeed installed, whether it is not
 * installed but supported on the device, or whether the device is not supported (based on Android
 * version).
 */
enum class HealthConnectAvailability {
    INSTALLED,
    NOT_INSTALLED,
    NOT_SUPPORTED
}
