package com.wavesplatform.state.diffs

import cats._
import cats.implicits._
import com.wavesplatform.account.Address
import com.wavesplatform.features.FeatureProvider._
import com.wavesplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.directives.values._
import com.wavesplatform.lang.script.v1.ExprScript
import com.wavesplatform.lang.script.{ContractScript, Script}
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state._
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError._
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.assets._
import com.wavesplatform.transaction.assets.exchange._
import com.wavesplatform.transaction.lease._
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.wavesplatform.transaction.transfer._

import scala.util.{Left, Right, Try}

object CommonValidation {

  def disallowSendingGreaterThanBalance[T <: Transaction](blockchain: Blockchain, blockTime: Long, tx: T): Either[ValidationError, T] =
    if (blockTime >= blockchain.settings.functionalitySettings.allowTemporaryNegativeUntil) {
      def checkTransfer(sender: Address, assetId: Asset, amount: Long, feeAssetId: Asset, feeAmount: Long) = {
        val amountDiff = assetId match {
          case aid @ IssuedAsset(_) => Portfolio(0, LeaseBalance.empty, Map(aid -> -amount))
          case Waves                => Portfolio(-amount, LeaseBalance.empty, Map.empty)
        }
        val feeDiff = feeAssetId match {
          case aid @ IssuedAsset(_) => Portfolio(0, LeaseBalance.empty, Map(aid -> -feeAmount))
          case Waves                => Portfolio(-feeAmount, LeaseBalance.empty, Map.empty)
        }

        val spendings       = Monoid.combine(amountDiff, feeDiff)
        val oldWavesBalance = blockchain.balance(sender, Waves)

        val newWavesBalance = oldWavesBalance + spendings.balance
        if (newWavesBalance < 0) {
          Left(
            GenericError(
              "Attempt to transfer unavailable funds: Transaction application leads to " +
                s"negative waves balance to (at least) temporary negative state, current balance equals $oldWavesBalance, " +
                s"spends equals ${spendings.balance}, result is $newWavesBalance"
            )
          )
        } else {
          val balanceError = spendings.assets.collectFirst {
            case (aid, delta) if delta < 0 && blockchain.balance(sender, aid) + delta < 0 =>
              val availableBalance = blockchain.balance(sender, aid)
              GenericError(
                "Attempt to transfer unavailable funds: Transaction application leads to negative asset " +
                  s"'$aid' balance to (at least) temporary negative state, current balance is $availableBalance, " +
                  s"spends equals $delta, result is ${availableBalance + delta}"
              )
          }
          balanceError.fold[Either[ValidationError, T]](Right(tx))(Left(_))
        }
      }

      tx match {
        case ptx: PaymentTransaction if blockchain.balance(ptx.sender, Waves) < (ptx.amount + ptx.fee) =>
          Left(
            GenericError(
              "Attempt to pay unavailable funds: balance " +
                s"${blockchain.balance(ptx.sender, Waves)} is less than ${ptx.amount + ptx.fee}"
            )
          )
        case ttx: TransferTransaction     => checkTransfer(ttx.sender, ttx.assetId, ttx.amount, ttx.feeAssetId, ttx.fee)
        case mtx: MassTransferTransaction => checkTransfer(mtx.sender, mtx.assetId, mtx.transfers.map(_.amount).sum, Waves, mtx.fee)
        case citx: InvokeScriptTransaction =>
          citx.payments.map(p => checkTransfer(citx.sender, p.assetId, p.amount, citx.feeAssetId, citx.fee)).find(_.isLeft).getOrElse(Right(tx))
        case _ => Right(tx)
      }
    } else Right(tx)

  def disallowDuplicateIds[T <: Transaction](blockchain: Blockchain, tx: T): Either[ValidationError, T] = tx match {
    case _: PaymentTransaction => Right(tx)
    case _ =>
      val id = tx.id()
      Either.cond(!blockchain.containsTransaction(tx), tx, AlreadyInTheState(id, blockchain.transactionInfo(id).get._1))
  }

