package com.wavesplatform.it.sync.grpc

import com.google.protobuf.ByteString
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.it.sync._
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.lang.v2.estimator.ScriptEstimatorV2
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions, Recipient}
import com.wavesplatform.common.utils.{Base58, EitherExt2}
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_BYTESTR, FUNCTION_CALL}
import com.wavesplatform.protobuf.Amount
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.smart.InvokeScriptTransaction.Payment
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import io.grpc.Status.Code

class InvokeScriptPayAndTransferAssetGrpcSuite extends GrpcBaseTransactionSuite {
  private val estimator = ScriptEstimatorV2

  private val (dApp, dAppAddress)     = (firstAcc, firstAddress)
  private val (caller, callerAddress)   = (secondAcc, secondAddress)
  private val (receiver, receiverAddress) = (thirdAcc, thirdAddress)

  val assetQuantity: Long       = 15
  var assetId: String           = ""
  var smartAssetId: String      = ""
  var rejAssetId: String        = ""

  test("issue and transfer asset") {
    assetId = PBTransactions.vanilla(
      sender.grpc.broadcastIssue(caller, "Asset", assetQuantity, 2, reissuable = true, fee = issueFee, waitForTx = true)
    ).explicitGet().id().base58

    val script = Some(ScriptCompiler.compile("true", estimator).explicitGet()._1.bytes.value.base64)
    smartAssetId = PBTransactions.vanilla(
      sender.grpc.broadcastIssue(caller, "Smart", assetQuantity, 2, reissuable = true, fee = issueFee, script = script, waitForTx = true)
    ).explicitGet().id().base58

    val scriptText  = "match tx {case t:TransferTransaction => false case _ => true}"
    val smartScript = Some(ScriptCompiler.compile(scriptText, estimator).explicitGet()._1.bytes.value.base64)
    rejAssetId = PBTransactions.vanilla(
      sender.grpc.broadcastIssue(caller, "Reject", assetQuantity, 2, reissuable = true, fee = issueFee, script = smartScript, waitForTx = true)
    ).explicitGet().id().base58
  }

  test("set script to dApp account and transfer out all waves") {
    val dAppBalance = sender.grpc.wavesBalance(dAppAddress)
    sender.grpc.broadcastTransfer(dApp, Recipient().withAddress(callerAddress), dAppBalance.available - smartMinFee - setScriptFee, smartMinFee, waitForTx = true)

    val dAppScript = ScriptCompiler.compile(
      s"""
         |{-# STDLIB_VERSION 3 #-}
         |{-# CONTENT_TYPE DAPP #-}
         |
         |let receiver = Address(base58'${receiver.toAddress.stringRepr}')
         |
         |@Callable(i)
         |func resendPayment() = {
         |  if (isDefined(i.payment)) then
         |    let pay = extract(i.payment)
         |    TransferSet([ScriptTransfer(receiver, 1, pay.assetId)])
         |  else throw("need payment in WAVES or any Asset")
         |}
        """.stripMargin, estimator).explicitGet()._1
    sender.grpc.setScript(dApp, Some(dAppScript.bytes().base64), fee = setScriptFee, waitForTx = true)
  }

  test("dApp can transfer payed asset if its own balance is 0") {
    val dAppInitBalance = sender.grpc.wavesBalance(dAppAddress)
    val callerInitBalance = sender.grpc.wavesBalance(callerAddress)
    val receiverInitBalance = sender.grpc.wavesBalance(receiverAddress)

    val paymentAmount = 10

    invoke("resendPayment", paymentAmount, assetId)

    sender.grpc.wavesBalance(dAppAddress).regular shouldBe dAppInitBalance.regular
    sender.grpc.wavesBalance(callerAddress).regular shouldBe callerInitBalance.regular - smartMinFee
    sender.grpc.wavesBalance(receiverAddress).regular shouldBe receiverInitBalance.regular

    sender.grpc.assetsBalance(dAppAddress, Seq(assetId)).getOrElse(assetId, 0L) shouldBe paymentAmount - 1
    sender.grpc.assetsBalance(callerAddress, Seq(assetId)).getOrElse(assetId, 0L) shouldBe assetQuantity - paymentAmount
    sender.grpc.assetsBalance(receiverAddress, Seq(assetId)).getOrElse(assetId, 0L) shouldBe 1
  }

