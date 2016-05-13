/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command

import java.io.File
import java.net.URI
import java.util.Date

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.{AnalysisException, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{CatalogColumn, CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.catalog.CatalogTableType._
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical.{Command, LogicalPlan, UnaryNode}
import org.apache.spark.sql.catalyst.util.quoteIdentifier
import org.apache.spark.sql.execution.datasources.PartitioningUtils
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

case class CreateTableAsSelectLogicalPlan(
    tableDesc: CatalogTable, child: LogicalPlan, allowExisting: Boolean)
    extends UnaryNode
    with Command {

  override def output: Seq[Attribute] = Seq.empty[Attribute]

  override lazy val resolved: Boolean =
    tableDesc.identifier.database.isDefined && tableDesc.schema.nonEmpty &&
    tableDesc.storage.serde.isDefined && tableDesc.storage.inputFormat.isDefined &&
    tableDesc.storage.outputFormat.isDefined && childrenResolved
}

/**
 * A command to create a table with the same definition of the given existing table.
 *
 * The syntax of using this command in SQL is:
 * {{{
 *   CREATE TABLE [IF NOT EXISTS] [db_name.]table_name
 *   LIKE [other_db_name.]existing_table_name
 * }}}
 */
case class CreateTableLike(
    targetTable: TableIdentifier, sourceTable: TableIdentifier, ifNotExists: Boolean)
    extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    if (!catalog.tableExists(sourceTable)) {
      throw new AnalysisException(
          s"Source table in CREATE TABLE LIKE does not exist: '$sourceTable'")
    }
    if (catalog.isTemporaryTable(sourceTable)) {
      throw new AnalysisException(
          s"Source table in CREATE TABLE LIKE cannot be temporary: '$sourceTable'")
    }

    val tableToCreate = catalog
      .getTableMetadata(sourceTable)
      .copy(identifier = targetTable,
            tableType = CatalogTableType.MANAGED,
            createTime = System.currentTimeMillis,
            lastAccessTime = -1)
      .withNewStorage(locationUri = None)

    catalog.createTable(tableToCreate, ifNotExists)
    Seq.empty[Row]
  }
}

// TODO: move the rest of the table commands from ddl.scala to this file

/**
 * A command to create a table.
 *
 * Note: This is currently used only for creating Hive tables.
 * This is not intended for temporary tables.
 *
 * The syntax of using this command in SQL is:
 * {{{
 *   CREATE [EXTERNAL] TABLE [IF NOT EXISTS] [db_name.]table_name
 *   [(col1 data_type [COMMENT col_comment], ...)]
 *   [COMMENT table_comment]
 *   [PARTITIONED BY (col3 data_type [COMMENT col_comment], ...)]
 *   [CLUSTERED BY (col1, ...) [SORTED BY (col1 [ASC|DESC], ...)] INTO num_buckets BUCKETS]
 *   [SKEWED BY (col1, col2, ...) ON ((col_value, col_value, ...), ...)
 *   [STORED AS DIRECTORIES]
 *   [ROW FORMAT row_format]
 *   [STORED AS file_format | STORED BY storage_handler_class [WITH SERDEPROPERTIES (...)]]
 *   [LOCATION path]
 *   [TBLPROPERTIES (property_name=property_value, ...)]
 *   [AS select_statement];
 * }}}
 */
case class CreateTable(table: CatalogTable, ifNotExists: Boolean) extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    sparkSession.sessionState.catalog.createTable(table, ifNotExists)
    Seq.empty[Row]
  }
}

/**
 * A command that renames a table/view.
 *
 * The syntax of this command is:
 * {{{
 *    ALTER TABLE table1 RENAME TO table2;
 *    ALTER VIEW view1 RENAME TO view2;
 * }}}
 */
case class AlterTableRename(oldName: TableIdentifier, newName: TableIdentifier, isView: Boolean)
    extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    DDLUtils.verifyAlterTableType(catalog, oldName, isView)
    catalog.invalidateTable(oldName)
    catalog.renameTable(oldName, newName)
    Seq.empty[Row]
  }
}

