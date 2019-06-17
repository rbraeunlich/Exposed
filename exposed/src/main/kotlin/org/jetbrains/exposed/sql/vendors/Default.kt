package org.jetbrains.exposed.sql.vendors

import org.jetbrains.exposed.exceptions.throwUnsupportedException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.util.*

open class DataTypeProvider {
    open fun shortAutoincType() = "INT AUTO_INCREMENT"

    open fun shortType() = "INT"

    open fun longAutoincType() = "BIGINT AUTO_INCREMENT"

    open fun longType() = "BIGINT"

    open fun floatType() = "FLOAT"

    open fun doubleType() = "DOUBLE PRECISION"

    open fun uuidType() = "BINARY(16)"

    open fun dateTimeType() = "DATETIME"

    open fun blobType(): String = "BLOB"

    open fun binaryType(length: Int): String = "VARBINARY($length)"

    open fun booleanType(): String = "BOOLEAN"

    open fun booleanToStatementString(bool: Boolean) = bool.toString()

    open fun uuidToDB(value: UUID) : Any =
            ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()

    open fun booleanFromStringToBoolean(value: String): Boolean = value.toBoolean()

    open fun textType() = "TEXT"
    open val blobAsStream = false

    open fun processForDefaultValue(e: Expression<*>) : String = when (e) {
        is LiteralOp<*> -> e.toSQL(QueryBuilder(false))
        else -> "(${e.toSQL(QueryBuilder(false))})"
    }
}

abstract class FunctionProvider {

    open val DEFAULT_VALUE_EXPRESSION = "DEFAULT VALUES"

    open fun<T:String?> substring(expr: Expression<T>, start: Expression<Int>, length: Expression<Int>, builder: QueryBuilder) : String =
            "SUBSTRING(${expr.toSQL(builder)}, ${start.toSQL(builder)}, ${length.toSQL(builder)})"

    open fun random(seed: Int?): String = "RANDOM(${seed?.toString().orEmpty()})"

    open fun cast(expr: Expression<*>, type: IColumnType, builder: QueryBuilder) = "CAST(${expr.toSQL(builder)} AS ${type.sqlType()})"

    open fun<T:String?> ExpressionWithColumnType<T>.match(pattern: String, mode: MatchMode? = null): Op<Boolean> = with(SqlExpressionBuilder) { this@match.like(pattern) }

    open fun insert(ignore: Boolean, table: Table, columns: List<Column<*>>, expr: String, transaction: Transaction): String {
        if (ignore) {
            transaction.throwUnsupportedException("There's no generic SQL for INSERT IGNORE. There must be vendor specific implementation")
        }

        val (columnsExpr, valuesExpr) = if (columns.isNotEmpty()) {
            columns.joinToString(prefix = "(", postfix = ")") { transaction.identity(it) } to expr
        } else "" to DEFAULT_VALUE_EXPRESSION

        return "INSERT INTO ${transaction.identity(table)} $columnsExpr $valuesExpr"
    }

    open fun update(targets: ColumnSet, columnsAndValues: List<Pair<Column<*>, Any?>>, limit: Int?, where: Op<Boolean>?, transaction: Transaction): String {
        return buildString {
            val builder = QueryBuilder(true)
            append("UPDATE ${targets.describe(transaction, builder)}")
            append(" SET ")
            append(columnsAndValues.joinToString { (col, value) ->
                "${transaction.identity(col)}=" + builder.registerArgument(col, value)
            })

            where?.let { append(" WHERE " + it.toSQL(builder)) }
            limit?.let { append(" LIMIT $it")}
        }
    }

    open fun delete(ignore: Boolean, table: Table, where: String?, limit: Int?, transaction: Transaction): String {
        if (ignore) {
            transaction.throwUnsupportedException("There's no generic SQL for DELETE IGNORE. There must be vendor specific implementation")
        }

        return buildString {
            append("DELETE FROM ")
            append(transaction.identity(table))
            if (where != null) {
                append(" WHERE ")
                append(where)
            }
            if (limit != null) {
                append(" LIMIT ")
                append(limit)
            }
        }
    }

    open fun replace(table: Table, data: List<Pair<Column<*>, Any?>>, transaction: Transaction): String
        = transaction.throwUnsupportedException("There's no generic SQL for replace. There must be vendor specific implementation")

    open fun queryLimit(size: Int, offset: Int, alreadyOrdered: Boolean) = "LIMIT $size" + if (offset > 0) " OFFSET $offset" else ""

    open fun <T : String?> groupConcat(expr: GroupConcat<T>, queryBuilder: QueryBuilder) = buildString {
        append("GROUP_CONCAT(")
        if (expr.distinct)
            append("DISTINCT ")
        append(expr.expr.toSQL(queryBuilder))
        if (expr.orderBy.isNotEmpty()) {
            expr.orderBy.joinTo(this, prefix = " ORDER BY ") {
                "${it.first.toSQL(queryBuilder)} ${it.second.name}"
            }
        }
        expr.separator?.let {
            append(" SEPARATOR '$it'")
        }
        append(")")
    }

    open fun <T:String?> regexp(expr1: Expression<T>, pattern: Expression<String>, caseSensitive: Boolean, queryBuilder: QueryBuilder) = buildString {
        append("REGEXP_LIKE(")
        append(expr1.toSQL(queryBuilder))
        append(", ")
        append(pattern.toSQL(queryBuilder))
        append(", ")
        if (caseSensitive)
            append("'c'")
        else
            append("'i'")
        append(")")
    }

    interface MatchMode {
        fun mode() : String
    }

