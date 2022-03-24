/*
 * Copyright (c) 2022 gematik GmbH
 * 
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the Licence);
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 *     https://joinup.ec.europa.eu/software/page/eupl
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * 
 */

package de.gematik.ti.erp.app.prescription.repository

import de.gematik.ti.erp.app.DispatchProvider
import de.gematik.ti.erp.app.db.entities.LowDetailEventSimple
import de.gematik.ti.erp.app.db.entities.Task
import de.gematik.ti.erp.app.db.entities.TaskStatus
import de.gematik.ti.erp.app.db.entities.TaskWithMedicationDispense
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.hl7.fhir.r4.model.Communication
import org.hl7.fhir.r4.model.MedicationDispense

typealias FhirTask = org.hl7.fhir.r4.model.Task
typealias FhirCommunication = Communication

enum class RemoteRedeemOption(val type: String) {
    Local(type = "onPremise"),
    Shipment(type = "shipment"),
    Delivery(type = "delivery")
}

const val PROFILE = "https://gematik.de/fhir/StructureDefinition/ErxCommunicationDispReq"

const val AUDIT_EVENT_PAGE_SIZE = 50

class PrescriptionRepository @Inject constructor(
    private val dispatchProvider: DispatchProvider,
    private val localDataSource: LocalDataSource,
    private val remoteDataSource: RemoteDataSource,
    private val mapper: Mapper
) {

    /**
     * Saves all scanned tasks. It doesn't matter if they already exist.
     */
    suspend fun saveScannedTasks(tasks: List<Task>) {
        tasks.forEach {
            requireNotNull(it.taskId)
            requireNotNull(it.profileName)
            requireNotNull(it.scannedOn)
            requireNotNull(it.scanSessionEnd)
            require(it.rawKBVBundle == null)
        }

        localDataSource.saveTasks(tasks)
    }

    fun tasks(profileName: String) = localDataSource.loadTasks(profileName)
    fun scannedTasksWithoutBundle(profileName: String) =
        localDataSource.loadScannedTasksWithoutBundle(profileName)

    fun syncedTasksWithoutBundle(profileName: String) =
        localDataSource.loadSyncedTasksWithoutBundle(profileName)

    suspend fun redeemPrescription(
        profileName: String,
        communication: Communication
    ): Result<ResponseBody> {
        return remoteDataSource.communicate(profileName, communication)
    }

    /**
     * Communications will be downloaded and persisted local
     */
    suspend fun downloadCommunications(profileName: String): Result<Unit> =
        remoteDataSource.fetchCommunications(profileName).map {
            withContext(dispatchProvider.default()) {
                val communications = mapper.mapFhirBundleToCommunications(it, profileName)
                localDataSource.saveCommunications(communications)
            }
        }

    /**
     * Downloads all tasks and each referenced bundle. Each bundle is persisted locally.
     */
    suspend fun downloadTasks(profileName: String): Result<Int> =
        remoteDataSource.fetchTasks(localDataSource.taskSyncedUpTo(profileName), profileName).mapCatching { bundle ->
            val taskIds = mapper.parseTaskIds(bundle)

            supervisorScope {
                withContext(dispatchProvider.io()) {
                    val results = taskIds.map { taskId ->
                        async {
                            downloadTaskWithKBVBundle(taskId, profileName).mapCatching {
                                deleteLowDetailEvents(taskId)

                                if (it.status == TaskStatus.Completed) {
                                    downloadMedicationDispense(
                                        profileName,
                                        taskId
                                    )
                                }

                                requireNotNull(it.lastModified)
                            }
                        }
                    }.awaitAll()

                    // throw if any result is not parsed correctly
                    results.find { it.isFailure }?.getOrThrow()

                    val lastModified = results.map { it.getOrNull()!! }
                    lastModified.maxOrNull()?.let {
                        localDataSource.updateTaskSyncedUpTo(profileName, it.toInstant())
                    }

                    // return number of bundles saved to db
                    lastModified.size
                }
            }
        }

    private suspend fun downloadTaskWithKBVBundle(
        taskId: String,
        profileName: String
    ): Result<Task> =
        remoteDataSource.taskWithKBVBundle(profileName, taskId).mapCatching {
            val task = mapper.mapFhirBundleToTaskWithKBVBundle(it, profileName)
            localDataSource.saveTask(task)
            task
        }

    private val coroutineScope = CoroutineScope(dispatchProvider.io())
    private val mutex = Mutex()

    fun downloadAllAuditEvents(
        profileName: String
    ) {
        coroutineScope.launch {
            mutex.withLock {
                while (true) {
                    val result = downloadAuditEvents(
                        profileName = profileName,
                        count = AUDIT_EVENT_PAGE_SIZE
                    )
                    if (result.isFailure || result.getOrThrow() != AUDIT_EVENT_PAGE_SIZE) {
                        break
                    }
                }
            }
        }
    }

    private suspend fun downloadAuditEvents(
        profileName: String,
        count: Int? = null
    ): Result<Int> {
        val syncedUpTo = localDataSource.auditEventsSyncedUpTo(profileName)
        return remoteDataSource.allAuditEvents(
            profileName,
            syncedUpTo,
            count = count
        ).mapCatching {
            val auditEvents = mapper.mapFhirBundleToAuditEvents(profileName, it)
            localDataSource.saveAuditEvents(auditEvents)
            localDataSource.setAllAuditEventsSyncedUpTo(profileName)
            auditEvents.size
        }
    }

    private suspend fun downloadMedicationDispense(
        profileName: String,
        taskId: String
    ): Result<Unit> {
        return remoteDataSource.medicationDispense(profileName, taskId).mapCatching {
            // FIXME cast can never succeed
            mapper.mapMedicationDispenseToMedicationDispenseSimple(it as MedicationDispense)
                .let {
                    localDataSource.saveMedicationDispense(it)
                    localDataSource.updateRedeemedOnForSingleTask(
                        taskId,
                        it.whenHandedOver
                    )
                }
        }
    }

    suspend fun saveLowDetailEvent(lowDetailEvent: LowDetailEventSimple) {
        localDataSource.saveLowDetailEvent(lowDetailEvent)
    }

    fun loadLowDetailEvents(taskId: String): Flow<List<LowDetailEventSimple>> =
        localDataSource.loadLowDetailEvents(taskId)

    fun deleteLowDetailEvents(taskId: String) {
        localDataSource.deleteLowDetailEvents(taskId)
    }

    suspend fun deleteTaskByTaskId(
        profileName: String,
        taskId: String,
        isRemoteTask: Boolean
    ): Result<Unit> {
        return if (isRemoteTask) {
            remoteDataSource.deleteTask(profileName, taskId)
        } else {
            Result.success(Unit)
        }.map { localDataSource.deleteTaskByTaskId(taskId) }
    }

    suspend fun updateRedeemedOnForAllTasks(taskIds: List<String>, tm: OffsetDateTime?) {
        localDataSource.updateRedeemedOnForAllTasks(taskIds, tm)
    }

    suspend fun updateRedeemedOnForSingleTask(taskId: String, tm: OffsetDateTime?) {
        localDataSource.updateRedeemedOnForSingleTask(taskId, tm)
    }

    fun loadTasksForRedeemedOn(redeemedOn: OffsetDateTime, profileName: String): Flow<List<Task>> {
        return localDataSource.loadTasksForRedeemedOn(redeemedOn, profileName)
    }

    fun loadTaskWithMedicationDispenseForTaskId(taskId: String): Flow<TaskWithMedicationDispense> {
        return localDataSource.loadTaskWithMedicationDispenseForTaskId(taskId)
    }

    fun loadTasksForTaskId(vararg taskIds: String): Flow<List<Task>> {
        return localDataSource.loadTasksForTaskId(*taskIds)
    }

    suspend fun getAllTasksWithTaskIdOnly(profileName: String): List<String> {
        return localDataSource.getAllTasksWithTaskIdOnly(profileName)
    }

    fun updateScanSessionName(name: String?, scanSessionEnd: OffsetDateTime) {
        localDataSource.updateScanSessionName(name, scanSessionEnd)
    }
}
