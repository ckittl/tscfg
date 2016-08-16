package tscfg

import java.io.PrintWriter
import java.util.Date

import tscfg.generator._
import tscfg.nodes._

object scalaGenerator {

  def generate(node: Node, out: PrintWriter)
              (implicit genOpts: GenOpts): GenResult = {

    out.println(s"// generated by tscfg $version on ${new Date()}")
    genOpts.preamble foreach { p =>
      out.println(s"// ${p.replace("\n", "\n// ")}\n")
    }
    out.println(s"package ${genOpts.packageName}\n")

    var results = GenResult()

    gen(node)

    def gen(n: Node, indent: String = "")
           (implicit genOpts: GenOpts): Unit = {

      val simple = n.key.simple
      val symbol = if (simple == "/") genOpts.className else simple

      val scalaId = scalaIdentifier(symbol)

      n match {
        case ln: LeafNode  => genForLeaf(ln)
        case n: BranchNode => genForBranch(n)
      }

      def genForLeaf(ln: LeafNode): Unit = {
        out.println(s"$indent  $scalaId: ${ln.accessor.`type`}")
      }

      def genForBranch(bn: BranchNode): Unit = {
        var comma = ""

        val className = upperFirst(symbol)

        val orderedNames = bn.keys().toList.sorted

        val padScalaIdLength = if (orderedNames.nonEmpty)
          orderedNames.map(scalaIdentifier).maxBy(_.length).length else 0
        def padScalaId(id: String) = id + (" " * (padScalaIdLength - id.length))

        // <object>
        out.println(s"${indent}object $className {")

        // <recurse>
        orderedNames foreach { name =>
          bn(name) match {
            case sbn@BranchNode(k, _) => gen(sbn, indent + "  ")
            case _ =>
          }
        }
        // </recurse>

        // <apply>
        out.println(s"$indent  def apply(c: $TypesafeConfigClassName): $className = {")
        out.println(s"$indent    $className(")

        comma = indent
        orderedNames foreach { name =>
          out.print(comma)
          bn(name) match {
            case ln@LeafNode(k, v) =>
              out.print(s"""      ${ln.accessor.instance(k.simple)}""")

            case BranchNode(k, _)  =>
              val className = upperFirst(k.simple)
              out.print(s"""      $className(c.getConfig("${k.simple}"))""")
          }
          comma = s",\n$indent"
        }
        out.println()
        out.println(s"$indent    )")
        out.println(s"$indent  }")
        // </apply>

        out.println(s"$indent}")
        // </object>

        // <class>
        results = results.copy(classNames = results.classNames + className)
        out.println(s"${indent}case class $className(")
        comma = ""
        orderedNames foreach { name =>
          val scalaId = scalaIdentifier(name)
          results = results.copy(fieldNames = results.fieldNames + scalaId)
          out.print(comma)
          out.print(s"$indent  ${padScalaId(scalaId)} : ")  // note, space before : for proper tokenization
          bn(name) match {
            case ln@LeafNode(k, v) =>
              out.print(s"""${ln.accessor.`type`}""")

            case BranchNode(k, _)  =>
              // use full qualified class name
              val className = genOpts.className + "." + k.parts.map(upperFirst).mkString(".")
              out.print(s"""$className""")
          }
          comma = ",\n"
        }

        // <class-body>
        out.println(s"\n$indent) {")


        // toString():
        out.println(s"""$indent  override def toString: String = toString("")""")

        val padNameLength = if (orderedNames.nonEmpty) orderedNames.maxBy(_.length).length else 0
        def padName(str: String) = str + (" " * (padNameLength - str.length))

        // <toString(i:String)>
        out.println(s"""$indent  def toString(i:String): String = {""")
        val ids = orderedNames map { name =>
          val id = scalaIdentifier(name)

          bn(name) match {
            case ln@LeafNode(k, v) =>
              s"""  i+ "${padName(name)} = """ +
              (if(ln.accessor.`type` == "String") {
                s"""" + '"' + this.$id + '"'"""
              }
              else if(ln.accessor.`type` == "Option[String]") {
                val value = s"""if(this.$id.isDefined) "Some(" +'"' +this.$id.get+ '"' + ")" else "None""""
                s"""" + (""" + value + ")"
              }
              else {
                s"""" + this.$id"""
              }) +
              s""" + "\\n""""

            case BranchNode(k, _) =>
              s"""  i+ "$name:\\n" + this.$id.toString(i+"    ")"""
          }
        }
        out.println(s"$indent  ${ids.mkString(s"+\n$indent  ")}")
        out.println(s"$indent  }")
        // <toString(i:String)>

        out.println(s"$indent}")
        // </class-body>
        // </class>
      }
    }
    results
  }

  /**
    * Returns a valid scala identifier from the given symbol:
    *
    * - encloses the symbol in backticks if the symbol is a scala reserved word;
    * - otherwise, returns symbol if it is a valid java identifier
    * - otherwise, returns `javaGenerator.javaIdentifier(symbol)`
    */
  def scalaIdentifier(symbol: String): String = {
    if (scalaReservedWords.contains(symbol)) "`" + symbol + "`"
    else if (javaGenerator.isJavaIdentifier(symbol)) symbol
    else javaGenerator.javaIdentifier(symbol)
  }

  private def upperFirst(symbol:String) = symbol.charAt(0).toUpper + symbol.substring(1)

  /**
    * from Sect 1.1 of the Scala Language Spec, v2.9
    */
  val scalaReservedWords: List[String] = List(
    "abstract", "case",     "catch",   "class",   "def",
    "do",       "else",     "extends", "false",   "final",
    "finally",  "for",      "forSome", "if",      "implicit",
    "import",   "lazy",     "match",   "new",     "null",
    "object",   "override", "package", "private", "protected",
    "return",   "sealed",   "super",   "this",    "throw",
    "trait",    "try",      "true",    "type",    "val",
    "var",      "while",    "with",    "yield"
  )

  val TypesafeConfigClassName = classOf[com.typesafe.config.Config].getName
}