/**
 * A command that loads data into a Hive table.
 *
 * The syntax of this command is:
 * {{{
 *  LOAD DATA [LOCAL] INPATH 'filepath' [OVERWRITE] INTO TABLE tablename
 *  [PARTITION (partcol1=val1, partcol2=val2 ...)]
 * }}}
 */
case class LoadData(table: TableIdentifier,
                    path: String,
                    isLocal: Boolean,
                    isOverwrite: Boolean,
                    partition: Option[TablePartitionSpec])
    extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    if (!catalog.tableExists(table)) {
      throw new AnalysisException(s"Target table in LOAD DATA does not exist: '$table'")
    }
    val targetTable = catalog.getTableMetadataOption(table).getOrElse {
      throw new AnalysisException(s"Target table in LOAD DATA cannot be temporary: '$table'")
    }
    if (DDLUtils.isDatasourceTable(targetTable)) {
      throw new AnalysisException(s"LOAD DATA is not supported for datasource tables: '$table'")
    }
    if (targetTable.partitionColumnNames.nonEmpty) {
      if (partition.isEmpty) {
        throw new AnalysisException(
            s"LOAD DATA target table '$table' is partitioned, " +
            s"but no partition spec is provided")
      }
      if (targetTable.partitionColumnNames.size != partition.get.size) {
        throw new AnalysisException(
            s"LOAD DATA target table '$table' is partitioned, " +
            s"but number of columns in provided partition spec (${partition.get.size}) " +
            s"do not match number of partitioned columns in table " +
            s"(s${targetTable.partitionColumnNames.size})")
      }
      partition.get.keys.foreach { colName =>
        if (!targetTable.partitionColumnNames.contains(colName)) {
          throw new AnalysisException(
              s"LOAD DATA target table '$table' is partitioned, " +
              s"but the specified partition spec refers to a column that is not partitioned: " +
              s"'$colName'")
        }
      }
    } else {
      if (partition.nonEmpty) {
        throw new AnalysisException(
            s"LOAD DATA target table '$table' is not partitioned, " +
            s"but a partition spec was provided.")
      }
    }

    val loadPath =
      if (isLocal) {
        val uri = Utils.resolveURI(path)
        if (!new File(uri.getPath()).exists()) {
          throw new AnalysisException(s"LOAD DATA input path does not exist: $path")
        }
        uri
      } else {
        val uri = new URI(path)
        if (uri.getScheme() != null && uri.getAuthority() != null) {
          uri
        } else {
          // Follow Hive's behavior:
          // If no schema or authority is provided with non-local inpath,
          // we will use hadoop configuration "fs.default.name".
          val defaultFSConf = sparkSession.sessionState.newHadoopConf().get("fs.default.name")
          val defaultFS =
            if (defaultFSConf == null) {
              new URI("")
            } else {
              new URI(defaultFSConf)
            }

          val scheme =
            if (uri.getScheme() != null) {
              uri.getScheme()
            } else {
              defaultFS.getScheme()
            }
          val authority =
            if (uri.getAuthority() != null) {
              uri.getAuthority()
            } else {
              defaultFS.getAuthority()
            }

          if (scheme == null) {
            throw new AnalysisException(
                s"LOAD DATA: URI scheme is required for non-local input paths: '$path'")
          }

          // Follow Hive's behavior:
          // If LOCAL is not specified, and the path is relative,
          // then the path is interpreted relative to "/user/<username>"
          val uriPath = uri.getPath()
          val absolutePath =
            if (uriPath != null && uriPath.startsWith("/")) {
              uriPath
            } else {
              s"/user/${System.getProperty("user.name")}/$uriPath"
            }
          new URI(scheme, authority, absolutePath, uri.getQuery(), uri.getFragment())
        }
      }

    if (partition.nonEmpty) {
      catalog.loadPartition(targetTable.identifier,
                            loadPath.toString,
                            partition.get,
                            isOverwrite,
                            holdDDLTime = false,
                            inheritTableSpecs = true,
                            isSkewedStoreAsSubdir = false)
    } else {
      catalog.loadTable(targetTable.identifier,
                        loadPath.toString,
                        isOverwrite,
                        holdDDLTime = false)
    }
    Seq.empty[Row]
  }
}

/**
 * Command that looks like
 * {{{
 *   DESCRIBE [EXTENDED|FORMATTED] table_name;
 * }}}
 */