  test("dApp can transfer payed smart asset if its own balance is 0") {
    val dAppInitBalance = sender.grpc.wavesBalance(dAppAddress)
    val callerInitBalance = sender.grpc.wavesBalance(callerAddress)
    val receiverInitBalance = sender.grpc.wavesBalance(receiverAddress)

    val paymentAmount = 10
    val fee           = smartMinFee + smartFee * 2

    invoke("resendPayment", paymentAmount, smartAssetId, fee)

    sender.grpc.wavesBalance(dAppAddress).regular shouldBe dAppInitBalance.regular
    sender.grpc.wavesBalance(callerAddress).regular shouldBe callerInitBalance.regular - fee
    sender.grpc.wavesBalance(receiverAddress).regular shouldBe receiverInitBalance.regular

    sender.grpc.assetsBalance(dAppAddress, Seq(smartAssetId)).getOrElse(smartAssetId, 0L) shouldBe paymentAmount - 1
    sender.grpc.assetsBalance(callerAddress, Seq(smartAssetId)).getOrElse(smartAssetId, 0L) shouldBe assetQuantity - paymentAmount
    sender.grpc.assetsBalance(receiverAddress, Seq(smartAssetId)).getOrElse(smartAssetId, 0L) shouldBe 1
  }

  test("dApp can't transfer payed smart asset if it rejects transfers and its own balance is 0") {
    val dAppInitBalance = sender.grpc.wavesBalance(dAppAddress)
    val callerInitBalance = sender.grpc.wavesBalance(callerAddress)
    val receiverInitBalance = sender.grpc.wavesBalance(receiverAddress)

    val paymentAmount = 10
    val fee           = smartMinFee + smartFee * 2

    assertGrpcError(
      invoke("resendPayment", paymentAmount, rejAssetId, fee),
      "Transaction is not allowed by token-script",
      Code.INVALID_ARGUMENT
    )
    sender.grpc.wavesBalance(dAppAddress).regular shouldBe dAppInitBalance.regular
    sender.grpc.wavesBalance(callerAddress).regular shouldBe callerInitBalance.regular
    sender.grpc.wavesBalance(receiverAddress).regular shouldBe receiverInitBalance.regular

    sender.grpc.assetsBalance(dAppAddress, Seq(rejAssetId)).getOrElse(rejAssetId, 0L) shouldBe 0L
    sender.grpc.assetsBalance(callerAddress, Seq(rejAssetId)).getOrElse(rejAssetId, 0L) shouldBe assetQuantity
    sender.grpc.assetsBalance(receiverAddress, Seq(rejAssetId)).getOrElse(rejAssetId, 0L) shouldBe 0L
  }

  test("dApp can transfer payed Waves if its own balance is 0") {
    val dAppInitBalance = sender.grpc.wavesBalance(dAppAddress)
    val callerInitBalance = sender.grpc.wavesBalance(callerAddress)
    val receiverInitBalance = sender.grpc.wavesBalance(receiverAddress)

    dAppInitBalance.regular shouldBe 0

    val paymentAmount    = 10
    invoke("resendPayment", paymentAmount)

    sender.grpc.wavesBalance(dAppAddress).regular shouldBe dAppInitBalance.regular + paymentAmount - 1
    sender.grpc.wavesBalance(callerAddress).regular shouldBe callerInitBalance.regular - paymentAmount - smartMinFee
    sender.grpc.wavesBalance(receiverAddress).regular shouldBe receiverInitBalance.regular + 1
  }


  def invoke(func: String, amount: Long, assetId: String = "WAVES", fee: Long = 500000): PBSignedTransaction = {
    val assetIdByteSting = if (assetId == "WAVES") ByteString.EMPTY else ByteString.copyFrom(Base58.decode(assetId))
    sender.grpc.broadcastInvokeScript(
      caller,
      Recipient().withAddress(dAppAddress),
      Some(FUNCTION_CALL(FunctionHeader.User(func), List.empty)),
      payments = Seq(Amount.of(assetIdByteSting, amount)),
      fee = fee,
      waitForTx = true
    )
  }

}
