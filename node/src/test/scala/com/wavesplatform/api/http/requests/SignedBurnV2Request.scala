package com.wavesplatform.api.http.requests

import cats.implicits._
import com.wavesplatform.account.PublicKey
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.assets.BurnTransaction
import io.swagger.annotations.ApiModelProperty
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class SignedBurnV2Request(
    @ApiModelProperty(value = "Base58 encoded Issuer public key", required = true)
    senderPublicKey: String,
    @ApiModelProperty(value = "Base58 encoded Asset ID", required = true)
    assetId: String,
    @ApiModelProperty(required = true, example = "1000000")
    quantity: Long,
    @ApiModelProperty(required = true)
    fee: Long,
    @ApiModelProperty(required = true)
    timestamp: Long,
    @ApiModelProperty(required = true)
    proofs: List[String]
) {

  def toTx: Either[ValidationError, BurnTransaction] =
    for {
      _sender     <- PublicKey.fromBase58String(senderPublicKey)
      _assetId    <- parseBase58ToAsset(assetId)
      _proofBytes <- proofs.traverse(s => parseBase58(s, "invalid proof", Proofs.MaxProofStringSize))
      _proofs     <- Proofs.create(_proofBytes)
      _t <- BurnTransaction.create(2.toByte, _sender, _assetId, quantity, fee, timestamp, _proofs)
    } yield _t
}

object SignedBurnV2Request {
  implicit val reads: Reads[SignedBurnV2Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "assetId").read[String] and
      (JsPath \ "quantity").read[Long].orElse((JsPath \ "amount").read[Long]) and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "proofs").read[List[ProofStr]]
  )(SignedBurnV2Request.apply _)

  implicit val writes: Writes[SignedBurnV2Request] =
    Json.writes[SignedBurnV2Request].transform((request: JsObject) => request + ("version" -> JsNumber(2)))
}
