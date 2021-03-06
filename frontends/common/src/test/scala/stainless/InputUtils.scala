/* Copyright 2009-2018 EPFL, Lausanne */

package stainless

import extraction.xlang.{ trees => xt }
import frontend.{ CallBack, MasterCallBack }
import utils.{ CheckFilter, DependenciesFinder, Registry }

import scala.collection.mutable.ListBuffer

import java.io.{ File, BufferedWriter, FileWriter }

trait InputUtils {

  type Filter = CheckFilter { val trees: xt.type }

  /** Compile and extract the given files' **content** (& the library). */
  def load(context: inox.Context, contents: Seq[String], filterOpt: Option[Filter] = None):
          (Seq[xt.UnitDef], Program { val trees: xt.type }) = {

    val files = contents.map { content =>
      val file = File.createTempFile("stainless", ".scala")
      file.deleteOnExit()
      val out = new BufferedWriter(new FileWriter(file))
      out.write(content)
      out.close()
      file.getAbsolutePath
    }

    loadFiles(context, files, filterOpt)
  }

  /** Compile and extract the given files (& the library). */
  def loadFiles(ctx: inox.Context, files: Seq[String], filterOpt: Option[Filter] = None):
               (Seq[xt.UnitDef], Program { val trees: xt.type }) = {

    // Use the callback to collect the trees.
    val units = ListBuffer[xt.UnitDef]()
    val cls = ListBuffer[xt.ClassDef]()
    val funs = ListBuffer[xt.FunDef]()
    var syms = xt.NoSymbols
    var done = false

    def updateSyms(extra: xt.Symbols) = {
      syms = syms.withClasses(extra.classes.values.toSeq)
                 .withFunctions(extra.functions.values.toSeq)
    }

    val callback = new CallBack {
      override def join(): Unit = ()
      override def stop(): Unit = ()
      override def failed(): Unit = ()
      override def getReport = None

      private val registry = new Registry {
        override val context = ctx

        override def computeDirectDependencies(fd: xt.FunDef): Set[Identifier] =
          new DependenciesFinder()(fd)
        override def computeDirectDependencies(cd: xt.ClassDef): Set[Identifier] =
          new DependenciesFinder()(cd)

        override def shouldBeChecked(fd: xt.FunDef): Boolean =
          filterOpt map { _.shouldBeChecked(fd) } getOrElse true

        // When using no custom filter, require the class to be part
        // of the generated symbols.
        override def shouldBeChecked(cd: xt.ClassDef): Boolean =
          filterOpt.isEmpty
      }

      override def beginExtractions(): Unit = ()

      override def apply(file: String, unit: xt.UnitDef,
                         classes: Seq[xt.ClassDef], functions: Seq[xt.FunDef]): Unit = {
        units += unit
        cls ++= classes
        funs ++= functions

        val extraOpt = registry.update(classes, functions)
        extraOpt foreach updateSyms
      }

      override def endExtractions(): Unit = {
        // Ensure all symbols were loaded properly: the registry should check that no symbol is missing.
        val extraOpt = registry.checkpoint()
        extraOpt foreach updateSyms
        done = true
      }
    }

    val master = new MasterCallBack(Seq(callback))
    val compiler = Main.factory(ctx, files, master)
    compiler.run()

    // Wait for compilation to finish to produce the whole program
    compiler.join()

    // Ensure the registry yields all classes and functions (unless using a custom filter)
    assert(done)
    if (filterOpt.isEmpty) {
      assert(syms.classes.values.toSet == cls.toSet)
      assert(syms.functions.values.toSet == funs.toSet)
    }

    val program = inox.Program(xt)(syms)

    (units.toSeq.sortBy(_.id.name), program)
  }

}

