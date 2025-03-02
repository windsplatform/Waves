package com.wavesplatform.transaction.serialization.impl

import java.nio.ByteBuffer

import com.google.common.primitives.{Bytes, Longs}
import com.wavesplatform.serialization.ByteBufferOps
import com.wavesplatform.serialization.Deser
import com.wavesplatform.transaction.{CreateAliasTransaction, Proofs, Transaction, TxVersion}
import play.api.libs.json.{JsObject, Json}

import scala.util.Try

object CreateAliasTxSerializer {
  def toJson(tx: CreateAliasTransaction): JsObject = {
    import tx._
    BaseTxJson.toJson(tx) ++ Json.obj("alias" -> alias.name)
  }

  def bodyBytes(tx: CreateAliasTransaction): Array[Byte] = {
    import tx._

    lazy val base = Bytes.concat(
      sender,
      Deser.serializeArrayWithLength(alias.bytes.arr),
      Longs.toByteArray(fee),
      Longs.toByteArray(timestamp)
    )

    version match {
      case TxVersion.V1 => Bytes.concat(Array(builder.typeId), base)
      case TxVersion.V2 => Bytes.concat(Array(builder.typeId, version), base)
      case _ => PBTransactionSerializer.bodyBytes(tx)
    }
  }

  def toBytes(tx: CreateAliasTransaction): Array[Byte] = {
    import tx._
    require(!isProtobufVersion, "Should be serialized with protobuf")
    version match {
      case TxVersion.V1 => Bytes.concat(this.bodyBytes(tx), tx.signature)
      case TxVersion.V2 => Bytes.concat(Array(0: Byte), this.bodyBytes(tx), proofs.bytes())
    }
  }

  def parseBytes(bytes: Array[Byte]): Try[CreateAliasTransaction] = Try {
    require(bytes.length > 3, "buffer underflow while parsing transaction")
    bytes.take(3) match {
      case Array(CreateAliasTransaction.typeId, _, _) =>
        val buf       = ByteBuffer.wrap(bytes, 1, bytes.length - 1)
        val sender    = buf.getPublicKey
        val alias     = buf.getAlias
        val fee       = buf.getLong
        val timestamp = buf.getLong
        val signature = buf.getSignature
        CreateAliasTransaction(Transaction.V1, sender, alias, fee, timestamp, Proofs(signature))

      case Array(0, CreateAliasTransaction.typeId, 2) =>
        val buf       = ByteBuffer.wrap(bytes, 3, bytes.length - 3)
        val sender    = buf.getPublicKey
        val alias     = buf.getAlias
        val fee       = buf.getLong
        val timestamp = buf.getLong
        val proofs    = buf.getProofs
        CreateAliasTransaction(Transaction.V2, sender, alias, fee, timestamp, proofs)

      case Array(b1, b2, b3) => throw new IllegalArgumentException(s"Invalid tx header bytes: $b1, $b2, $b3")
    }
  }
}
