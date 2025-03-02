package com.wavesplatform.api.http.requests

import cats.implicits._
import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.assets.ReissueTransaction
import io.swagger.annotations.ApiModelProperty
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Reads}

case class SignedReissueV2Request(
    @ApiModelProperty(value = "Base58 encoded Issuer public key", required = true)
    senderPublicKey: String,
    @ApiModelProperty(value = "Base58 encoded Asset ID", required = true)
    assetId: String,
    @ApiModelProperty(required = true, example = "1000000")
    quantity: Long,
    @ApiModelProperty(required = true)
    reissuable: Boolean,
    @ApiModelProperty(required = true)
    fee: Long,
    @ApiModelProperty(required = true)
    timestamp: Long,
    @ApiModelProperty(required = true)
    proofs: List[String]
) {
  def toTx: Either[ValidationError, ReissueTransaction] =
    for {
      _sender <- PublicKey.fromBase58String(senderPublicKey)
      chainId = AddressScheme.current.chainId
      _proofBytes <- proofs.traverse(s => parseBase58(s, "invalid proof", Proofs.MaxProofStringSize))
      _proofs     <- Proofs.create(_proofBytes)
      _assetId    <- parseBase58ToAsset(assetId)
      _t          <- ReissueTransaction.create(2.toByte, _sender, _assetId, quantity, reissuable, fee, timestamp, _proofs)
    } yield _t
}

object SignedReissueV2Request {
  implicit val assetReissueRequestReads: Reads[SignedReissueV2Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "assetId").read[String] and
      (JsPath \ "quantity").read[Long] and
      (JsPath \ "reissuable").read[Boolean] and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "proofs").read[List[ProofStr]]
  )(SignedReissueV2Request.apply _)
}