case class DescribeTableCommand(table: TableIdentifier, isExtended: Boolean, isFormatted: Boolean)
    extends RunnableCommand {

  override val output: Seq[Attribute] = Seq(
      // Column names are based on Hive.
      AttributeReference(
          "col_name",
          StringType,
          nullable = false,
          new MetadataBuilder().putString("comment", "name of the column").build())(),
      AttributeReference(
          "data_type",
          StringType,
          nullable = false,
          new MetadataBuilder().putString("comment", "data type of the column").build())(),
      AttributeReference(
          "comment",
          StringType,
          nullable = true,
          new MetadataBuilder().putString("comment", "comment of the column").build())()
  )

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val result = new ArrayBuffer[Row]
    val catalog = sparkSession.sessionState.catalog

    if (catalog.isTemporaryTable(table)) {
      describeSchema(catalog.lookupRelation(table).schema, result)
    } else {
      val metadata = catalog.getTableMetadata(table)

      if (isExtended) {
        describeExtended(metadata, result)
      } else if (isFormatted) {
        describeFormatted(metadata, result)
      } else {
        describe(metadata, result)
      }
    }

    result
  }

  // Shows data columns and partitioned columns (if any)
  private def describe(table: CatalogTable, buffer: ArrayBuffer[Row]): Unit = {
    if (DDLUtils.isDatasourceTable(table)) {
      val schema = DDLUtils.getSchemaFromTableProperties(table)

      if (schema.isEmpty) {
        append(buffer, "# Schema of this table is inferred at runtime", "", "")
      } else {
        schema.foreach(describeSchema(_, buffer))
      }

      val partCols = DDLUtils.getPartitionColumnsFromTableProperties(table)
      if (partCols.nonEmpty) {
        append(buffer, "# Partition Information", "", "")
        append(buffer, s"# ${output.head.name}", "", "")
        partCols.foreach(col => append(buffer, col, "", ""))
      }
    } else {
      describeSchema(table.schema, buffer)

      if (table.partitionColumns.nonEmpty) {
        append(buffer, "# Partition Information", "", "")
        append(buffer, s"# ${output.head.name}", output(1).name, output(2).name)
        describeSchema(table.partitionColumns, buffer)
      }
    }
  }

  private def describeExtended(table: CatalogTable, buffer: ArrayBuffer[Row]): Unit = {
    describe(table, buffer)

    append(buffer, "", "", "")
    append(buffer, "# Detailed Table Information", table.toString, "")
  }

  private def describeFormatted(table: CatalogTable, buffer: ArrayBuffer[Row]): Unit = {
    describe(table, buffer)

    append(buffer, "", "", "")
    append(buffer, "# Detailed Table Information", "", "")
    append(buffer, "Database:", table.database, "")
    append(buffer, "Owner:", table.owner, "")
    append(buffer, "Create Time:", new Date(table.createTime).toString, "")
    append(buffer, "Last Access Time:", new Date(table.lastAccessTime).toString, "")
    append(buffer, "Location:", table.storage.locationUri.getOrElse(""), "")
    append(buffer, "Table Type:", table.tableType.name, "")

    append(buffer, "Table Parameters:", "", "")
    table.properties.filterNot {
      // Hides schema properties that hold user-defined schema, partition columns, and bucketing
      // information since they are already extracted and shown in other parts.
      case (key, _) => key.startsWith("spark.sql.sources.schema")
    }.foreach {
      case (key, value) =>
        append(buffer, s"  $key", value, "")
    }

    describeStorageInfo(table, buffer)
  }

  private def describeStorageInfo(metadata: CatalogTable, buffer: ArrayBuffer[Row]): Unit = {
    append(buffer, "", "", "")
    append(buffer, "# Storage Information", "", "")
    metadata.storage.serde.foreach(serdeLib => append(buffer, "SerDe Library:", serdeLib, ""))
    metadata.storage.inputFormat.foreach(format => append(buffer, "InputFormat:", format, ""))
    metadata.storage.outputFormat.foreach(format => append(buffer, "OutputFormat:", format, ""))
    append(buffer, "Compressed:", if (metadata.storage.compressed) "Yes" else "No", "")
    describeBucketingInfo(metadata, buffer)

    append(buffer, "Storage Desc Parameters:", "", "")
    metadata.storage.serdeProperties.foreach {
      case (key, value) =>
        append(buffer, s"  $key", value, "")
    }
  }

  private def describeBucketingInfo(metadata: CatalogTable, buffer: ArrayBuffer[Row]): Unit = {
    def appendBucketInfo(numBuckets: Int, bucketColumns: Seq[String], sortColumns: Seq[String]) = {
      append(buffer, "Num Buckets:", numBuckets.toString, "")
      append(buffer, "Bucket Columns:", bucketColumns.mkString("[", ", ", "]"), "")
      append(buffer, "Sort Columns:", sortColumns.mkString("[", ", ", "]"), "")
    }

    DDLUtils
      .getBucketSpecFromTableProperties(metadata)
      .map { bucketSpec =>
        appendBucketInfo(bucketSpec.numBuckets,
                         bucketSpec.bucketColumnNames,
                         bucketSpec.sortColumnNames)
      }
      .getOrElse {
        appendBucketInfo(metadata.numBuckets, metadata.bucketColumnNames, metadata.sortColumnNames)
      }
  }

  private def describeSchema(schema: Seq[CatalogColumn], buffer: ArrayBuffer[Row]): Unit = {
    schema.foreach { column =>
      append(buffer, column.name, column.dataType.toLowerCase, column.comment.orNull)
    }
  }

  private def describeSchema(schema: StructType, buffer: ArrayBuffer[Row]): Unit = {
    schema.foreach { column =>
      val comment =
        if (column.metadata.contains("comment")) column.metadata.getString("comment") else ""
      append(buffer, column.name, column.dataType.simpleString, comment)
    }
  }

  private def append(
      buffer: ArrayBuffer[Row], column: String, dataType: String, comment: String): Unit = {
    buffer += Row(column, dataType, comment)
  }
}