  def disallowBeforeActivationTime[T <: Transaction](blockchain: Blockchain, tx: T): Either[ValidationError, T] = {

    def activationBarrier(b: BlockchainFeature, msg: Option[String] = None): Either[ActivationError, T] =
      Either.cond(
        blockchain.isFeatureActivated(b, blockchain.height),
        tx,
        TxValidationError.ActivationError(msg.getOrElse(b.description + " feature has not been activated yet"))
      )

    def scriptActivation(sc: Script): Either[ActivationError, T] = {

      val ab = activationBarrier(BlockchainFeatures.Ride4DApps)

      def scriptVersionActivation(sc: Script): Either[ActivationError, T] = sc.stdLibVersion match {
        case V1 | V2 if sc.containsBlockV2.value => ab
        case V1 | V2                             => Right(tx)
        case V3                                  => ab
        case V4                                  => activationBarrier(BlockchainFeatures.MultiPaymentInvokeScript)
      }

      def scriptTypeActivation(sc: Script): Either[ActivationError, T] = sc match {
        case e: ExprScript                        => Right(tx)
        case c: ContractScript.ContractScriptImpl => ab
      }

      for {
        _ <- scriptVersionActivation(sc)
        _ <- scriptTypeActivation(sc)
      } yield tx

    }

    def generic1or2Barrier(t: VersionedTransaction, name: String) = {
      if (t.version == 1.toByte) Right(tx)
      else if (t.version == 2.toByte) activationBarrier(BlockchainFeatures.SmartAccounts)
      else Left(GenericError(s"Unknown version of $name transaction: ${t.version}"))
    }

    tx match {
      case _: PaymentTransaction => Right(tx)
      case _: GenesisTransaction => Right(tx)

      case v: VersionedTransaction if v.version < 1 =>
        throw new IllegalArgumentException(s"Invalid tx version: $v")

      case p: VersionedTransaction with LegacyPBSwitch if p.version > p.lastVersion =>
        throw new IllegalArgumentException(s"Invalid tx version: $p")

      case p: LegacyPBSwitch if p.isProtobufVersion =>
        activationBarrier(BlockchainFeatures.BlockV5)

      case e: ExchangeTransaction if e.version == TxVersion.V1 => Right(tx)
      case exv2: ExchangeTransaction if exv2.version >= TxVersion.V2 =>
        activationBarrier(BlockchainFeatures.SmartAccountTrading).flatMap { tx =>
          (exv2.buyOrder, exv2.sellOrder) match {
            case (o1, o2) if o1.version >= 3 || o2.version >= 3 => activationBarrier(BlockchainFeatures.OrderV3)
            case _                                              => Right(tx)
          }
        }

      case _: MassTransferTransaction => activationBarrier(BlockchainFeatures.MassTransfer)
      case _: DataTransaction         => activationBarrier(BlockchainFeatures.DataTransaction)

      case sst: SetScriptTransaction =>
        sst.script match {
          case None     => Right(tx)
          case Some(sc) => scriptActivation(sc)
        }

      case it: IssueTransaction =>
        it.script match {
          case None     => Right(tx)
          case Some(sc) => scriptActivation(sc)
        }

      case sast: SetAssetScriptTransaction =>
        activationBarrier(BlockchainFeatures.SmartAssets).flatMap { _ =>
          sast.script match {
            case None     => Left(GenericError("Cannot set empty script"))
            case Some(sc) => scriptActivation(sc)
          }
        }

      case t: TransferTransaction    => generic1or2Barrier(t, "transfer")
      case t: CreateAliasTransaction => generic1or2Barrier(t, "create alias")
      case t: LeaseTransaction       => generic1or2Barrier(t, "lease")
      case t: LeaseCancelTransaction => generic1or2Barrier(t, "lease cancel")
      case t: ReissueTransaction     => generic1or2Barrier(t, "reissue")
      case t: BurnTransaction        => generic1or2Barrier(t, "burn")

      case _: SponsorFeeTransaction   => activationBarrier(BlockchainFeatures.FeeSponsorship)
      case _: InvokeScriptTransaction => activationBarrier(BlockchainFeatures.Ride4DApps)

      case _ => Left(GenericError("Unknown transaction must be explicitly activated"))
    }
  }

  def disallowTxFromFuture[T <: Transaction](settings: FunctionalitySettings, time: Long, tx: T): Either[ValidationError, T] = {
    val allowTransactionsFromFutureByTimestamp = tx.timestamp < settings.allowTransactionsFromFutureUntil
    if (!allowTransactionsFromFutureByTimestamp && tx.timestamp - time > settings.maxTransactionTimeForwardOffset.toMillis)
      Left(
        Mistiming(
          s"""Transaction timestamp ${tx.timestamp}
       |is more than ${settings.maxTransactionTimeForwardOffset.toMillis}ms in the future
       |relative to block timestamp $time""".stripMargin
            .replaceAll("\n", " ")
            .replaceAll("\r", "")
        )
      )
    else Right(tx)
  }

  def disallowTxFromPast[T <: Transaction](settings: FunctionalitySettings, prevBlockTime: Option[Long], tx: T): Either[ValidationError, T] =
    prevBlockTime match {
      case Some(t) if (t - tx.timestamp) > settings.maxTransactionTimeBackOffset.toMillis =>
        Left(
          Mistiming(
            s"""Transaction timestamp ${tx.timestamp}
         |is more than ${settings.maxTransactionTimeBackOffset.toMillis}ms in the past
         |relative to previous block timestamp $prevBlockTime""".stripMargin
              .replaceAll("\n", " ")
              .replaceAll("\r", "")
          )
        )
      case _ => Right(tx)
    }

  def validateOverflow(dataList: Traversable[Long], errMsg: String): Either[ValidationError, Unit] = {
    Try(dataList.foldLeft(0L)(Math.addExact))
      .fold(
        _ => GenericError(errMsg).asLeft[Unit],
        _ => ().asRight[ValidationError]
      )
  }
}
