package com.wavesplatform.lang.compiler

import cats.kernel.Monoid
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, EitherExt2}
import com.wavesplatform.lang.Global
import com.wavesplatform.lang.contract.DApp
import com.wavesplatform.lang.contract.DApp._
import com.wavesplatform.lang.directives.values.{DApp => DAppType, _}
import com.wavesplatform.lang.directives.DirectiveSet
import com.wavesplatform.lang.directives.values._
import com.wavesplatform.lang.v1.FunctionHeader.{Native, User}
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.lang.v1.compiler.Types._
import com.wavesplatform.lang.v1.compiler._
import com.wavesplatform.lang.v1.evaluator.ctx.impl._
import com.wavesplatform.lang.v1.evaluator.ctx.impl.waves.WavesContext
import com.wavesplatform.lang.v1.parser.BinaryOperation.NE_OP
import com.wavesplatform.lang.v1.parser.Parser
import com.wavesplatform.lang.v1.traits.Environment
import com.wavesplatform.lang.v1.{CTX, FunctionHeader, compiler}
import com.wavesplatform.protobuf.dapp.DAppMeta
import org.scalatest.{Matchers, PropSpec}
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class DecompilerTest extends PropSpec with PropertyChecks with Matchers {

  implicit class StringCmp(s1: String) {
    def shouldEq(s2: String) = s1.replace("\r\n", "\n") shouldEqual s2.replace("\r\n", "\n")
  }

  def getCtx(v: StdLibVersion): CTX[Environment] = {
    Monoid.combineAll(
      Seq(
        getTestContext(v).withEnvironment[Environment],
        CryptoContext.build(Global, v).withEnvironment[Environment],
        WavesContext.build(DirectiveSet.contractDirectiveSet)
      )
    )
  }

  val decompilerContextV3 = getCtx(V3).decompilerContext
  val decompilerContextV4 = getCtx(V4).decompilerContext

  property("successful on very deep expressions (stack overflow check)") {
    val expr = (1 to 10000).foldLeft[EXPR](CONST_LONG(0)) { (acc, _) =>
      FUNCTION_CALL(function = FunctionHeader.Native(100), List(CONST_LONG(1), acc))
    }
    Decompiler(expr, decompilerContextV3) should startWith("(1 + (1 + (1 + (1 + (1 + (1 + ")
  }

  property("simple let") {
    val expr = Terms.LET_BLOCK(LET("a", CONST_LONG(1)), Terms.LET_BLOCK(LET("b", CONST_LONG(2)), Terms.LET_BLOCK(LET("c", CONST_LONG(3)), TRUE)))
    Decompiler(expr, decompilerContextV3) shouldEq
      """let a = 1
        |let b = 2
        |let c = 3
        |true""".stripMargin
  }

  property("let in let") {
    val expr =
      Terms.LET_BLOCK(LET("a", Terms.LET_BLOCK(LET("x", CONST_LONG(0)), TRUE)), Terms.LET_BLOCK(LET("c", CONST_LONG(3)), TRUE))
    Decompiler(expr, decompilerContextV3) shouldEq
      """let a = {
        |    let x = 0
        |    true
        |    }
        |let c = 3
        |true""".stripMargin
  }
  property("native function call with one arg") {
    val expr = Terms.FUNCTION_CALL(
      function = FunctionHeader.Native(500),
      args = List(TRUE)
    )
    Decompiler(expr, decompilerContextV3) shouldEq "sigVerify(true)"
  }

  property("native function call with two arg (binary operations)") {
    val expr = Terms.FUNCTION_CALL(
      function = FunctionHeader.Native(100),
      args = List(CONST_LONG(1), CONST_LONG(2))
    )
    Decompiler(expr, decompilerContextV3) shouldEq "(1 + 2)"
  }

  property("nested binary operations") {
    val expr = FUNCTION_CALL(Native(105), List(FUNCTION_CALL(Native(101), List(REF("height"), REF("startHeight"))), REF("interval")))
    Decompiler(expr, decompilerContextV3) shouldEq "((height - startHeight) / interval)"
  }

  property("unknown native function call") {
    val expr = Terms.FUNCTION_CALL(
      function = FunctionHeader.Native(254),
      args = List(CONST_LONG(1), CONST_LONG(2))
    )
    Decompiler(expr, decompilerContextV3) shouldEq "Native<254>(1, 2)"
  }

  property("user function call with one args") {
    val expr = Terms.FUNCTION_CALL(
      function = FunctionHeader.User("foo"),
      args = List(TRUE)
    )
    Decompiler(expr, decompilerContextV3) shouldEq "foo(true)"
  }

  property("user function call with empty args") {
    val expr = Terms.FUNCTION_CALL(
      function = FunctionHeader.User("foo"),
      args = List.empty
    )
    Decompiler(expr, decompilerContextV3) shouldEq "foo()"
  }

  property("v2 with LET in BLOCK") {
    val expr = Terms.BLOCK(
      LET("vari", REF("p")),
      TRUE
    )
    val actual   = Decompiler(expr, decompilerContextV3)
    val expected = """|let vari = p
                      |true""".stripMargin
    actual shouldEq expected
  }

  property("let and function call in block") {
    val expr = Terms.BLOCK(
      Terms.LET("v", CONST_LONG(1)),
      Terms.FUNCTION_CALL(
        function = FunctionHeader.Native(100),
        args = List(REF("v"), CONST_LONG(2))
      )
    )
    Decompiler(expr, decompilerContextV3) shouldEq
      """let v = 1
        |(v + 2)""".stripMargin
  }

  property("neq binary op") {
    val expr =
      Terms.FUNCTION_CALL(
        function = FunctionHeader.User(NE_OP.func),
        args = List(CONST_LONG(4), CONST_LONG(2))
      )
    Decompiler(expr, decompilerContextV3) shouldEq
      """(4 != 2)""".stripMargin
  }

  property("function with complex args") {
    val expr = BLOCK(
      LET(
        "x",
        BLOCK(
          LET(
            "y",
            Terms.FUNCTION_CALL(
              function = FunctionHeader.User("foo"),
              args = List(BLOCK(LET("a", CONST_LONG(4)), REF("a")), CONST_LONG(2))
            )
          ),
          TRUE
        )
      ),
      FALSE
    )
    Decompiler(expr, decompilerContextV3) shouldEq
      """let x = {
        |    let y = foo({
        |        let a = 4
        |        a
        |        }, 2)
        |    true
        |    }
        |false""".stripMargin
  }

  property("complicated let in let and function call in block") {
    val expr = Terms.BLOCK(
      Terms.LET(
        "p",
        Terms.BLOCK(Terms.LET("v", CONST_LONG(1)), Terms.FUNCTION_CALL(function = FunctionHeader.Native(100), args = List(REF("v"), CONST_LONG(2))))
      ),
      Terms.FUNCTION_CALL(function = FunctionHeader.Native(100), args = List(REF("p"), CONST_LONG(3)))
    )
    Decompiler(expr, decompilerContextV3) shouldEq
      """let p = {
        |    let v = 1
        |    (v + 2)
        |    }
        |(p + 3)""".stripMargin
  }

  property("old match") {
    val expr = Terms.BLOCK(
      LET("v", CONST_LONG(1)),
      IF(
        IF(
          FUNCTION_CALL(function = FunctionHeader.Native(100), args = List(REF("v"), CONST_LONG(2))),
          TRUE,
          FUNCTION_CALL(function = FunctionHeader.Native(100), args = List(REF("v"), CONST_LONG(3)))
        ),
        BLOCK(LET("p", REF("v")), TRUE),
        FALSE
      )
    )
    Decompiler(expr, decompilerContextV3) shouldEq
      """let v = 1
        |if (if ((v + 2))
        |    then true
        |    else (v + 3))
        |    then {
        |        let p = v
        |        true
        |        }
        |    else false""".stripMargin
  }

  property("ref getter idents") {
    val expr = GETTER(REF("a"), "foo")
    Decompiler(expr, decompilerContextV3) shouldEq
      """a.foo""".stripMargin
  }

  property("block getter idents") {
    val expr = GETTER(BLOCK(LET("a", FALSE), REF("a")), "foo")
    Decompiler(expr, decompilerContextV3) shouldEq
      """{
        |    let a = false
        |    a
        |    }.foo""".stripMargin
  }

  property("Invoke contract with verifier decompilation") {
    val contract = DApp(
      DAppMeta(),
      List(FUNC("foo", List(), FALSE), FUNC("bar", List(), IF(FUNCTION_CALL(User("foo"), List()), TRUE, FALSE))),
      List(
        CallableFunction(
          CallableAnnotation("invocation"),
          FUNC(
            "baz",
            List("a"),
            BLOCK(
              LET("x", GETTER(GETTER(REF("invocation"), "caller"), "bytes")),
              IF(
                FUNCTION_CALL(User("foo"), List()),
                FUNCTION_CALL(
                  User("WriteSet"),
                  List(
                    FUNCTION_CALL(
                      Native(1100),
                      List(
                        FUNCTION_CALL(User("DataEntry"), List(CONST_STRING("b").explicitGet(), CONST_LONG(1))),
                        FUNCTION_CALL(
                          Native(1100),
                          List(FUNCTION_CALL(User("DataEntry"), List(CONST_STRING("sender").explicitGet(), REF("x"))), REF("nil"))
                        )
                      )
                    )
                  )
                ),
                FUNCTION_CALL(
                  User("WriteSet"),
                  List(
                    FUNCTION_CALL(
                      Native(1100),
                      List(
                        FUNCTION_CALL(User("DataEntry"), List(CONST_STRING("a").explicitGet(), REF("a"))),
                        FUNCTION_CALL(
                          Native(1100),
                          List(FUNCTION_CALL(User("DataEntry"), List(CONST_STRING("sender").explicitGet(), REF("x"))), REF("nil"))
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      ),
      Some(VerifierFunction(VerifierAnnotation("t"), FUNC("verify", List(), TRUE)))
    )
    Decompiler(contract: DApp, decompilerContextV3) shouldEq
      """|func foo () = false
         |
         |
         |func bar () = if (foo())
         |    then true
         |    else false
         |
         |
         |@Callable(invocation)
         |func baz (a) = {
         |    let x = invocation.caller.bytes
         |    if (foo())
         |        then WriteSet([DataEntry("b", 1), DataEntry("sender", x)])
         |        else WriteSet([DataEntry("a", a), DataEntry("sender", x)])
         |    }
         |
         |
         |@Verifier(t)
         |func verify () = true
         |""".stripMargin
  }

  property("Invoke contract decompilation") {
    val contract = DApp(
      DAppMeta(),
      List(Terms.FUNC("foo", List("bar", "buz"), CONST_BOOLEAN(true))),
      List(
        CallableFunction(
          CallableAnnotation("i"),
          Terms.FUNC(
            "testfunc",
            List("amount"),
            BLOCK(
              LET("pmt", CONST_LONG(1)),
              TRUE
            )
          )
        )
      ),
      None
    )
    Decompiler(contract, decompilerContextV3) shouldEq
      """func foo (bar,buz) = true
        |
        |
        |@Callable(i)
        |func testfunc (amount) = {
        |    let pmt = 1
        |    true
        |    }
        |
        |""".stripMargin

  }

  property("Invoke contract decompilation with meta") {
    val contract = DApp(
      DAppMeta(),
      List(Terms.FUNC("foo", List("bar", "buz"), CONST_BOOLEAN(true))),
      List(
        CallableFunction(
          CallableAnnotation("i"),
          Terms.FUNC(
            "testfunc",
            List("amount"),
            BLOCK(
              LET("pmt", CONST_LONG(1)),
              TRUE
            )
          )
        )
      ),
      None
    )
    Decompiler(contract, decompilerContextV3) shouldEq
      """func foo (bar,buz) = true
        |
        |
        |@Callable(i)
        |func testfunc (amount) = {
        |    let pmt = 1
        |    true
        |    }
        |
        |""".stripMargin

  }

  property("bytestring") {
    val test = Base58.encode("abc".getBytes("UTF-8"))
    // ([REVIEW]: may be i`am make a mistake here)
    val expr = Terms.BLOCK(Terms.LET("param", CONST_BYTESTR(ByteStr(test.getBytes("UTF-8"))).explicitGet()), REF("param"))
    Decompiler(expr, decompilerContextV3) shouldEq
      """let param = base58'3K3F4C'
        |param""".stripMargin
  }

  property("getter") {
    val expr = Terms.GETTER(
      Terms.FUNCTION_CALL(
        function = FunctionHeader.User("testfunc"),
        args = List(TRUE)
      ),
      "testfield"
    )
    Decompiler(expr, decompilerContextV3) shouldEq
      """testfunc(true).testfield""".stripMargin
  }

  property("simple if") {
    val expr = IF(TRUE, CONST_LONG(1), CONST_STRING("XXX").explicitGet())
    Decompiler(expr, decompilerContextV3) shouldEq
      """if (true)
        |    then 1
        |    else "XXX"""".stripMargin
  }

  property("if with complicated else branch") {
    val expr = IF(TRUE, CONST_LONG(1), IF(TRUE, CONST_LONG(1), CONST_STRING("XXX").explicitGet()))
    Decompiler(expr, decompilerContextV3) shouldEq
      """if (true)
        |    then 1
        |    else if (true)
        |        then 1
        |        else "XXX"""".stripMargin
  }

  property("if with complicated then branch") {
    val expr = IF(TRUE, IF(TRUE, CONST_LONG(1), CONST_STRING("XXX").explicitGet()), CONST_LONG(1))
    Decompiler(expr, decompilerContextV3) shouldEq
      """if (true)
        |    then if (true)
        |        then 1
        |        else "XXX"
        |    else 1""".stripMargin
  }

  property("if with != condition") {
    val expr = IF(
      FUNCTION_CALL(User("!="), List(REF("left"), REF("right"))),
      REF("left"),
      REF("right")
    )
    Decompiler(expr, decompilerContextV3) shouldEq
      """if ((left != right))
        |    then left
        |    else right""".stripMargin
  }

  property("Surge smart accet") {
    val expr = BLOCK(
      LET("startHeight", CONST_LONG(1375557)),
      BLOCK(
        LET("startPrice", CONST_LONG(100000)),
        BLOCK(
          LET("interval", FUNCTION_CALL(Native(104), List(CONST_LONG(24), CONST_LONG(60)))),
          BLOCK(
            LET("exp", FUNCTION_CALL(Native(104), List(FUNCTION_CALL(Native(104), List(CONST_LONG(100), CONST_LONG(60))), CONST_LONG(1000)))),
            BLOCK(
              LET("$match0", REF("tx")),
              IF(
                FUNCTION_CALL(Native(1), List(REF("$match0"), CONST_STRING("ExchangeTransaction").explicitGet())),
                BLOCK(
                  LET("e", REF("$match0")),
                  BLOCK(
                    LET(
                      "days",
                      FUNCTION_CALL(Native(105), List(FUNCTION_CALL(Native(101), List(REF("height"), REF("startHeight"))), REF("interval")))
                    ),
                    IF(
                      IF(
                        IF(
                          FUNCTION_CALL(
                            Native(103),
                            List(
                              GETTER(REF("e"), "price"),
                              FUNCTION_CALL(
                                Native(104),
                                List(
                                  REF("startPrice"),
                                  FUNCTION_CALL(Native(100), List(CONST_LONG(1), FUNCTION_CALL(Native(104), List(REF("days"), REF("days")))))
                                )
                              )
                            )
                          ),
                          FUNCTION_CALL(
                            User("!"),
                            List(FUNCTION_CALL(User("isDefined"), List(GETTER(GETTER(GETTER(REF("e"), "sellOrder"), "assetPair"), "priceAsset"))))
                          ),
                          FALSE
                        ),
                        FUNCTION_CALL(
                          Native(103),
                          List(
                            REF("exp"),
                            FUNCTION_CALL(
                              Native(101),
                              List(GETTER(GETTER(REF("e"), "sellOrder"), "expiration"), GETTER(GETTER(REF("e"), "sellOrder"), "timestamp"))
                            )
                          )
                        ),
                        FALSE
                      ),
                      FUNCTION_CALL(
                        Native(103),
                        List(
                          REF("exp"),
                          FUNCTION_CALL(
                            Native(101),
                            List(GETTER(GETTER(REF("e"), "buyOrder"), "expiration"), GETTER(GETTER(REF("e"), "buyOrder"), "timestamp"))
                          )
                        )
                      ),
                      FALSE
                    )
                  )
                ),
                IF(
                  FUNCTION_CALL(
                    Native(1),
                    List(REF("$match0"), CONST_STRING("BurnTransaction").explicitGet())
                  ),
                  BLOCK(LET("tx", REF("$match0")), TRUE),
                  FALSE
                )
              )
            )
          )
        )
      )
    )

    Decompiler(expr, decompilerContextV3) shouldEq
      """let startHeight = 1375557
        |let startPrice = 100000
        |let interval = (24 * 60)
        |let exp = ((100 * 60) * 1000)
        |match tx {
        |    case e: ExchangeTransaction => 
        |        let days = ((height - startHeight) / interval)
        |        if (if (if ((e.price >= (startPrice * (1 + (days * days)))))
        |            then !(isDefined(e.sellOrder.assetPair.priceAsset))
        |            else false)
        |            then (exp >= (e.sellOrder.expiration - e.sellOrder.timestamp))
        |            else false)
        |            then (exp >= (e.buyOrder.expiration - e.buyOrder.timestamp))
        |            else false
        |    case tx: BurnTransaction => 
        |        true
        |    case _ => 
        |        false
        |}""".stripMargin
  }

  def compileExpr(code: String, v: StdLibVersion = V3): Either[String, (EXPR, TYPE)] = {
    val untyped = Parser.parseExpr(code).get.value
    val typed   = ExpressionCompiler(getCtx(v).compilerContext, untyped)
    typed
  }

  property("match") {
    val script = """
      match tv {
        case x : PointA|PointB => 1
        case x : PointC => 2
    }"""

    val Right((expr, ty)) = compileExpr(script)

    val rev = Decompiler(expr, decompilerContextV3)

    rev shouldEq """match tv {
    |    case x: PointB|PointA => 
    |        1
    |    case x: PointC => 
    |        2
    |    case _ => 
    |        throw()
    |}""".stripMargin
  }

  property("match with case without type") {
    val script = """
      match tv {
        case x : PointA|PointB => 1
        case x => 2
    }"""

    val Right((expr, ty)) = compileExpr(script)

    val rev = Decompiler(expr, decompilerContextV3)

    rev shouldEq """match tv {
    |    case x: PointB|PointA => 
    |        1
    |    case x => 
    |        2
    |}""".stripMargin
  }

  property("match with case without var") {
    val script = """
      match tv {
        case _ : PointA|PointB => 1
        case x => 2
    }"""

    val Right((expr, ty)) = compileExpr(script)

    val rev = Decompiler(expr, decompilerContextV3)

    rev shouldEq """match tv {
    |    case _: PointB|PointA => 
    |        1
    |    case x => 
    |        2
    |}""".stripMargin
  }

  property("multiple value list") {
    val script           = """["a", "b", "c", "d"]"""
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("concat with nil is hidden") {
    val expected          = """["a", "b"]"""
    val Right((expr1, _)) = compileExpr(""""a"::"b"::nil""")
    val Right((expr2, _)) = compileExpr("""cons("a", cons("b", nil))""")
    Decompiler(expr1, decompilerContextV3) shouldEq expected
    Decompiler(expr2, decompilerContextV3) shouldEq expected
  }

  property("single value list") {
    val script           = """["a"]"""
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("existing list concat") {
    val script =
      """let list = ["b", "c", "d"]
        |"a" :: list""".stripMargin
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("extracted functions") {
    val script           = """addressFromStringValue("abcd")"""
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("list element access") {
    val script =
      """let arr = [1, 2, 3]
        |arr[1]""".stripMargin
    val Right((expr, _)) = compileExpr(script)
    val Right((expr2, _)) = compileExpr(
      """let arr = [1, 2, 3]
        |arr.getElement(1)""".stripMargin
    )
    Decompiler(expr, decompilerContextV3) shouldEq script
    Decompiler(expr2, decompilerContextV3) shouldEq script
  }

  property("get state values") {
    val script =
      """let bin = getBinary(this, "key")
        |let boo = getBoolean(this, "key")
        |let int = getInteger(this, "key")
        |let str = getString(this, "key")
        |let binV = getBinaryValue(this, "key")
        |let booV = getBooleanValue(this, "key")
        |let intV = getIntegerValue(this, "key")
        |let strV = getStringValue(this, "key")
        |boo""".stripMargin
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("get DataEntry list values by key") {
    val script =
      """let list = [DataEntry("k1", fromBase58String("3ye1XJ")), DataEntry("k2", true), DataEntry("k3", 42), DataEntry("k4", "true")]
        |let bin = getBinary(list, "k1")
        |let boo = getBoolean(list, "k2")
        |let int = getInteger(list, "k3")
        |let str = getString(list, "k4")
        |let binV = getBinaryValue(list, "k1")
        |let booV = getBooleanValue(list, "k2")
        |let intV = getIntegerValue(list, "k3")
        |let strV = getStringValue(list, "k4")
        |boo""".stripMargin
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("get DataEntry list values by index") {
    val script =
      """let list = [DataEntry("k1", fromBase58String("3ye1XJ")), DataEntry("k2", true), DataEntry("k3", 42), DataEntry("k4", "true")]
        |let bin = getBinary(list, 0)
        |let boo = getBoolean(list, 1)
        |let int = getInteger(list, 2)
        |let str = getString(list, 3)
        |let binV = getBinaryValue(list, 0)
        |let booV = getBooleanValue(list, 1)
        |let intV = getIntegerValue(list, 2)
        |let strV = getStringValue(list, 3)
        |boo""".stripMargin
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq script
  }

  property("list func params") {
    val script =
      """
        | func f(a: List[String], b: String) = a[0] + b
        | f(["a", "b"], "c")
      """.stripMargin
    val Right((expr, _)) = compileExpr(script)
    Decompiler(expr, decompilerContextV3) shouldEq
      """func f (a,b) = (a[0] + b)
        |
        |f(["a", "b"], "c")""".stripMargin
  }

  property("V4 - contains") {
    val script           = """"abc".contains("b")"""
    val Right((expr, _)) = compileExpr(script, V4)
    val res              = Decompiler(expr, decompilerContextV4)
    res shouldEq """contains("abc", "b")"""
  }

  property("V4 - valueOrElse") {
    val script           = """let a = 1
                             |let b = 2
                             |a.valueOrElse(b)""".stripMargin
    val Right((expr, _)) = compileExpr(script, V4)
    val res              = Decompiler(expr, decompilerContextV4)
    res shouldEq """let a = 1
                   |let b = 2
                   |valueOrElse(a, b)""".stripMargin
  }

  property("V4 - listAppend (:+)") {
    val script           = """[1, 2, 3] :+ 4"""
    val Right((expr, _)) = compileExpr(script, V4)
    val res              = Decompiler(expr, decompilerContextV4)
    res shouldEq """([1, 2, 3] :+ 4)"""
  }

  property("V4 - listConcat (++)") {
    val script           = """[1, 2, 3] ++ [4, 5, 42]"""
    val Right((expr, _)) = compileExpr(script, V4)
    val res              = Decompiler(expr, decompilerContextV4)
    res shouldEq """([1, 2, 3] ++ [4, 5, 42])"""
  }

  property("V4 - median") {
    val script           = """[1, 2, 3].median()"""
    val Right((expr, _)) = compileExpr(script, V4)
    val res              = Decompiler(expr, decompilerContextV4)
    res shouldEq """median([1, 2, 3])"""
  }

  property("V4 - new contract result format") {
    val script =
      """
        | {-# STDLIB_VERSION 4    #-}
        | {-#CONTENT_TYPE    DAPP #-}
        |
        | @Callable(i)
        | func foo() =
        |   [
        |     IntEntry("key", 1),
        |     BooleanEntry("key", true),
        |     StringEntry("key", "str"),
        |     BinaryEntry("key", base58''),
        |     ScriptTransfer(i.caller, 1, base58''),
        |     Issue(unit, 4, "description", true, "name", 1000),
        |     Reissue(base58'', false, 1),
        |     Burn(base58'', 1)
        |   ]
        """.stripMargin

    val parsedExpr = Parser.parseContract(script).get.value

    val ctx =
      Monoid.combine(
        PureContext.build(Global, V4).withEnvironment[Environment],
        WavesContext.build(DirectiveSet(V4, Account, DAppType).explicitGet())
      )

    val Right(dApp) = compiler.ContractCompiler(ctx.compilerContext, parsedExpr, V4)
    val res         = Decompiler(dApp, decompilerContextV4)
    res shouldEq """

@Callable(i)
func foo () = [IntEntry("key", 1), BooleanEntry("key", true), StringEntry("key", "str"), BinaryEntry("key", base58''), ScriptTransfer(i.caller, 1, base58''), Issue(unit, 4, "description", true, "name", 1000), Reissue(base58'', false, 1), Burn(base58'', 1)]

"""
  }
}
