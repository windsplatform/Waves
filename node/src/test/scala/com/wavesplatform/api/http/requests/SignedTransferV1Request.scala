package com.wavesplatform.api.http.requests

import com.wavesplatform.account.{AddressOrAlias, PublicKey}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer._
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import play.api.libs.functional.syntax._
import play.api.libs.json._

object SignedTransferV1Request {
  implicit val reads: Reads[SignedTransferV1Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "assetId").readNullable[String] and
      (JsPath \ "recipient").read[String] and
      (JsPath \ "amount").read[Long] and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "feeAssetId").read[String].map(Option.apply).orElse((JsPath \ "feeAsset").readNullable[String]) and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "attachment").readNullable[String] and
      (JsPath \ "signature").read[String]
  )(SignedTransferV1Request.apply _)

  implicit val writes: Writes[SignedTransferV1Request] = Json.writes[SignedTransferV1Request]
}

@ApiModel(value = "Signed Asset transfer transaction")
case class SignedTransferV1Request(
    @ApiModelProperty(value = "Base58 encoded sender public key", required = true)
    senderPublicKey: String,
    @ApiModelProperty(value = "Base58 encoded Asset ID")
    assetId: Option[String],
    @ApiModelProperty(value = "Recipient address", required = true)
    recipient: String,
    @ApiModelProperty(required = true, example = "1000000")
    amount: Long,
    @ApiModelProperty(required = true)
    fee: Long,
    @ApiModelProperty(value = "Fee asset ID")
    feeAssetId: Option[String],
    @ApiModelProperty(required = true)
    timestamp: Long,
    @ApiModelProperty(value = "Base58 encoded attachment")
    attachment: Option[String],
    @ApiModelProperty(required = true)
    signature: String
) {
  def toTx: Either[ValidationError, TransferTransaction] =
    for {
      _sender     <- PublicKey.fromBase58String(senderPublicKey)
      _assetId    <- parseBase58ToAssetId(assetId, "invalid.assetId") //parseBase58ToOption(assetId.filter(_.length > 0), "invalid.assetId", transaction.AssetIdStringLength).map(AssetId.fromCompatId)
      _feeAssetId <- parseBase58ToAssetId(feeAssetId, "invalid.feeAssetId") //parseBase58ToOption(feeAssetId.filter(_.length > 0), "invalid.feeAssetId", transaction.AssetIdStringLength).map(AssetId.fromCompatId)
      _signature  <- parseBase58(signature, "invalid.signature", SignatureStringLength)
      _attachment <- parseBase58(attachment.filter(_.length > 0), "invalid.attachment", TransferTransaction.MaxAttachmentStringSize)
      _account    <- AddressOrAlias.fromString(recipient)
      tx <- TransferTransaction.create(
        1.toByte,
        _sender,
        _account,
        _assetId,
        amount,
        _feeAssetId,
        fee,
        _attachment.arr,
        timestamp,
        Proofs(_signature)
      )
    } yield tx
}
