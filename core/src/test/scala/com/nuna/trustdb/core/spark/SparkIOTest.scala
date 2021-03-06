package com.nuna.trustdb.core.spark

import java.io.IOException

import com.nuna.trustdb.core.SparkTestBase
import com.nuna.trustdb.core.util.{Arbitrary, IO}

class SparkIOTest extends SparkTestBase {
  import SparkIO._

  val emptySparkIOConfig = Arbitrary.empty[SparkIOConfig]
  val emptySosTable = Arbitrary.empty[SosTable]

  test("parsePathParams") {
    assert(parsePathParams("dir") === ("dir", Seq.empty))
    assert(parsePathParams("dir/file") === ("dir/file", Seq.empty))
    assert(parsePathParams("?param=value") === ("", Seq("param" -> "value")))
    assert(parsePathParams("dir/file?param1=value1&param2=&param3=value3&param3")
        === ("dir/file", Seq("param1" -> "value1", "param2" -> "", "param3" -> "value3", "param3" -> null)))
    assert(parsePathParams("dir?sos-listing_strategy=*~42/@&foo=foo")
        === ("dir", Seq("sos-listing_strategy" -> "*~42/@", "foo" -> "foo")))
  }

  test("resolveInputs edge cases") {
    val tmp = createLocalTempDirectory().toAbsolutePath.toString
    val sparkIO = new SparkIO(sparkSession, emptySparkIOConfig)

    def interceptException(path: String): String = {
      intercept[IllegalArgumentException](sparkIO.resolveInputs(s"$tmp/$path")).getMessage
    }

    assert(interceptException("foo.list?sos-table_name=bar").contains("not supported on *.list"))
    assert(interceptException("foo?sos-listing_strategy=tables&sos-table_name=bar")
        .contains("at the same time are not supported"))
    assert(interceptException("foo?sos-listing_strategy=unsupported").contains("Unsupported listing strategy"))
    assert(interceptException("foo?sos-listing_strategy=dag_unsupported").contains("Unsupported dag listing strategy"))
    assert(interceptException(".foo").contains("Unsupported path starting with dot"))
  }

  test("resolveInputs") {
    val tmp = createLocalTempDirectory().toAbsolutePath.toString

    writeDagLists(tmp, "aa", Seq.empty, Seq.empty, Seq("aa/aa"))
    writeDagLists(tmp, "a", Seq("aa"), Seq("aa/aa"), Seq("a/a"))
    writeDagLists(tmp, "b", Seq.empty, Seq("non_dag_table"), Seq("b/b"))
    writeDagLists(tmp, "dag", Seq("a", "b"), Seq("a/a", "b/b"), Seq("dag/foo", "dag/bar"))
    writeEmptyDirectories(tmp, "dag/foo", "dag/bar")

    val dagAA = SosDag(s"$tmp/aa")
    val aa = emptySosTable.copy(name = "aa", path = s"$tmp/aa/aa")
    val dagA = SosDag(s"$tmp/a")
    val a = emptySosTable.copy(name = "a", path = s"$tmp/a/a")
    val dagB = SosDag(s"$tmp/b")
    val b = emptySosTable.copy(name = "b", path = s"$tmp/b/b")
    val nonDagTable = emptySosTable.copy(name = "non_dag_table", path = s"$tmp/non_dag_table")
    val dagDag = SosDag(s"$tmp/dag")
    val foo = emptySosTable.copy(name = "foo", path = s"$tmp/dag/foo")
    val bar = emptySosTable.copy(name = "bar", path = s"$tmp/dag/bar")

    val sparkIO = new SparkIO(sparkSession, emptySparkIOConfig)
    assert(sparkIO.resolveInputs(s"$tmp/dag/foo").toSet === Set(foo))
    assert(sparkIO.resolveInputs(s"$tmp/dag/foo?sos-table_name=renamed").toSet === Set(foo.copy(name = "renamed")))
    assert(sparkIO.resolveInputs(s"$tmp/dag/.dag").toSet === Set(dagDag, bar, foo))
    assert(sparkIO.resolveInputs(s"$tmp/dag/.dag/output_tables.list").toSet === Set(bar, foo))
    assert(sparkIO.resolveInputs(s"$tmp/dag?sos-listing_strategy=tables").toSet
        === Set(foo, bar).map(t => t.copy(path = s"file:${t.path}")))
    assert(sparkIO.resolveInputs(s"$tmp/dag?sos-listing_strategy=dag").toSet === Set(dagDag, bar, foo))
    assert(sparkIO.resolveInputs(s"$tmp/dag?sos-listing_strategy=dag_io").toSet === Set(dagDag, a, b, bar, foo))
    assert(sparkIO.resolveInputs(s"$tmp/dag?sos-listing_strategy=dag_io_recursive").toSet
        === Set(dagAA, aa, dagA, a, dagB, b, dagDag, bar, foo, nonDagTable))
  }

