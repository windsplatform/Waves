package com.wavesplatform.transaction.serialization.impl

import java.nio.ByteBuffer

import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.wavesplatform.account.AddressOrAlias
import com.wavesplatform.common.utils._
import com.wavesplatform.serialization._
import com.wavesplatform.transaction.TxVersion
import com.wavesplatform.transaction.transfer.MassTransferTransaction.{ParsedTransfer, Transfer}
import com.wavesplatform.transaction.transfer.{Attachment, MassTransferTransaction}
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.util.Try

object MassTransferTxSerializer {
  def transfersJson(transfers: Seq[ParsedTransfer]): JsValue =
    Json.toJson(transfers.map { case ParsedTransfer(address, amount) => Transfer(address.stringRepr, amount) })

  def toJson(tx: MassTransferTransaction): JsObject = {
    import tx._
    BaseTxJson.toJson(tx) ++ Json.obj(
      "assetId"       -> assetId.maybeBase58Repr,
      "attachment"    -> (if (isProtobufVersion) Json.toJson(attachment) else Base58.encode(attachment.toBytesExact)),
      "transferCount" -> transfers.size,
      "totalAmount"   -> transfers.map(_.amount).sum,
      "transfers"     -> transfersJson(transfers)
    )
  }

  def bodyBytes(tx: MassTransferTransaction): Array[Byte] = {
    import tx._
    version match {
      case TxVersion.V1 =>
        val transferBytes = transfers.map { case ParsedTransfer(recipient, amount) => Bytes.concat(recipient.bytes, Longs.toByteArray(amount)) }

        Bytes.concat(
          Array(builder.typeId, version),
          sender,
          assetId.byteRepr,
          Shorts.toByteArray(transfers.size.toShort),
          Bytes.concat(transferBytes: _*),
          Longs.toByteArray(timestamp),
          Longs.toByteArray(fee),
          Deser.serializeArrayWithLength(attachment.toBytesExact)
        )

      case _ =>
        PBTransactionSerializer.bodyBytes(tx)
    }
  }

  def toBytes(tx: MassTransferTransaction): Array[Byte] = {
    require(!tx.isProtobufVersion, "Should be serialized with protobuf")
    Bytes.concat(this.bodyBytes(tx), tx.proofs.bytes()) // No zero mark
  }

  def parseBytes(bytes: Array[Byte]): Try[MassTransferTransaction] = Try {
    def parseTransfers(buf: ByteBuffer): Seq[MassTransferTransaction.ParsedTransfer] = {
      def readTransfer(buf: ByteBuffer): ParsedTransfer = {
        val addressOrAlias = AddressOrAlias.fromBytes(buf).explicitGet()
        val amount         = buf.getLong
        ParsedTransfer(addressOrAlias, amount)
      }

      val entryCount = buf.getShort
      require(entryCount >= 0 && buf.remaining() > entryCount, s"Broken array size ($entryCount entries while ${buf.remaining()} bytes available)")
      Vector.fill(entryCount)(readTransfer(buf))
    }

    val buf = ByteBuffer.wrap(bytes)
    require(buf.getByte == MassTransferTransaction.typeId && buf.getByte == TxVersion.V1, "transaction type mismatch")

    val sender     = buf.getPublicKey
    val assetId    = buf.getAsset
    val transfers  = parseTransfers(buf)
    val timestamp  = buf.getLong // Timestamp before fee
    val fee        = buf.getLong
    val attachment = Deser.parseArrayWithLength(buf)
    val proofs     = buf.getProofs
    MassTransferTransaction(TxVersion.V1, sender, assetId, transfers, fee, timestamp, Attachment.fromBytes(attachment), proofs)
  }
}
