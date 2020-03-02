package com.nuna.trustdb.core

import java.nio.file.{Files, Path}
import java.sql.Date

import com.github.mrpowers.spark.fast.tests.DataFrameComparer
import com.nuna.trustdb.core.spark.Dfs
import org.apache.spark.sql._
import org.scalatest.funsuite.AnyFunSuite

class SparkTestBase extends AnyFunSuite {
  lazy val sparkSession = SparkSession.builder()
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.master.rest.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", "target/spark-warehouse")
      .appName(this.getClass.getName.stripSuffix("$"))
      .getOrCreate()

  lazy val dfs = new Dfs(sparkSession)

  def assertDatasetEquality[T](actual: Dataset[T], expected: Dataset[T], ignoreNullable: Boolean = false,
      ignoreColumnNames: Boolean = false, ignoreOrdering: Boolean = true): Unit = {
    SparkTestBase.comparer.assertSmallDatasetEquality(actual, expected, ignoreNullable = ignoreNullable,
      ignoreColumnNames = ignoreColumnNames, orderedComparison = !ignoreOrdering)
  }

  def assertDataFrameEquality(actual: DataFrame, expected: DataFrame, ignoreNullable: Boolean = false,
      ignoreColumnNames: Boolean = false, ignoreOrdering: Boolean = true, precision: Double = 0.0): Unit = {
    if (precision == 0.0) {
      SparkTestBase.comparer.assertSmallDataFrameEquality(actual, expected, ignoreNullable = ignoreNullable,
        ignoreColumnNames = ignoreColumnNames, orderedComparison = !ignoreOrdering)
    } else {
      SparkTestBase.comparer.assertApproximateDataFrameEquality(actual, expected, precision = precision,
        ignoreNullable = ignoreNullable, ignoreColumnNames = ignoreColumnNames, orderedComparison = !ignoreOrdering)
    }
  }

  def createLocalTempDirectory(deleteOnExit: Boolean = true): Path = {
    val directory = Files.createTempDirectory(this.getClass.getSimpleName + ".")
    if (deleteOnExit) {
      directory.toFile.deleteOnExit()
    }
    directory
  }

  def readDataset[T: Encoder](path: String): Dataset[T] = {
    sparkSession.read.format("parquet").load(path).as[T]
  }

  def readData[T: Encoder](path: String): Seq[T] = {
    readDataset[T](path).collect()
  }

  def writeDataset[T: Encoder](path: String, dataset: Dataset[T]): Unit = {
    dataset.write.format("parquet").mode(SaveMode.Overwrite).save(path)
  }

  def writeData[T: Encoder](path: String, data: Seq[T]): Unit = {
    writeDataset[T](path, sparkSession.createDataset(data))
  }
}

object SparkTestBase {
  private[SparkTestBase] val comparer = new DataFrameComparer {}

  // Dangerous and powerful implicits lives here. Be careful what we add here.
  // Do NOT copy this to main - it is for tests only!
  object implicits {
    import scala.language.implicitConversions

    implicit def toOption[T](value: T): Option[T] = Some(value)

    implicit def toDate(date: String): Date = Date.valueOf(date)
  }
}