/**
 * A command for users to get tables in the given database.
 * If a databaseName is not given, the current database will be used.
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW TABLES [(IN|FROM) database_name] [[LIKE] 'identifier_with_wildcards'];
 * }}}
 */
case class ShowTablesCommand(databaseName: Option[String], tableIdentifierPattern: Option[String])
    extends RunnableCommand {

  // The result of SHOW TABLES has two columns, tableName and isTemporary.
  override val output: Seq[Attribute] = {
    AttributeReference("tableName", StringType, nullable = false)() ::
    AttributeReference("isTemporary", BooleanType, nullable = false)() :: Nil
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    // Since we need to return a Seq of rows, we will call getTables directly
    // instead of calling tables in sparkSession.
    val catalog = sparkSession.sessionState.catalog
    val db = databaseName.getOrElse(catalog.getCurrentDatabase)
    val tables =
      tableIdentifierPattern.map(catalog.listTables(db, _)).getOrElse(catalog.listTables(db))
    tables.map { t =>
      val isTemp = t.database.isEmpty
      Row(t.table, isTemp)
    }
  }
}

/**
 * A command for users to list the properties for a table If propertyKey is specified, the value
 * for the propertyKey is returned. If propertyKey is not specified, all the keys and their
 * corresponding values are returned.
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW TBLPROPERTIES table_name[('propertyKey')];
 * }}}
 */
case class ShowTablePropertiesCommand(table: TableIdentifier, propertyKey: Option[String])
    extends RunnableCommand {

  override val output: Seq[Attribute] = {
    val schema = AttributeReference("value", StringType, nullable = false)() :: Nil
    propertyKey match {
      case None => AttributeReference("key", StringType, nullable = false)() :: schema
      case _ => schema
    }
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog

    if (catalog.isTemporaryTable(table)) {
      Seq.empty[Row]
    } else {
      val catalogTable = sparkSession.sessionState.catalog.getTableMetadata(table)

      propertyKey match {
        case Some(p) =>
          val propValue = catalogTable.properties.getOrElse(
              p, s"Table ${catalogTable.qualifiedName} does not have property: $p")
          Seq(Row(propValue))
        case None =>
          catalogTable.properties.map(p => Row(p._1, p._2)).toSeq
      }
    }
  }
}

