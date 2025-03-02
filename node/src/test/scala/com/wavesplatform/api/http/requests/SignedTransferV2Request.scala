package com.wavesplatform.api.http.requests

import cats.implicits._
import com.wavesplatform.account.{AddressOrAlias, PublicKey}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer._
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import play.api.libs.functional.syntax._
import play.api.libs.json._

object SignedTransferV2Request {

  implicit val writes: Writes[SignedTransferV2Request] =
    Json.writes[SignedTransferV2Request].transform((request: JsObject) => request + ("version" -> JsNumber(2)))

  implicit val reads: Reads[SignedTransferV2Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "assetId").readNullable[String] and
      (JsPath \ "recipient").read[String] and
      (JsPath \ "amount").read[Long] and
      (JsPath \ "feeAssetId").readNullable[String] and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "attachment").readNullable[String] and
      (JsPath \ "proofs").read[List[ProofStr]]
  )(SignedTransferV2Request.apply _)
}

@ApiModel(value = "Signed Asset transfer transaction")
case class SignedTransferV2Request(
    @ApiModelProperty(value = "Base58 encoded sender public key", required = true)
    senderPublicKey: String,
    @ApiModelProperty(value = "Base58 encoded Asset ID")
    assetId: Option[String],
    @ApiModelProperty(value = "Recipient address", required = true)
    recipient: String,
    @ApiModelProperty(required = true, example = "1000000")
    amount: Long,
    @ApiModelProperty(value = "Base58 encoded Fee Asset ID")
    feeAssetId: Option[String],
    @ApiModelProperty(required = true)
    fee: Long,
    @ApiModelProperty(required = true)
    timestamp: Long,
    @ApiModelProperty(value = "Base58 encoded attachment")
    attachment: Option[String],
    @ApiModelProperty(required = true)
    proofs: List[String]
) {
  def toTx: Either[ValidationError, TransferTransaction] =
    for {
      _sender     <- PublicKey.fromBase58String(senderPublicKey)
      _assetId    <- parseBase58ToAssetId(assetId.filter(_.length > 0), "invalid.assetId")
      _feeAssetId <- parseBase58ToAssetId(feeAssetId.filter(_.length > 0), "invalid.feeAssetId")
      _proofBytes <- proofs.traverse(s => parseBase58(s, "invalid proof", Proofs.MaxProofStringSize))
      _proofs     <- Proofs.create(_proofBytes)
      _recipient  <- AddressOrAlias.fromString(recipient)
      _attachment <- parseBase58(attachment.filter(_.length > 0), "invalid.attachment", TransferTransaction.MaxAttachmentStringSize)
      tx          <- TransferTransaction.create(2.toByte, _sender, _recipient, _assetId, amount, _feeAssetId, fee, _attachment.arr, timestamp, _proofs)
    } yield tx
}