  test("init and addInputPaths") {
    val tmp = createLocalTempDirectory().toAbsolutePath.toString

    writeDagLists(tmp, "dag", Seq.empty, Seq.empty, Seq("dag/foo"))
    writeDummyParquetTables(tmp, "dag/foo")
    writeDummyParquetTables(tmp, "non_dag_table")

    val sparkIOConfig = emptySparkIOConfig.copy(inputPaths = Seq(s"$tmp/dag/.dag"), outputPath = Some(s"$tmp/out"))
    IO.using(new SparkIO(sparkSession, sparkIOConfig)) { sparkIO =>
      assert(sparkIO.getInputTable("foo").isEmpty)
      sparkIO.init()
      assert(sparkIO.getInputTable("foo").isDefined)

      assert(sparkIO.getInputTable("non_dag_table").isEmpty)
      sparkIO.addInputPaths(s"$tmp/non_dag_table")
      assert(sparkIO.getInputTable("non_dag_table").isDefined)

      assert(sparkIO.getInputTable("bar").isEmpty)
      sparkIO.addInputPaths(s"$tmp/non_dag_table?sos-table_name=bar")
      assert(sparkIO.getInputTable("bar").isDefined)

      val conflictingException = intercept[IOException](sparkIO.addInputPaths(s"$tmp/dag/foo?sos-table_name=bar"))
      assert(conflictingException.getMessage.contains("conflicting tables"))

      // It is fine to add path that will resolve to the same table (including all options).
      sparkIO.addInputPaths(s"$tmp/dag/foo?sos-table_name=foo")

      assert(!dfs.exists(s"$tmp/out/.dag"))
    }

    assert(dfs.exists(s"$tmp/out/.dag"))
  }

  def writeDagLists(tmpDir: String, dagName: String,
      inputDags: Seq[String], inputTables: Seq[String], outputTables: Seq[String]): Unit = {
    dfs.writeString(s"$tmpDir/$dagName/.dag/input_dags.list",
      inputDags.map(d => s"$tmpDir/$d?sos-listing_strategy=dag").mkString("\n"))
    dfs.writeString(s"$tmpDir/$dagName/.dag/input_tables.list",
      inputTables.map(t => s"$tmpDir/$t").sorted.mkString("\n"))
    dfs.writeString(s"$tmpDir/$dagName/.dag/output_tables.list",
      outputTables.map(t => s"$tmpDir/$t").sorted.mkString("\n"))
  }

  def writeEmptyDirectories(tmpDir: String, outputTablePaths: String*): Unit = {
    outputTablePaths.foreach(p => dfs.mkdirs(s"$tmpDir/$p"))
  }

  def writeDummyParquetTables(tmpDir: String, outputTablePaths: String*): Unit = {
    val dummyDf = sparkSession.range(1).toDF()
    outputTablePaths.foreach(p => writeDataFrame(s"$tmpDir/$p", dummyDf))
  }
}