/**
 * A command to list the column names for a table. This function creates a
 * [[ShowColumnsCommand]] logical plan.
 *
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW COLUMNS (FROM | IN) table_identifier [(FROM | IN) database];
 * }}}
 */
case class ShowColumnsCommand(table: TableIdentifier) extends RunnableCommand {
  // The result of SHOW COLUMNS has one column called 'result'
  override val output: Seq[Attribute] = {
    AttributeReference("result", StringType, nullable = false)() :: Nil
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    sparkSession.sessionState.catalog.getTableMetadata(table).schema.map { c =>
      Row(c.name)
    }
  }
}

/**
 * A command to list the partition names of a table. If the partition spec is specified,
 * partitions that match the spec are returned. [[AnalysisException]] exception is thrown under
 * the following conditions:
 *
 * 1. If the command is called for a non partitioned table.
 * 2. If the partition spec refers to the columns that are not defined as partitioning columns.
 *
 * This function creates a [[ShowPartitionsCommand]] logical plan
 *
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW PARTITIONS [db_name.]table_name [PARTITION(partition_spec)]
 * }}}
 */
case class ShowPartitionsCommand(table: TableIdentifier, spec: Option[TablePartitionSpec])
    extends RunnableCommand {
  // The result of SHOW PARTITIONS has one column called 'result'
  override val output: Seq[Attribute] = {
    AttributeReference("result", StringType, nullable = false)() :: Nil
  }

  private def getPartName(spec: TablePartitionSpec, partColNames: Seq[String]): String = {
    partColNames.map { name =>
      PartitioningUtils.escapePathName(name) + "=" + PartitioningUtils.escapePathName(spec(name))
    }.mkString(File.separator)
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog

    if (catalog.isTemporaryTable(table)) {
      throw new AnalysisException(
          s"SHOW PARTITIONS is not allowed on a temporary table: ${table.unquotedString}")
    }

    val tab = catalog.getTableMetadata(table)

    /**
     * Validate and throws an [[AnalysisException]] exception under the following conditions:
     * 1. If the table is not partitioned.
     * 2. If it is a datasource table.
     * 3. If it is a view or index table.
     */
    if (tab.tableType == VIEW || tab.tableType == INDEX) {
      throw new AnalysisException(
          s"SHOW PARTITIONS is not allowed on a view or index table: ${tab.qualifiedName}")
    }

    if (!DDLUtils.isTablePartitioned(tab)) {
      throw new AnalysisException(
          s"SHOW PARTITIONS is not allowed on a table that is not partitioned: ${tab.qualifiedName}")
    }

    if (DDLUtils.isDatasourceTable(tab)) {
      throw new AnalysisException(
          s"SHOW PARTITIONS is not allowed on a datasource table: ${tab.qualifiedName}")
    }

    /**
     * Validate the partitioning spec by making sure all the referenced columns are
     * defined as partitioning columns in table definition. An AnalysisException exception is
     * thrown if the partitioning spec is invalid.
     */
    if (spec.isDefined) {
      val badColumns = spec.get.keySet.filterNot(tab.partitionColumns.map(_.name).contains)
      if (badColumns.nonEmpty) {
        val badCols = badColumns.mkString("[", ", ", "]")
        throw new AnalysisException(
            s"Non-partitioning column(s) $badCols are specified for SHOW PARTITIONS")
      }
    }

    val partNames = catalog.listPartitions(table, spec).map { p =>
      getPartName(p.spec, tab.partitionColumnNames)
    }

    partNames.map(Row(_))
  }
}

