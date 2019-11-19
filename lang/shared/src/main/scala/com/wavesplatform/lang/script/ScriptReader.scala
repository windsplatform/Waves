package com.wavesplatform.lang.script
import com.wavesplatform.lang.ValidationError.ScriptParseError
import com.wavesplatform.lang.contract.ContractSerDe
import com.wavesplatform.lang.directives.DirectiveDictionary
import com.wavesplatform.lang.directives.values._
import com.wavesplatform.lang.script.v1.ExprScript
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.lang.v1.{BaseGlobal, Serde}

object ScriptReader {

  private val Global: BaseGlobal = com.wavesplatform.lang.Global // Hack for IDEA

  val checksumLength = 4

  def fromBytes(inBytes: Array[Byte]): Either[ScriptParseError, Script] = {
    for {
      serVersionByte <- inBytes.headOption.toRight(ScriptParseError("Can't parse empty script bytes"))
      (versionByte, bytes) <- if (serVersionByte < 4) {
        Right((serVersionByte, inBytes))
      } else {
        Serde.deserialize(inBytes.drop(1), all=false).map({
          case (CONST_LONG(len), rest) =>
              val bytes = inBytes.takeRight(rest).take(len.toInt)
              (bytes.head, bytes)
              }).left.map({e => ScriptParseError(e.toString)})
      }
      a <- {
        val contentTypes   = DirectiveDictionary[ContentType].idMap
        val stdLibVersions = DirectiveDictionary[StdLibVersion].idMap
        versionByte match {
          case 0 =>
            if (bytes.length <= 2)
              Left(ScriptParseError(s"Illegal length of script: ${bytes.length}"))
            else if (!contentTypes.contains(bytes(1)))
              Left(ScriptParseError(s"Invalid content type of script: ${bytes(1)}"))
            else if (!stdLibVersions.contains(bytes(2)))
              Left(ScriptParseError(s"Invalid version of script: ${bytes(2)}"))
            else
              Right((contentTypes(bytes(1)), stdLibVersions(bytes(2)), 3))
          case v if !stdLibVersions.contains(v) => Left(ScriptParseError(s"Invalid version of script: $v"))
          case v                                => Right((Expression, stdLibVersions(v.toInt), 1))
        }
      }
      (scriptType, stdLibVersion, offset) = a
      checkedBytes                        = bytes.dropRight(checksumLength)

      checkSum         = bytes.takeRight(checksumLength)
      computedCheckSum = Global.secureHash(checkedBytes).take(checksumLength)
      _ <- Either.cond(java.util.Arrays.equals(checkSum, computedCheckSum), (), ScriptParseError("Invalid checksum"))

      scriptBytes                         = checkedBytes.drop(offset)
      s <- (scriptType match {
        case Expression | Library =>
          for {
            bytes <- Serde.deserialize(scriptBytes).map(_._1)
            s     <- ExprScript(stdLibVersion, bytes, checkSize = false)
          } yield s
        case DApp =>
          for {
            dapp <- ContractSerDe.deserialize(scriptBytes)
            s    <- ContractScript(stdLibVersion, dapp)
          } yield s
      }).left
        .map(ScriptParseError)
    } yield s
  }
}
