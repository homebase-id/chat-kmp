package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import id.homebase.homebasekmppoc.prototype.lib.core.IntRange
import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtc
import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtcRange
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid


/**
 * QueryBatch for batch querying of drive records with sorting and filtering
 *
 * Ported from C# Odin.Core.Storage.Database.Identity.Abstractions.QueryBatch
 * 
 * NOTE: This is a simplified KMP conversion. The original C# code uses database-specific
 * implementations that would need to be adapted for your specific database layer.
 * This provides the structure and logic for porting to your KMP database solution.
 */
class QueryBatch(
    private val odinIdentity: Uuid
) {
    
    companion object {
        // Initialize selectOutputFields statically
        private const val SELECT_OUTPUT_FIELDS = "rowId, jsonHeader";
    }

    /**
     * Builds the shared WHERE clause for queries
     */
    private suspend fun buildSharedWhereAnd(
        listWhere: MutableList<String>,
        aclAnyOf: List<Uuid>? = null,
        filetypesAnyOf: List<Int>? = null,
        datatypesAnyOf: List<Int>? = null,
        globalTransitIdAnyOf: List<Uuid>? = null,
        uniqueIdAnyOf: List<Uuid>? = null,
        tagsAnyOf: List<Uuid>? = null,
        localTagsAnyOf: List<Uuid>? = null,
        archivalStatusAnyOf: List<Int>? = null,
        senderIdAnyOf: List<String>? = null,
        groupIdAnyOf: List<Uuid>? = null,
        userDateSpan: UnixTimeUtcRange? = null,
        tagsAllOf: List<Uuid>? = null,
        localTagsAllOf: List<Uuid>? = null,
        fileSystemType: Int? = null,
        driveId: Uuid
    ): String {
        var leftJoin = ""

        listWhere.add("driveMainIndex.identityId = ${odinIdentity.toSqlString()}")
        listWhere.add("driveMainIndex.driveId = ${driveId.toSqlString()}")
        fileSystemType?.let { listWhere.add("(fileSystemType = $it)") }

        // ACL handling - security group with optional circles
        if (!aclAnyOf.isNullOrEmpty()) {
            leftJoin = "LEFT JOIN driveAclIndex cir ON (driveMainIndex.identityId = cir.identityId AND driveMainIndex.driveId = cir.driveId AND driveMainIndex.fileId = cir.fileId)"
            listWhere.add("( (cir.fileId IS NULL) OR cir.aclMemberId IN (${hexList(aclAnyOf)}) )")
        }

        if (!filetypesAnyOf.isNullOrEmpty()) {
            listWhere.add("filetype IN (${intList(filetypesAnyOf)})")
        }

        if (!datatypesAnyOf.isNullOrEmpty()) {
            listWhere.add("datatype IN (${intList(datatypesAnyOf)})")
        }

        if (!globalTransitIdAnyOf.isNullOrEmpty()) {
            listWhere.add("globaltransitid IN (${hexList(globalTransitIdAnyOf)})")
        }

        if (!uniqueIdAnyOf.isNullOrEmpty()) {
            listWhere.add("uniqueid IN (${hexList(uniqueIdAnyOf)})")
        }

        if (!tagsAnyOf.isNullOrEmpty()) {
            listWhere.add("driveMainIndex.fileId IN (SELECT DISTINCT fileId FROM driveTagIndex WHERE driveTagIndex.identityId=driveMainIndex.identityId AND tagId IN (${hexList(tagsAnyOf)}))")
        }

        if (!localTagsAnyOf.isNullOrEmpty()) {
            listWhere.add("driveMainIndex.fileId IN (SELECT DISTINCT fileId FROM driveLocalTagIndex WHERE driveLocalTagIndex.identityId=driveMainIndex.identityId AND TagId IN (${hexList(localTagsAnyOf)}))")
        }

        if (!archivalStatusAnyOf.isNullOrEmpty()) {
            listWhere.add("archivalStatus IN (${intList(archivalStatusAnyOf)})")
        }

        if (!senderIdAnyOf.isNullOrEmpty()) {
            listWhere.add("senderId IN (${unsafeStringList(senderIdAnyOf)})")
        }

        if (!groupIdAnyOf.isNullOrEmpty()) {
            listWhere.add("groupId IN (${hexList(groupIdAnyOf)})")
        }

        userDateSpan?.let {
            it.validate()
            listWhere.add("(userDate >= ${it.start.milliseconds} AND userDate <= ${it.end.milliseconds})")
        }

        if (!tagsAllOf.isNullOrEmpty()) {
            // TODO: This will return 0 matches. Figure out the right query.
            listWhere.add(andIntersectHexList(tagsAllOf, "driveTagIndex"))
        }

        if (!localTagsAllOf.isNullOrEmpty()) {
            // TODO: This will return 0 matches. Figure out the right query.
            listWhere.add(andIntersectHexList(localTagsAllOf, "driveLocalTagIndex"))
        }

        return leftJoin
    }

    /**
     * Asynchronously retrieves a batch of records from the drive main index
     */
    suspend fun queryBatchAsync(
        dbm : DatabaseManager,
        driveId: Uuid,
        noOfItems: Int,
        cursor: QueryBatchCursor? = null,
        sortOrder: QueryBatchSortOrder = QueryBatchSortOrder.NewestFirst,
        sortField: QueryBatchSortField = QueryBatchSortField.CreatedDate,
        fileSystemType: Int? = null, // Default would be FileSystemType.Standard
        fileStateAnyOf: List<Int>? = null,
        globalTransitIdAnyOf: List<Uuid>? = null,
        filetypesAnyOf: List<Int>? = null,
        datatypesAnyOf: List<Int>? = null,
        senderIdAnyOf: List<String>? = null,
        groupIdAnyOf: List<Uuid>? = null,
        uniqueIdAnyOf: List<Uuid>? = null,
        archivalStatusAnyOf: List<Int>? = null,
        userDateSpan: UnixTimeUtcRange? = null,
        aclAnyOf: List<Uuid>? = null,
        tagsAnyOf: List<Uuid>? = null,
        tagsAllOf: List<Uuid>? = null,
        localTagsAnyOf: List<Uuid>? = null,
        localTagsAllOf: List<Uuid>? = null
    ): QueryBatchResult {
        
        if (fileSystemType == null) {
            throw IllegalArgumentException("fileSystemType required in Query Batch")
        }

        if (noOfItems < 1) {
            throw IllegalArgumentException("Must QueryBatch() no less than one item.")
        }

        val actualNoOfItems = if (noOfItems == Int.MAX_VALUE) noOfItems - 1 else noOfItems

        var workingCursor = cursor?.clone() ?: QueryBatchCursor()

        // Set order for appropriate direction
        val (sign, isign, direction) = when (sortOrder) {
            QueryBatchSortOrder.NewestFirst, QueryBatchSortOrder.Default -> 
                Triple('<', '>', "DESC")
            QueryBatchSortOrder.OldestFirst -> 
                Triple('>', '<', "ASC")
        }

        val timeField: String
        val listWhereAnd = mutableListOf<String>()
        
        
        
        timeField = when (sortField) {
            QueryBatchSortField.CreatedDate, QueryBatchSortField.FileId -> "created"
            QueryBatchSortField.UserDate -> "userDate"
            QueryBatchSortField.AnyChangeDate -> {
                // Important: See C# comments about same millisecond restriction
                listWhereAnd.add("modified < ${UnixTimeUtc.now().milliseconds}")
                "modified"
            }
            QueryBatchSortField.OnlyModifiedDate -> {
                listWhereAnd.add("modified < ${UnixTimeUtc.now().milliseconds}")
                listWhereAnd.add("modified != created")
                "modified"
            }
        }

        // Handle paging cursor
        workingCursor.paging?.let { pagingCursor ->
            val rowId = pagingCursor.row ?: if (sortOrder == QueryBatchSortOrder.NewestFirst) Long.MAX_VALUE else 0L
            listWhereAnd.add("($timeField, driveMainIndex.rowId) $sign (${pagingCursor.time.milliseconds}, $rowId)")
        }

        // Handle stop boundary
        workingCursor.stop?.let { stopBoundary ->
            val rowId = stopBoundary.row ?: if (sortOrder == QueryBatchSortOrder.NewestFirst) Long.MAX_VALUE else 0L
            listWhereAnd.add("($timeField, driveMainIndex.rowId) $isign (${stopBoundary.time.milliseconds}, $rowId)")
        }

        if (!fileStateAnyOf.isNullOrEmpty()) {
            listWhereAnd.add("fileState IN (${intList(fileStateAnyOf)})")
        }

        val leftJoin = buildSharedWhereAnd(
            listWhereAnd, aclAnyOf, filetypesAnyOf, datatypesAnyOf,
            globalTransitIdAnyOf, uniqueIdAnyOf, tagsAnyOf, localTagsAnyOf, archivalStatusAnyOf,
            senderIdAnyOf, groupIdAnyOf, userDateSpan, tagsAllOf, localTagsAllOf,
            fileSystemType, driveId
        )

        val orderString = "$timeField $direction, driveMainIndex.rowId $direction"

        // Read +1 more than requested to see if we're at the end of the dataset
        val sqlStatement = "SELECT DISTINCT $SELECT_OUTPUT_FIELDS FROM driveMainIndex $leftJoin WHERE ${
            listWhereAnd.joinToString(" AND ")
        } ORDER BY $orderString LIMIT ${actualNoOfItems + 1}"

        // Execute custom SQL using SQLDelight driver
        val result = dbm.executeReadQuery(
            identifier = null,
            sql = sqlStatement,
            mapper = { sqlCursor ->
                val records = mutableListOf<HomebaseFile>()
                var count = 0
                lateinit var header: HomebaseFile
                var rowId: Long? = -1

                while (sqlCursor.next().value && count < actualNoOfItems) {
                    rowId = sqlCursor.getLong(0)
                    val jsonHeader = sqlCursor.getString(1) ?: ""
                    header = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
                    records.add(header)
                    count++
                }
                val hasMoreRows = sqlCursor.next().value // Check if there's at least one more record

                if (count > 0)
                {
                    if (sortField === QueryBatchSortField.UserDate)
                        workingCursor = workingCursor.copy(paging = TimeRowCursor(UnixTimeUtc(header.fileMetadata.appData.userDate!!), 0L))
                    else if (sortField === QueryBatchSortField.AnyChangeDate || sortField === QueryBatchSortField.OnlyModifiedDate)
                        workingCursor = workingCursor.copy(paging = TimeRowCursor(header.fileMetadata.updated, 0L))
                    else if (sortField === QueryBatchSortField.FileId || sortField === QueryBatchSortField.CreatedDate)
                        workingCursor = workingCursor.copy(paging = TimeRowCursor(header.fileMetadata.created, 0L))
                    else
                        throw IllegalArgumentException("Invalid QueryBatchSortField type")
                }
                QueryResult.Value(QueryBatchResult(records, hasMoreRows, workingCursor))
            },
            parameters = 0
        ) { }

        return result.value;
    }

    /**
     * Smart cursor variant with automatic boundary management for NewestFirst queries
     */
    suspend fun queryBatchSmartCursorAsync(
        dbm : DatabaseManager,
        driveId: Uuid,
        noOfItems: Int,
        cursor: QueryBatchCursor? = null,
        sortOrder: QueryBatchSortOrder = QueryBatchSortOrder.NewestFirst,
        sortField: QueryBatchSortField = QueryBatchSortField.CreatedDate,
        fileSystemType: Int? = null,
        fileStateAnyOf: List<Int>? = null,
        requiredSecurityGroup: IntRange? = null,
        globalTransitIdAnyOf: List<Uuid>? = null,
        filetypesAnyOf: List<Int>? = null,
        datatypesAnyOf: List<Int>? = null,
        senderIdAnyOf: List<String>? = null,
        groupIdAnyOf: List<Uuid>? = null,
        uniqueIdAnyOf: List<Uuid>? = null,
        archivalStatusAnyOf: List<Int>? = null,
        userDateSpan: UnixTimeUtcRange? = null,
        aclAnyOf: List<Uuid>? = null,
        tagsAnyOf: List<Uuid>? = null,
        tagsAllOf: List<Uuid>? = null,
        localTagsAnyOf: List<Uuid>? = null,
        localTagsAllOf: List<Uuid>? = null
    ): QueryBatchResult {
        
        val pagingCursorWasNull = cursor?.paging == null

        val (result, moreRows, refCursor) = queryBatchAsync(dbm,
            driveId, noOfItems, cursor, sortOrder, sortField,
            fileSystemType, fileStateAnyOf,
            globalTransitIdAnyOf, filetypesAnyOf, datatypesAnyOf,
            senderIdAnyOf, groupIdAnyOf, uniqueIdAnyOf, archivalStatusAnyOf,
            userDateSpan, aclAnyOf, tagsAnyOf, tagsAllOf,
            localTagsAnyOf, localTagsAllOf
        )

        if (sortOrder == QueryBatchSortOrder.OldestFirst) {
            return QueryBatchResult(result, moreRows, refCursor)
        }

        if (result.isNotEmpty()) {
            // Set nextBoundaryCursor for first set when pagingCursor was null
            if (pagingCursorWasNull) {
                val nextBoundaryCursor = when (sortField) {
                    QueryBatchSortField.CreatedDate, QueryBatchSortField.FileId ->
                        TimeRowCursor(UnixTimeUtc(result[0].fileMetadata.created.milliseconds), 0L)
                    QueryBatchSortField.AnyChangeDate ->
                        TimeRowCursor(UnixTimeUtc(result[0].fileMetadata.updated.milliseconds), 0L)
                    QueryBatchSortField.UserDate ->
                        TimeRowCursor(UnixTimeUtc(result[0].fileMetadata.appData.userDate ?: 0L), 0L)
                    QueryBatchSortField.OnlyModifiedDate ->
                        TimeRowCursor(UnixTimeUtc(result[0].fileMetadata.updated.milliseconds), 0L)
                }
                return QueryBatchResult(result, moreRows, refCursor.copy(next = nextBoundaryCursor))
            }

            if (result.size < noOfItems) {
                if (!moreRows) {
                    // Advance cursor boundary
                    val updatedCursor = if (refCursor.next != null) {
                        refCursor.copy(
                            stop = refCursor.next,
                            next = null,
                            paging = null
                        )
                    } else {
                        refCursor.copy(next = null, paging = null)
                    }

                    // Recursive call to check for more items
                    val (r2, moreRows2, refCursor2) = queryBatchSmartCursorAsync(dbm,
                        driveId, noOfItems - result.size, updatedCursor,
                        sortOrder, sortField, fileSystemType, fileStateAnyOf,
                        requiredSecurityGroup, globalTransitIdAnyOf, filetypesAnyOf,
                        datatypesAnyOf, senderIdAnyOf, groupIdAnyOf, uniqueIdAnyOf,
                        archivalStatusAnyOf, userDateSpan, aclAnyOf, tagsAnyOf,
                        tagsAllOf, localTagsAnyOf, localTagsAllOf
                    )

                    if (r2.isNotEmpty()) {
                        // Combine results - r2 should be newer than result
                        val combinedResult = (r2 + result)
                        return QueryBatchResult(combinedResult, moreRows2, refCursor2)
                    }
                }
            }
        } else {
            if (refCursor.next != null) {
                val updatedCursor = refCursor.copy(
                    stop = refCursor.next,
                    next = null,
                    paging = null
                )
                return queryBatchSmartCursorAsync(dbm,
                    driveId, noOfItems, updatedCursor, sortOrder, sortField,
                    fileSystemType, fileStateAnyOf, requiredSecurityGroup,
                    globalTransitIdAnyOf, filetypesAnyOf, datatypesAnyOf,
                    senderIdAnyOf, groupIdAnyOf, uniqueIdAnyOf, archivalStatusAnyOf,
                    userDateSpan, aclAnyOf, tagsAnyOf, tagsAllOf, localTagsAnyOf, localTagsAllOf
                )
            } else {
                return QueryBatchResult(result, moreRows, refCursor.copy(next = null, paging = null))
            }
        }

        return QueryBatchResult(result, moreRows, refCursor)
    }

    /**
     * Legacy query for modified items - should be removed eventually
     */
    suspend fun queryModifiedAsync(
        dbm : DatabaseManager,
        driveId: Uuid,
        noOfItems: Int,
        cursorString: String? = null,
        stopAtModifiedUnixTimeSeconds: TimeRowCursor? = null,
        fileSystemType: Int? = null,
        requiredSecurityGroup: IntRange? = null,
        globalTransitIdAnyOf: List<Uuid>? = null,
        filetypesAnyOf: List<Int>? = null,
        datatypesAnyOf: List<Int>? = null,
        senderIdAnyOf: List<String>? = null,
        groupIdAnyOf: List<Uuid>? = null,
        uniqueIdAnyOf: List<Uuid>? = null,
        archivalStatusAnyOf: List<Int>? = null,
        userDateSpan: UnixTimeUtcRange? = null,
        aclAnyOf: List<Uuid>? = null,
        tagsAnyOf: List<Uuid>? = null,
        tagsAllOf: List<Uuid>? = null,
        localTagsAnyOf: List<Uuid>? = null,
        localTagsAllOf: List<Uuid>? = null
    ): QueryModifiedResult {

        val cursor = if (cursorString != null) {
            TimeRowCursor.fromJson(cursorString)
        } else {
            TimeRowCursor(UnixTimeUtc(0), 0L)
        }

        val queryCursor = QueryBatchCursor(
            paging = cursor.copy(row = cursor.row ?: 0L),
            stop = stopAtModifiedUnixTimeSeconds
        )

        val (records, hasMoreRows, updatedCursor) = queryBatchAsync(dbm,
            driveId, noOfItems, queryCursor, QueryBatchSortOrder.OldestFirst,
            QueryBatchSortField.OnlyModifiedDate, fileSystemType, null,
            globalTransitIdAnyOf, filetypesAnyOf,
            datatypesAnyOf, senderIdAnyOf, groupIdAnyOf, uniqueIdAnyOf,
            archivalStatusAnyOf, userDateSpan, aclAnyOf, tagsAnyOf,
            tagsAllOf, localTagsAnyOf, localTagsAllOf
        )

        return QueryModifiedResult(
            records = records,
            hasMoreRows = hasMoreRows,
            cursor = updatedCursor.paging?.toJson() ?: ""
        )
    }

    // Helper functions

    private fun intList(list: List<Int>): String {
        return list.joinToString(",")
    }

    private fun hexList(list: List<Uuid>): String {
        return list.joinToString(",") { it.toSqlString() }
    }

    private fun unsafeStringList(list: List<String>): String {
        // WARNING! This does not escape strings. Caller must ensure safety.
        return list.joinToString(",") { "'$it'" }
    }

    private fun isSet(list: List<*>?): Boolean {
        return !list.isNullOrEmpty()
    }

    private fun andIntersectHexList(list: List<Uuid>, tableName: String): String {
        if (list.size < 1) {
            throw IllegalArgumentException("AllOf list must have at least two entries")
        }

        var sql = "driveMainIndex.fileId IN (SELECT DISTINCT fileId FROM $tableName WHERE tagId = ${list[0].toSqlString()} "

        for (i in 1 until list.size) {
            sql += "INTERSECT SELECT DISTINCT fileId FROM $tableName WHERE tagId = ${list[i].toSqlString()} "
        }

        sql += ") "
        return sql
    }

    // Extension functions for database-specific formatting
    private fun Uuid.toSqlString(): String {
        // Remove hyphens from UUID string for SQLite hex literal format
        return "x'${this.toString().replace("-", "")}'" // Hex format for SQL
    }
}

// Supporting data classes

data class QueryBatchResult(
    val records: List<HomebaseFile>,
    val hasMoreRows: Boolean,
    val cursor: QueryBatchCursor
)

data class QueryModifiedResult(
    val records: List<HomebaseFile>,
    val hasMoreRows: Boolean,
    val cursor: String
)