case class ShowCreateTableCommand(table: TableIdentifier) extends RunnableCommand {
  override val output: Seq[Attribute] = Seq(
      AttributeReference("createtab_stmt", StringType, nullable = false)()
  )

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog

    if (catalog.isTemporaryTable(table)) {
      throw new AnalysisException(s"SHOW CREATE TABLE cannot be applied to temporary table")
    }

    if (!catalog.tableExists(table)) {
      throw new AnalysisException(s"Table $table doesn't exist")
    }

    val tableMetadata = catalog.getTableMetadata(table)

    val stmt =
      if (DDLUtils.isDatasourceTable(tableMetadata)) {
        showCreateDataSourceTable(tableMetadata)
      } else {
        throw new UnsupportedOperationException(
            "SHOW CREATE TABLE only supports Spark SQL data source tables.")
      }

    Seq(Row(stmt))
  }

  private def showCreateDataSourceTable(metadata: CatalogTable): String = {
    val builder = StringBuilder.newBuilder

    builder ++= s"CREATE TABLE ${table.quotedString} "
    showDataSourceTableDataCols(metadata, builder)
    showDataSourceTableOptions(metadata, builder)
    showDataSourceTableNonDataColumns(metadata, builder)

    builder.toString()
  }

  private def showDataSourceTableDataCols(metadata: CatalogTable, builder: StringBuilder): Unit = {
    val props = metadata.properties
    val schemaParts = for {
      numParts <- props.get("spark.sql.sources.schema.numParts").toSeq
      index <- 0 until numParts.toInt
    } yield
      props.getOrElse(
          s"spark.sql.sources.schema.part.$index",
          throw new AnalysisException(
              s"Corrupted schema in catalog: $numParts parts expected, but part $index is missing."
          )
      )

    if (schemaParts.nonEmpty) {
      val fields = DataType.fromJson(schemaParts.mkString).asInstanceOf[StructType].fields
      val colTypeList = fields.map(f => s"${quoteIdentifier(f.name)} ${f.dataType.sql}")
      builder ++= colTypeList.mkString("(", ", ", ")")
    }

    builder ++= "\n"
  }

  private def showDataSourceTableOptions(metadata: CatalogTable, builder: StringBuilder): Unit = {
    val props = metadata.properties

    builder ++= s"USING ${props("spark.sql.sources.provider")}\n"

    val dataSourceOptions = metadata.storage.serdeProperties.filterNot {
      case (key, value) =>
        // If it's a managed table, omit PATH option. Spark SQL always creates external table
        // when the table creation DDL contains the PATH option.
        key.toLowerCase == "path" && metadata.tableType == MANAGED
    }.map {
      case (key, value) => s"${quoteIdentifier(key)} '${escapeSingleQuotedString(value)}'"
    }

    if (dataSourceOptions.nonEmpty) {
      builder ++= "OPTIONS (\n"
      builder ++= dataSourceOptions.mkString("  ", ",\n  ", "\n")
      builder ++= ")\n"
    }
  }

  private def showDataSourceTableNonDataColumns(
      metadata: CatalogTable, builder: StringBuilder): Unit = {
    val props = metadata.properties

    def getColumnNamesByType(colType: String, typeName: String): Seq[String] = {
      (for {
         numCols <- props.get(s"spark.sql.sources.schema.num${colType.capitalize}Cols").toSeq
         index <- 0 until numCols.toInt
       } yield
         props.getOrElse(
             s"spark.sql.sources.schema.${colType}Col.$index",
             throw new AnalysisException(
                 s"Corrupted $typeName in catalog: $numCols parts expected, but part $index is missing."
             )
         )).map(quoteIdentifier)
    }

    val partCols = getColumnNamesByType("part", "partitioning columns")
    if (partCols.nonEmpty) {
      builder ++= s"PARTITIONED BY ${partCols.mkString("(", ", ", ")")}\n"
    }

    val bucketCols = getColumnNamesByType("bucket", "bucketing columns")
    if (bucketCols.nonEmpty) {
      builder ++= s"CLUSTERED BY ${bucketCols.mkString("(", ", ", ")")}\n"

      val sortCols = getColumnNamesByType("sort", "sorting columns")
      if (sortCols.nonEmpty) {
        builder ++= s"SORTED BY ${sortCols.mkString("(", ", ", ")")}\n"
      }

      val numBuckets = props.getOrElse(
          "spark.sql.sources.schema.numBuckets",
          throw new AnalysisException("Corrupted bucket spec in catalog: missing bucket number")
      )

      builder ++= s"INTO $numBuckets BUCKETS\n"
    }
  }

  private def escapeSingleQuotedString(str: String): String = {
    val builder = StringBuilder.newBuilder

    str.foreach {
      case '\'' => builder ++= s"\\\'"
      case ch => builder += ch
    }

    builder.toString()
  }
}