    open fun <T:String?> concat(separator: String, queryBuilder: QueryBuilder, vararg expr: Expression<T>) = buildString {
        if (separator == "")
            append("CONCAT(")
        else {
            append("CONCAT_WS(")
            append("'")
            append(separator)
            append("',")
        }
        expr.joinTo(this) { it.toSQL(queryBuilder) }
        append(")")
    }
}

/**
 * type:
 * @see java.sql.Types
 */
data class ColumnMetadata(val name: String, val type: Int, val nullable: Boolean)

interface DatabaseDialect {
    val name: String
    val dataTypeProvider: DataTypeProvider
    val functionProvider: FunctionProvider

    fun getDatabase(): String

    fun allTablesNames(): List<String>
    /**
     * returns list of pairs (column name + nullable) for every table
     */
    fun tableColumns(vararg tables: Table): Map<Table, List<ColumnMetadata>> = emptyMap()

    /**
     * returns map of constraint for a table name/column name pair
     */
    fun columnConstraints(vararg tables: Table): Map<Pair<String, String>, List<ForeignKeyConstraint>> = emptyMap()

    /**
     * return set of indices for each table
     */
    fun existingIndices(vararg tables: Table): Map<Table, List<Index>> = emptyMap()

    fun tableExists(table: Table): Boolean

    fun checkTableMapping(table: Table) = true

    fun resetCaches()

    fun supportsSelectForUpdate(): Boolean
    val supportsMultipleGeneratedKeys: Boolean
    fun isAllowedAsColumnDefault(e: Expression<*>) = e is LiteralOp<*>

    val supportsIfNotExists: Boolean get() = true
    val needsSequenceToAutoInc: Boolean get() = false
    val needsQuotesWhenSymbolsInNames: Boolean get() = true
    fun catalog(transaction: Transaction): String = transaction.connection.catalog


    val defaultReferenceOption : ReferenceOption get() = ReferenceOption.RESTRICT

    val supportsOnlyIdentifiersInGeneratedKeys get() = false

    // Specific SQL statements

    fun createIndex(index: Index): String
    fun dropIndex(tableName: String, indexName: String): String
    fun modifyColumn(column: Column<*>) : String
}

abstract class VendorDialect(override val name: String,
                                      override val dataTypeProvider: DataTypeProvider,
                                      override val functionProvider: FunctionProvider) : DatabaseDialect {

    /* Cached values */
    private var _allTableNames: List<String>? = null
    val allTablesNames: List<String>
        get() {
            if (_allTableNames == null) {
                _allTableNames = allTablesNames()
            }
            return _allTableNames!!
        }

    val String.inProperCase: String get() = TransactionManager.current().db.identifierManager.inProperCase(this)

    /* Method always re-read data from DB. Using allTablesNames field is preferred way */
    override fun allTablesNames(): List<String> = TransactionManager.current().connection.metadata { tableNames }

    override fun getDatabase(): String = catalog(TransactionManager.current())

    override fun tableExists(table: Table) = allTablesNames.any { it == table.nameInDatabaseCase() }

    override fun tableColumns(vararg tables: Table): Map<Table, List<ColumnMetadata>> =
            TransactionManager.current().connection.metadata { tableColumns(*tables) }

    protected fun String.quoteIdentifierWhenWrongCaseOrNecessary(tr: Transaction) =
            tr.db.identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(this)

    override fun columnConstraints(vararg tables: Table): Map<Pair<String, String>, List<ForeignKeyConstraint>>
        = TransactionManager.current().db.metadata { columnConstraints(*tables) }

    override fun existingIndices(vararg tables: Table): Map<Table, List<Index>>
        = TransactionManager.current().db.metadata { existingIndices(*tables) }

    override fun resetCaches() {
        _allTableNames = null
        TransactionManager.current().db.metadata { cleanCache() }
    }

    override fun createIndex(index: Index): String {
        val t = TransactionManager.current()
        val quotedTableName = t.identity(index.table)
        val quotedIndexName = t.db.identifierManager.cutIfNecessaryAndQuote(index.indexName)
        val columnsList = index.columns.joinToString(prefix = "(", postfix = ")") { t.identity(it) }
        return if (index.unique) {
            "ALTER TABLE $quotedTableName ADD CONSTRAINT $quotedIndexName UNIQUE $columnsList"
        } else {
            "CREATE INDEX $quotedIndexName ON $quotedTableName $columnsList"
        }

    }

    override fun dropIndex(tableName: String, indexName: String): String {
        val identifierManager = TransactionManager.current().db.identifierManager
        return "ALTER TABLE ${identifierManager.quoteIfNecessary(tableName)} DROP CONSTRAINT ${identifierManager.quoteIfNecessary(indexName)}"
    }

    private val supportsSelectForUpdate by lazy { TransactionManager.current().db.metadata { supportsSelectForUpdate } }
    override fun supportsSelectForUpdate() = supportsSelectForUpdate

    override val supportsMultipleGeneratedKeys: Boolean = true

    override fun modifyColumn(column: Column<*>): String = "MODIFY COLUMN ${column.descriptionDdl()}"

}

internal val currentDialect: DatabaseDialect get() = TransactionManager.current().db.dialect

internal val currentDialectIfAvailable : DatabaseDialect? get() =
    if (TransactionManager.isInitialized() && TransactionManager.currentOrNull() != null) {
        currentDialect
    } else null

internal fun String.inProperCase(): String = (currentDialectIfAvailable as? VendorDialect)?.run {
    TransactionManager.current().db.identifierManager.inProperCase(this@inProperCase)
} ?: this
